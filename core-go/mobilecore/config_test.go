package mobilecore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func validConfigJSON(t *testing.T, mode string) string {
	t.Helper()
	root := t.TempDir()
	raw, err := json.Marshal(config{
		ListenIP:    "127.0.0.1",
		AdvertiseIP: "192.168.1.2",
		Mode:        mode,
		Root:        root,
		HTTPPort:    8080,
		BootFile:    "ipxe.efi",
		DHCP: dhcpConfig{
			PoolStart:    "192.168.1.100",
			PoolEnd:      "192.168.1.120",
			SubnetMask:   "255.255.255.0",
			Router:       "192.168.1.1",
			DNS:          "192.168.1.1",
			LeaseSeconds: 3600,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

func TestParseConfigAppliesDefaults(t *testing.T) {
	cfg, err := parseConfig(validConfigJSON(t, ModeProxy))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.MaxTransfers != 16 || cfg.IPXEScript == "" {
		t.Fatalf("defaults were not applied: %+v", cfg)
	}
}

func TestParseConfigKeepsAnEmptyBootFileAutomatic(t *testing.T) {
	raw, err := json.Marshal(config{
		ListenIP:    "127.0.0.1",
		AdvertiseIP: "192.168.1.2",
		Mode:        ModeProxy,
		Root:        t.TempDir(),
		HTTPPort:    8080,
	})
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := parseConfig(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.BootFile != "" {
		t.Fatalf("an omitted boot file must stay empty so the architecture decides: %q", cfg.BootFile)
	}
}

func TestParseConfigRejectsUnknownAndTrailingFields(t *testing.T) {
	if _, err := parseConfig(`{"unknown":true}`); err == nil {
		t.Fatal("expected unknown field error")
	}
	raw := validConfigJSON(t, ModeProxy) + `{}`
	if _, err := parseConfig(raw); err == nil {
		t.Fatal("expected trailing data error")
	}
}

func TestSafeReadPath(t *testing.T) {
	root := t.TempDir()
	inside := filepath.Join(root, "boot.ipxe")
	if err := os.WriteFile(inside, []byte("#!ipxe"), 0o600); err != nil {
		t.Fatal(err)
	}
	path, err := safeReadPath(root, "boot.ipxe")
	if err != nil || path != inside {
		t.Fatalf("unexpected path result %q: %v", path, err)
	}
	for _, invalid := range []string{"../outside", "/absolute", "", "missing"} {
		if _, err := safeReadPath(root, invalid); err == nil {
			t.Fatalf("expected %q to fail", invalid)
		}
	}
}
