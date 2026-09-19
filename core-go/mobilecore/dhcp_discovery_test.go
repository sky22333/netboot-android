package mobilecore

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"testing"
	"time"
)

func TestPXEBootDiscovery(t *testing.T) {
	for _, mode := range []string{ModeProxy, ModeDHCP} {
		for _, port := range []string{"67", "4011"} {
			for _, message := range []byte{3, 8} {
				for _, broadcast := range []bool{false, true} {
					t.Run(fmt.Sprintf("%s/%s/%d/broadcast=%t", mode, port, message, broadcast), func(t *testing.T) {
						cfg := testDHCPConfig(mode)
						cfg.BootFile = ""
						pool := newLeasePool(cfg.DHCP)
						pool.offer("existing", nil)
						before := pool.byClient["existing"]
						uuid := append([]byte{0}, bytes.Repeat([]byte{0x42}, 16)...)
						request := requestOptions(message, map[byte][]byte{
							43: {71, 4, 0, 0, 0, 0, 255}, 94: {1, 3, 16}, 97: uuid,
						}, "192.168.50.40")
						if broadcast {
							binary.BigEndian.PutUint16(request[10:12], 0x8000)
						}
						reply, target := buildDHCPResponse(request, cfg, pool, port, nil)
						if len(reply) < 240 {
							t.Fatal("boot discovery was ignored")
						}
						opts := parseDHCPOptions(reply[240:])
						if first(opts[53]) != 5 || string(opts[60]) != "PXEClient" || string(opts[67]) != "ipxe-x86_64.efi" {
							t.Fatalf("invalid boot ACK: %v", opts)
						}
						if !net.IP(reply[12:16]).Equal(net.IPv4zero) || !net.IP(reply[16:20]).Equal(net.ParseIP("192.168.50.40")) || !net.IP(reply[20:24]).Equal(net.ParseIP(cfg.AdvertiseIP)) {
							t.Fatalf("invalid boot ACK addresses: %v", reply[12:24])
						}
						if !bytes.Equal(reply[4:8], request[4:8]) || !bytes.Equal(reply[28:44], request[28:44]) {
							t.Fatal("client transaction identity changed")
						}
						for _, code := range []byte{43, 93, 94, 97} {
							if !bytes.Equal(opts[code], parseDHCPOptions(request[240:])[code]) {
								t.Fatalf("PXE option %d was not returned", code)
							}
						}
						for _, code := range []byte{1, 3, 6, 51} {
							if opts[code] != nil {
								t.Fatalf("boot discovery must not assign network configuration: %d", code)
							}
						}
						if len(pool.byClient) != 1 || pool.byClient["existing"] != before {
							t.Fatal("boot discovery modified leases")
						}
						if broadcast && port == "67" {
							if target == nil || target.String() != "255.255.255.255:68" {
								t.Fatalf("wrong broadcast target: %v", target)
							}
						} else if target != nil {
							t.Fatalf("directed reply must use source endpoint: %v", target)
						}
					})
				}
			}
		}
	}
}

func TestPXEDiscoveryRejectsUnsupportedRequests(t *testing.T) {
	for _, tt := range []struct {
		name, address, reason string
		vendor                []byte
	}{
		{"service type", "192.168.50.40", "unsupported_boot_type", []byte{71, 4, 0, 1, 0, 0}},
		{"layer", "192.168.50.40", "unsupported_boot_layer", []byte{71, 4, 0, 0, 0, 1}},
		{"credentials", "192.168.50.40", "unsupported_boot_layer", []byte{71, 4, 0, 0, 128, 0}},
		{"missing address", "", "invalid_boot_request", []byte{71, 4, 0, 0, 0, 0}},
		{"short item", "192.168.50.40", "invalid_boot_item", []byte{71, 3, 0, 0, 0}},
		{"truncated TLV", "192.168.50.40", "invalid_boot_item", []byte{71, 4, 0}},
		{"duplicate item", "192.168.50.40", "invalid_boot_item", []byte{71, 4, 0, 0, 0, 0, 71, 4, 0, 0, 0, 0}},
	} {
		for _, mode := range []string{ModeProxy, ModeDHCP} {
			for _, port := range []string{"67", "4011"} {
				t.Run(tt.name+"/"+mode+"/"+port, func(t *testing.T) {
					cfg := testDHCPConfig(mode)
					listener := &discoveryEvents{}
					pool := newLeasePool(cfg.DHCP)
					reply, _ := buildDHCPResponse(requestOptions(3, map[byte][]byte{43: tt.vendor}, tt.address), cfg, pool, port, &eventSink{listener: listener})
					if reply != nil || len(pool.byClient) != 0 {
						t.Fatal("unsupported discovery was answered or allocated a lease")
					}
					if len(listener.events) != 1 || listener.events[0].Code != "request_rejected" || listener.events[0].Arguments["reason"] != tt.reason {
						t.Fatalf("missing diagnostic: %+v", listener.events)
					}
				})
			}
		}
	}
}

func TestProxyLeavesAddressAssignmentToRouter(t *testing.T) {
	cfg := testDHCPConfig(ModeProxy)
	for _, message := range []byte{3, 4, 7, 8} {
		for _, address := range []string{"", "192.168.50.40"} {
			pool := newLeasePool(cfg.DHCP)
			listener := &discoveryEvents{}
			request := requestOptions(message, map[byte][]byte{54: net.ParseIP("192.168.50.254").To4()}, address)
			if reply, _ := buildDHCPResponse(request, cfg, pool, "67", &eventSink{listener: listener}); reply != nil || len(pool.byClient) != 0 || len(listener.events) != 0 {
				t.Fatalf("interfered with router traffic: message=%d address=%s", message, address)
			}
		}
	}
}

func TestPXE4011DefaultItemAndArchitecture(t *testing.T) {
	for _, mode := range []string{ModeProxy, ModeDHCP} {
		cfg := testDHCPConfig(mode)
		cfg.BootFile = ""
		for _, architecture := range []uint16{0, 6, 7, 9, 11} {
			request := dhcpRequest(3, architecture, true)
			copy(request[12:16], net.ParseIP("192.168.50.40").To4())
			reply, _ := buildDHCPResponse(request, cfg, newLeasePool(cfg.DHCP), "4011", nil)
			if architecture == 6 {
				if reply != nil {
					t.Fatal("unsupported architecture received a boot ACK")
				}
				continue
			}
			if len(reply) < 240 {
				t.Fatal("default boot query was rejected")
			}
			opts := parseDHCPOptions(reply[240:])
			if first(opts[53]) != 5 || len(opts[67]) == 0 || opts[43] != nil {
				t.Fatalf("invalid default boot reply: %v", opts)
			}
			if !net.IP(reply[16:20]).Equal(net.IPv4zero) || !bytes.Equal(reply[12:16], request[12:16]) {
				t.Fatal("4011 query without a boot item must retain the ProxyDHCP layout")
			}
		}
	}
}

func TestPXEDiscoveryDoesNotAnswerForeignServerOrLeaseSelection(t *testing.T) {
	for _, mode := range []string{ModeProxy, ModeDHCP} {
		for _, port := range []string{"67", "4011"} {
			cfg := testDHCPConfig(mode)
			for _, option := range []byte{50, 54} {
				request := requestOptions(3, map[byte][]byte{
					43: {71, 4, 0, 0, 0, 0}, option: net.ParseIP("192.168.50.254").To4(),
				}, "192.168.50.40")
				pool := newLeasePool(cfg.DHCP)
				if reply, _ := buildDHCPResponse(request, cfg, pool, port, nil); reply != nil || len(pool.byClient) != 0 {
					t.Fatalf("answered foreign/lease request: mode=%s port=%s option=%d", mode, port, option)
				}
			}
		}
	}

}

func TestPXEDiscoveryKeepsIPXEChainAndExplicitBootFile(t *testing.T) {
	for _, mode := range []string{ModeProxy, ModeDHCP} {
		cfg := testDHCPConfig(mode)
		for _, ipxe := range []bool{false, true} {
			options := map[byte][]byte{43: {71, 4, 0, 0, 0, 0}}
			want := cfg.BootFile
			if ipxe {
				options[175] = []byte{1}
				want = "http://192.168.50.1:8080/boot.ipxe"
			}
			reply, _ := buildDHCPResponse(requestOptions(3, options, "192.168.50.40"), cfg, newLeasePool(cfg.DHCP), "67", nil)
			if len(reply) < 240 || string(parseDHCPOptions(reply[240:])[67]) != want {
				t.Fatalf("boot selection changed: mode=%s ipxe=%t", mode, ipxe)
			}
		}
	}
}

func TestPXEVendorOptionsWithoutEnd(t *testing.T) {
	item, valid := parsePXEBootItem([]byte{0, 6, 1, 0, 71, 4, 0, 0, 0, 0})
	if !valid || !bytes.Equal(item, []byte{0, 0, 0, 0}) {
		t.Fatal("valid vendor TLVs rejected")
	}
}

type discoveryEvents struct{ events []event }

func (l *discoveryEvents) OnEvent(raw string) {
	var e event
	_ = json.Unmarshal([]byte(raw), &e)
	l.events = append(l.events, e)
}

// Exercise the real receive/dispatch/send loop without binding LAN service ports.
type discoveryConn struct {
	packet         []byte
	remote, target net.Addr
	written        []byte
}

func (c *discoveryConn) ReadFrom(b []byte) (int, net.Addr, error) {
	if c.packet == nil {
		return 0, nil, net.ErrClosed
	}
	n := copy(b, c.packet)
	c.packet = nil
	return n, c.remote, nil
}
func (c *discoveryConn) WriteTo(b []byte, target net.Addr) (int, error) {
	c.written = bytes.Clone(b)
	c.target = target
	return len(b), nil
}
func (c *discoveryConn) Close() error                     { return nil }
func (c *discoveryConn) LocalAddr() net.Addr              { return &net.UDPAddr{} }
func (c *discoveryConn) SetDeadline(time.Time) error      { return nil }
func (c *discoveryConn) SetReadDeadline(time.Time) error  { return nil }
func (c *discoveryConn) SetWriteDeadline(time.Time) error { return nil }

func TestPXEDiscoveryResponseEndpointAndEvent(t *testing.T) {
	for _, port := range []string{"67", "4011"} {
		cfg := testDHCPConfig(ModeProxy)
		conn := &discoveryConn{packet: requestOptions(3, map[byte][]byte{43: {71, 4, 0, 0, 0, 0}}, "192.168.50.40"), remote: &net.UDPAddr{IP: net.ParseIP("192.168.50.40"), Port: 4011}}
		listener := &discoveryEvents{}
		serveDHCP(context.Background(), conn, cfg, newLeasePool(cfg.DHCP), &eventSink{listener: listener}, port)
		if len(conn.written) == 0 || conn.target.String() != conn.remote.String() {
			t.Fatal("reply did not reach source endpoint")
		}
		if len(listener.events) != 1 || listener.events[0].Code != "dhcp_response" || listener.events[0].Arguments["bootDiscovery"] != "true" || listener.events[0].Arguments["response"] != "5" {
			t.Fatalf("missing discovery diagnostic: %+v", listener.events)
		}
	}
}
