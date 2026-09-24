package mobilecore

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestStartReportsOccupiedHTTPPortInBothModes(t *testing.T) {
	occupied, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	for _, mode := range []string{ModeProxy, ModeDHCP} {
		t.Run(mode, func(t *testing.T) {
			cfg := testDHCPConfig(mode)
			cfg.ListenIP = "127.0.0.1"
			cfg.HTTPPort = occupied.Addr().(*net.TCPAddr).Port
			srv := newServer(cfg, nil)
			err := srv.start()
			if err == nil || !strings.HasPrefix(err.Error(), "listen HTTP:") {
				t.Fatalf("expected local port failure, got %v", err)
			}
			if srv.httpLn != nil || len(srv.udp) != 0 {
				t.Fatal("failed startup retained listeners")
			}
		})
	}
}

func TestStopClosesActiveHTTPTransfer(t *testing.T) {
	entered := make(chan struct{})
	exited := make(chan struct{})
	srv := &server{sink: &eventSink{}}
	httpServer := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		<-r.Context().Done()
		close(exited)
	}))
	httpServer.Config.ConnState = srv.httpConnectionState
	httpServer.Start()
	defer httpServer.Close()
	requestDone := make(chan struct{})
	go func() {
		defer close(requestDone)
		response, err := http.Get(httpServer.URL)
		if err == nil {
			response.Body.Close()
		}
	}()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("request did not arrive")
	}
	srv.http = httpServer.Config
	if err := srv.stop(time.Second); err != nil {
		t.Fatal(err)
	}
	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("active handler survived stop")
	}
	select {
	case <-requestDone:
	case <-time.After(time.Second):
		t.Fatal("client connection survived stop")
	}
	if err := srv.stop(time.Second); err != nil {
		t.Fatal(err)
	}
}
