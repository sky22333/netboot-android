package mobilecore

import (
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
	response, _ := buildDHCPResponse(dhcpRequest(1, 7, true), cfg, newLeasePool(cfg.DHCP), "67")
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
	response, _ := buildDHCPResponse(dhcpRequest(1, 0, false), cfg, newLeasePool(cfg.DHCP), "67")
	if response != nil {
		t.Fatal("proxy DHCP must ignore non-PXE clients")
	}
}

func TestIPXEUsesHTTPChain(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	request := dhcpRequest(1, 7, true)
	request = appendOption(request[:len(request)-1], 175, []byte{1})
	request = append(request, 255)
	response, _ := buildDHCPResponse(request, cfg, newLeasePool(cfg.DHCP), "67")
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
