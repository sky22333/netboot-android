package mobilecore

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
)

func dhcpRequest(message byte, architecture uint16, pxe bool) []byte {
	packet := make([]byte, 240)
	packet[0] = 1
	packet[1] = 1
	packet[2] = 6
	copy(packet[4:8], []byte{1, 2, 3, 4})
	copy(packet[28:34], []byte{0x02, 0, 0, 0, 0, 1})
	copy(packet[236:240], []byte(dhcpCookie))
	packet = appendOption(packet, 53, []byte{message})
	if pxe {
		packet = appendOption(packet, 60, []byte("PXEClient"))
		arch := make([]byte, 2)
		binary.BigEndian.PutUint16(arch, architecture)
		packet = appendOption(packet, 93, arch)
	}
	return append(packet, 255)
}

func testDHCPConfig(mode string) config {
	return config{
		ListenIP: "0.0.0.0", AdvertiseIP: "192.168.50.1", Mode: mode, HTTPPort: 8080, BootFile: "ipxe.efi",
		DHCP: dhcpConfig{PoolStart: "192.168.50.10", PoolEnd: "192.168.50.20", SubnetMask: "255.255.255.0", Router: "192.168.50.1", DNS: "192.168.50.1", LeaseSeconds: 3600},
	}
}

func TestDHCPAssignsLease(t *testing.T) {
	cfg := testDHCPConfig(ModeDHCP)
	response, _ := buildDHCPResponse(dhcpRequest(1, 7, true), cfg, newLeasePool(cfg.DHCP), "67", nil)
	if len(response) < 240 {
		t.Fatal("missing response")
	}
	if got := net.IP(response[16:20]).String(); got != "192.168.50.10" {
		t.Fatalf("unexpected lease %s", got)
	}
	options := parseDHCPOptions(response[240:])
	if first(options[53]) != 2 || string(options[67]) != "ipxe.efi" {
		t.Fatalf("unexpected options: %#v", options)
	}
}

func TestProxyIgnoresNonPXEClient(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	response, _ := buildDHCPResponse(dhcpRequest(1, 0, false), cfg, newLeasePool(cfg.DHCP), "67", nil)
	if response != nil {
		t.Fatal("proxy DHCP must ignore non-PXE clients")
	}
}

func TestIPXEUsesHTTPChain(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	request := dhcpRequest(1, 7, true)
	request = appendOption(request[:len(request)-1], 175, []byte{1})
	request = append(request, 255)
	response, _ := buildDHCPResponse(request, cfg, newLeasePool(cfg.DHCP), "67", nil)
	options := parseDHCPOptions(response[240:])
	if string(options[67]) != "http://192.168.50.1:8080/boot.ipxe" {
		t.Fatalf("unexpected iPXE URL %q", options[67])
	}
}

func TestClientArchitecture(t *testing.T) {
	request := dhcpRequest(1, 11, true)
	if got := clientArchitecture(parseDHCPOptions(request[240:])); got != "arm64" {
		t.Fatalf("unexpected architecture %q", got)
	}
}

func TestBootFileFollowsClientArchitecture(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	cfg.BootFile = ""
	for _, test := range []struct {
		architecture uint16
		want         string
	}{
		{0, "undionly.kpxe"},
		{7, "ipxe-x86_64.efi"},
		{9, "ipxe-x86_64.efi"},
		{11, "ipxe-arm64.efi"},
	} {
		request := dhcpRequest(1, test.architecture, true)
		if got := bootFileFor(parseDHCPOptions(request[240:]), cfg); got != test.want {
			t.Fatalf("architecture %d: got %q, want %q", test.architecture, got, test.want)
		}
	}
}

func TestBootFileIsEmptyWithoutABundledImage(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	cfg.BootFile = ""
	// efi32 ships no IA32 image, and a client that sends no architecture option cannot be matched.
	for _, architecture := range []uint16{6, 12} {
		request := dhcpRequest(1, architecture, true)
		if got := bootFileFor(parseDHCPOptions(request[240:]), cfg); got != "" {
			t.Fatalf("architecture %d: expected no bundled image, got %q", architecture, got)
		}
	}
	plain := dhcpRequest(1, 0, false)
	if got := bootFileFor(parseDHCPOptions(plain[240:]), cfg); got != "" {
		t.Fatalf("expected no bundled image without an architecture option, got %q", got)
	}
}

func TestUnbundledArchitectureAdvertisesNoBootFile(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	cfg.BootFile = ""
	response, _ := buildDHCPResponse(dhcpRequest(1, 6, true), cfg, newLeasePool(cfg.DHCP), "67", nil)
	if response == nil {
		t.Fatal("expected a proxy offer")
	}
	options := parseDHCPOptions(response[240:])
	if value, present := options[67]; present {
		t.Fatalf("expected no boot file option, got %q", value)
	}
	if file := bytes.TrimRight(response[108:236], "\x00"); len(file) != 0 {
		t.Fatalf("expected an empty boot file field, got %q", file)
	}
}
