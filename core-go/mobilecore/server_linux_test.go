//go:build linux

package mobilecore

import (
	"io"
	"net"
	"net/http"
	"os"
	"testing"
	"time"
)

// Opt in on a dedicated emulator with low-port binding permission: this test owns the real DHCP/TFTP ports.
func TestNetworkStartWithClientPortOccupied(t *testing.T) {
	if os.Getenv("NETBOOT_NETWORK_TEST") != "1" {
		t.Skip("requires a dedicated emulator with low-port binding permission; set NETBOOT_NETWORK_TEST=1")
	}
	client, err := net.ListenPacket("udp4", "0.0.0.0:68")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	for _, mode := range []string{ModeProxy, ModeDHCP} {
		t.Run(mode, func(t *testing.T) {
			cfg := testDHCPConfig(mode)
			cfg.ListenIP = "127.0.0.1"
			cfg.Root = t.TempDir()
			cfg.HTTPPort = 0
			cfg.IPXEScript = "#!ipxe\necho startup-test\n"
			cfg.MaxTransfers = 1
			srv := newServer(cfg, nil)
			started := time.Now()
			if err := srv.start(); err != nil {
				t.Fatal(err)
			}
			elapsed := time.Since(started)
			defer func() {
				if err := srv.stop(time.Second); err != nil {
					t.Error(err)
				}
			}()
			t.Logf("startup: %s", elapsed)
			if elapsed >= 2*time.Second {
				t.Errorf("startup exceeded 2 seconds: %s", elapsed)
			}
			httpClient := &http.Client{Timeout: time.Second}
			response, err := httpClient.Get("http://" + srv.httpLn.Addr().String() + "/boot.ipxe")
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			body, err := io.ReadAll(response.Body)
			if err != nil || response.StatusCode != http.StatusOK || string(body) != cfg.IPXEScript {
				t.Fatalf("boot script not ready: status=%d body=%q err=%v", response.StatusCode, body, err)
			}
		})
	}
}
