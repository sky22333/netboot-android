package mobilecore

import (
	"net"
	"strings"
	"testing"
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
