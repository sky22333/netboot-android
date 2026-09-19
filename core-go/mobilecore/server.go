package mobilecore

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"
)

type server struct {
	cfg       config
	sink      *eventSink
	cancel    context.CancelFunc
	startedAt time.Time
	wg        sync.WaitGroup
	http      *http.Server
	httpLn    net.Listener
	udp       []net.PacketConn
	leases    *leasePool
}

func newServer(cfg config, listener Listener) *server {
	return &server{cfg: cfg, sink: &eventSink{listener: listener}, leases: newLeasePool(cfg.DHCP)}
}

func (s *server) start() error {
	ctx, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	httpLn, err := net.Listen("tcp4", net.JoinHostPort(s.cfg.ListenIP, fmt.Sprint(s.cfg.HTTPPort)))
	if err != nil {
		cancel()
		return fmt.Errorf("listen HTTP: %w", err)
	}
	s.httpLn = httpLn
	tftpConn, err := net.ListenPacket("udp4", net.JoinHostPort(s.cfg.ListenIP, "69"))
	if err != nil {
		s.closeListeners()
		cancel()
		return fmt.Errorf("listen TFTP: %w", err)
	}
	s.udp = append(s.udp, tftpConn)
	dhcpPorts := []string{"67", "4011"}
	for _, port := range dhcpPorts {
		conn, listenErr := listenDHCPInterface(s.cfg.ListenIP, port)
		if listenErr != nil {
			s.closeListeners()
			cancel()
			return fmt.Errorf("listen DHCP port %s: %w", port, listenErr)
		}
		s.udp = append(s.udp, conn)
	}

	s.startedAt = time.Now()
	s.http = newHTTPServer(s.cfg, s.sink)
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		if serveErr := s.http.Serve(httpLn); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			s.sink.emit("error", "http", "http_failed", map[string]string{"error": serveErr.Error()})
		}
	}()
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		serveTFTP(ctx, tftpConn, s.cfg, s.sink)
	}()
	for index, conn := range s.udp[1:] {
		port := dhcpPorts[index]
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			serveDHCP(ctx, conn, s.cfg, s.leases, s.sink, port)
		}()
	}
	s.sink.emit("info", "runtime", "netboot_started", map[string]string{"mode": s.cfg.Mode})
	return nil
}

func (s *server) stop(timeout time.Duration) error {
	if s.cancel != nil {
		s.cancel()
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	if s.http != nil {
		_ = s.http.Shutdown(ctx)
	}
	s.closeListeners()
	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		s.sink.emit("info", "runtime", "netboot_stopped", nil)
		s.sink.clear()
		return nil
	case <-ctx.Done():
		s.sink.clear()
		return errors.New("timed out stopping netboot")
	}
}

func (s *server) closeListeners() {
	if s.httpLn != nil {
		_ = s.httpLn.Close()
	}
	for _, conn := range s.udp {
		_ = conn.Close()
	}
}
