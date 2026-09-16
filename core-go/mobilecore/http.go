package mobilecore

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func newHTTPServer(cfg config, sink *eventSink) *http.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/boot.ipxe", func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet && request.Method != http.MethodHead {
			response.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		response.Header().Set("Content-Type", "text/plain; charset=utf-8")
		response.Header().Set("Cache-Control", "no-store")
		if request.Method == http.MethodGet {
			_, _ = response.Write([]byte(cfg.IPXEScript))
		}
	})
	mux.HandleFunc("/", func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet && request.Method != http.MethodHead {
			response.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		name := strings.TrimPrefix(request.URL.Path, "/")
		path, err := safeReadPath(cfg.Root, name)
		if err != nil {
			http.NotFound(response, request)
			return
		}
		file, err := os.Open(path)
		if err != nil {
			http.NotFound(response, request)
			return
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil {
			http.NotFound(response, request)
			return
		}
		response.Header().Set("X-Content-Type-Options", "nosniff")
		response.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=%q", filepath.Base(path)))
		http.ServeContent(response, request, info.Name(), info.ModTime(), file)
	})
	return &http.Server{
		Handler:           logHTTPRequests(mux, sink),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      0,
		IdleTimeout:       60 * time.Second,
	}
}

type responseMetrics struct {
	http.ResponseWriter
	status int
	bytes  int64
}

func (r *responseMetrics) WriteHeader(status int) {
	if r.status == 0 {
		r.status = status
	}
	r.ResponseWriter.WriteHeader(status)
}

func (r *responseMetrics) Write(content []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	n, err := r.ResponseWriter.Write(content)
	r.bytes += int64(n)
	return n, err
}

func logHTTPRequests(next http.Handler, sink *eventSink) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		started := time.Now()
		metrics := &responseMetrics{ResponseWriter: response}
		next.ServeHTTP(metrics, request)
		status := metrics.status
		if status == 0 {
			status = http.StatusOK
		}
		level := "info"
		if status >= http.StatusBadRequest {
			level = "warning"
		}
		sink.emit(level, "http", "http_request", map[string]string{
			"bytes":    fmt.Sprint(metrics.bytes),
			"client":   clientAddress(request),
			"duration": fmt.Sprint(time.Since(started).Milliseconds()),
			"method":   request.Method,
			"path":     request.URL.Path,
			"range":    request.Header.Get("Range"),
			"status":   fmt.Sprint(status),
		})
	})
}

func clientAddress(request *http.Request) string {
	value := request.RemoteAddr
	if index := strings.LastIndex(value, ":"); index > 0 {
		return strings.Trim(value[:index], "[]")
	}
	return value
}
