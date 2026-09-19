package mobilecore

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func requestOptions(message byte, options map[byte][]byte, ciaddr string) []byte {
	p := dhcpRequest(message, 7, true)
	p = p[:len(p)-1]
	for code, value := range options {
		p = appendOption(p, code, value)
	}
	if ciaddr != "" {
		copy(p[12:16], net.ParseIP(ciaddr).To4())
	}
	return append(p, 255)
}

func TestDHCPRequestStates(t *testing.T) {
	cfg := testDHCPConfig(ModeDHCP)
	server := net.ParseIP(cfg.AdvertiseIP).To4()
	for _, tt := range []struct {
		name    string
		offer   bool
		options map[byte][]byte
		ciaddr  string
		want    byte
	}{
		{"select offered", true, map[byte][]byte{54: server, 50: net.ParseIP("192.168.50.10").To4()}, "", 5},
		{"select foreign", true, map[byte][]byte{54: net.ParseIP("192.168.50.2").To4(), 50: net.ParseIP("192.168.50.10").To4()}, "", 0},
		{"request another address", true, map[byte][]byte{54: server, 50: net.ParseIP("192.168.50.11").To4()}, "", 6},
		{"reboot known", true, map[byte][]byte{50: net.ParseIP("192.168.50.10").To4()}, "", 5},
		{"reboot unknown same subnet", false, map[byte][]byte{50: net.ParseIP("192.168.50.10").To4()}, "", 0},
		{"reboot wrong subnet", false, map[byte][]byte{50: net.ParseIP("192.168.60.10").To4()}, "", 6},
		{"renew known", true, nil, "192.168.50.10", 5},
		{"renew unknown", false, nil, "192.168.50.10", 0},
		{"select without requested address", true, map[byte][]byte{54: server}, "", 0},
	} {
		t.Run(tt.name, func(t *testing.T) {
			pool := newLeasePool(cfg.DHCP)
			if tt.offer {
				buildDHCPResponse(dhcpRequest(1, 7, true), cfg, pool, "67")
			}
			reply, target := buildDHCPResponse(requestOptions(3, tt.options, tt.ciaddr), cfg, pool, "67")
			var got byte
			if len(reply) > 0 {
				got = first(parseDHCPOptions(reply[240:])[53])
			}
			if got != tt.want {
				t.Fatalf("type=%d want=%d", got, tt.want)
			}
			if got == 5 && tt.ciaddr != "" && target.(*net.UDPAddr).IP.String() != tt.ciaddr {
				t.Fatal("renewal was not unicast")
			}
		})
	}
}

func TestDHCPInformDoesNotAllocateOrLease(t *testing.T) {
	cfg := testDHCPConfig(ModeDHCP)
	pool := newLeasePool(cfg.DHCP)
	reply, target := buildDHCPResponse(requestOptions(8, nil, "192.168.50.40"), cfg, pool, "67")
	opts := parseDHCPOptions(reply[240:])
	if first(opts[53]) != 5 || opts[51] != nil || !net.IP(reply[16:20]).Equal(net.IPv4zero) || len(pool.byClient) != 0 {
		t.Fatal("INFORM must ACK configuration only")
	}
	if target.(*net.UDPAddr).IP.String() != "192.168.50.40" {
		t.Fatal("INFORM must reply to ciaddr")
	}
}

func TestDHCPClientIdentityDeclineAndRelease(t *testing.T) {
	cfg := testDHCPConfig(ModeDHCP)
	pool := newLeasePool(cfg.DHCP)
	id := []byte{1, 2, 3, 4}
	opts := map[byte][]byte{61: id}
	reply, _ := buildDHCPResponse(requestOptions(1, opts, ""), cfg, pool, "67")
	ip := bytes.Clone(reply[16:20])
	opts[54] = net.ParseIP(cfg.AdvertiseIP).To4()
	opts[50] = ip
	reply, _ = buildDHCPResponse(requestOptions(3, opts, ""), cfg, pool, "67")
	if !bytes.Equal(parseDHCPOptions(reply[240:])[61], id) {
		t.Fatal("client ID was not echoed")
	}
	buildDHCPResponse(requestOptions(4, opts, ""), cfg, pool, "67")
	reply, _ = buildDHCPResponse(requestOptions(1, map[byte][]byte{61: id}, ""), cfg, pool, "67")
	if bytes.Equal(reply[16:20], ip) {
		t.Fatal("declined IP was reused")
	}
	next := net.IP(reply[16:20]).String()
	buildDHCPResponse(requestOptions(7, map[byte][]byte{61: id, 54: opts[54]}, next), cfg, pool, "67")
	if len(pool.byClient) != 0 {
		t.Fatal("release did not remove binding")
	}
	pool.declined[ipToUint(ip)] = time.Now().Add(-time.Second)
	pool.offer("other", ip)
	if pool.byClient["other"].ip != ipToUint(ip) {
		t.Fatal("expired quarantine not reclaimed")
	}
}

func TestDHCPExhaustionAndUnsupportedRelay(t *testing.T) {
	cfg := testDHCPConfig(ModeDHCP)
	cfg.DHCP.PoolEnd = cfg.DHCP.PoolStart
	pool := newLeasePool(cfg.DHCP)
	buildDHCPResponse(dhcpRequest(1, 7, true), cfg, pool, "67")
	req := dhcpRequest(1, 7, true)
	req[33]++
	if reply, _ := buildDHCPResponse(req, cfg, pool, "67"); reply != nil {
		t.Fatal("exhausted DISCOVER must not NAK")
	}
	copy(req[24:28], net.ParseIP("192.168.60.1").To4())
	if reply, _ := buildDHCPResponse(req, cfg, pool, "67"); reply != nil {
		t.Fatal("must not allocate local pool to relay")
	}
}
