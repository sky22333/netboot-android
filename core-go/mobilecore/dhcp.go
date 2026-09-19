package mobilecore

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const dhcpCookie = "\x63\x82\x53\x63"

type leasePool struct {
	mu       sync.Mutex
	start    uint32
	end      uint32
	ttl      time.Duration
	byClient map[string]lease
	byIP     map[uint32]string
	declined map[uint32]time.Time
}

type lease struct {
	ip      uint32
	expires time.Time
	bound   bool
}

func newLeasePool(cfg dhcpConfig) *leasePool {
	start := net.ParseIP(cfg.PoolStart).To4()
	end := net.ParseIP(cfg.PoolEnd).To4()
	ttl := time.Duration(cfg.LeaseSeconds) * time.Second
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	pool := &leasePool{ttl: ttl, byClient: make(map[string]lease), byIP: make(map[uint32]string), declined: make(map[uint32]time.Time)}
	if start != nil && end != nil {
		pool.start = ipToUint(start)
		pool.end = ipToUint(end)
	}
	return pool
}

func (p *leasePool) expire(now time.Time) {
	for owner, item := range p.byClient {
		if !item.expires.After(now) {
			delete(p.byIP, item.ip)
			delete(p.byClient, owner)
		}
	}
	for ip, until := range p.declined {
		if !until.After(now) {
			delete(p.declined, ip)
		}
	}
}

func (p *leasePool) offer(client string, requested net.IP) net.IP {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now()
	p.expire(now)
	if item, ok := p.byClient[client]; ok {
		return uintToIP(item.ip)
	}
	if value := requested.To4(); value != nil {
		candidate := ipToUint(value)
		if candidate >= p.start && candidate <= p.end && p.byIP[candidate] == "" && p.declined[candidate].IsZero() {
			p.remember(client, candidate, now, false)
			return uintToIP(candidate)
		}
	}
	for candidate := p.start; candidate <= p.end && p.start != 0; candidate++ {
		if p.byIP[candidate] == "" && p.declined[candidate].IsZero() {
			p.remember(client, candidate, now, false)
			return uintToIP(candidate)
		}
		if candidate == ^uint32(0) {
			break
		}
	}
	return nil
}

func (p *leasePool) remember(client string, ip uint32, now time.Time, confirm bool) {
	ttl := time.Minute
	if confirm {
		ttl = p.ttl
	}
	p.byClient[client] = lease{ip: ip, expires: now.Add(ttl), bound: confirm}
	p.byIP[ip] = client
}

// A REQUEST can confirm only this client's exact binding/offer, never allocate a substitute.
// Unknown INIT-REBOOT/renewal clients are left to discover a server (RFC 2131 4.3.2).
func (p *leasePool) confirm(client string, requested net.IP, selecting bool) byte {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.expire(time.Now())
	item, known := p.byClient[client]
	if !known {
		if selecting {
			return 6
		}
		return 0
	}
	if requested == nil || item.ip != ipToUint(requested) {
		return 6
	}
	p.remember(client, item.ip, time.Now(), true)
	return 5
}

func (p *leasePool) release(client string, address net.IP, decline bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if item, ok := p.byClient[client]; ok && address != nil && item.ip == ipToUint(address) {
		delete(p.byIP, item.ip)
		delete(p.byClient, client)
		if decline {
			p.declined[item.ip] = time.Now().Add(10 * time.Minute)
		}
	}
}

func (p *leasePool) withdrawOffer(client string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if item, ok := p.byClient[client]; ok && !item.bound {
		delete(p.byIP, item.ip)
		delete(p.byClient, client)
	}
}

func serveDHCP(ctx context.Context, conn net.PacketConn, cfg config, pool *leasePool, sink *eventSink, port string) {
	buf := make([]byte, 1500)
	for {
		n, remote, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return
			}
			sink.emit("error", "dhcp", "dhcp_receive_failed", map[string]string{"port": port, "error": err.Error()})
			return
		}
		request := append([]byte(nil), buf[:n]...)
		response, target := buildDHCPResponse(request, cfg, pool, port)
		if len(response) == 0 {
			continue
		}
		if port == "4011" || target == nil {
			target = remote
		}
		_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		if _, err = conn.WriteTo(response, target); err == nil {
			options := parseDHCPOptions(request[240:])
			architecture := clientArchitecture(options)
			bootFile := bootFileFor(options, cfg)
			sink.emit("info", "dhcp", "dhcp_response", map[string]string{
				"architecture": architecture,
				"bootFile":     bootFile,
				"client":       macString(request),
				"message":      strconv.Itoa(int(first(options[53]))),
				"port":         port,
				"remote":       remote.String(),
			})
			if bootFile == "" {
				sink.emit("warning", "dhcp", "boot_file_unsupported", map[string]string{
					"architecture": architecture, "client": macString(request),
				})
			}
		} else {
			sink.emit("warning", "dhcp", "dhcp_response_failed", map[string]string{
				"client": macString(request), "port": port,
			})
		}
	}
}

func clientArchitecture(options map[byte][]byte) string {
	if len(options[93]) < 2 {
		return "unknown"
	}
	switch binary.BigEndian.Uint16(options[93][:2]) {
	case 0:
		return "bios"
	case 6:
		return "efi32"
	case 7, 9:
		return "efi64"
	case 11:
		return "arm64"
	default:
		return "unknown"
	}
}

func buildDHCPResponse(request []byte, cfg config, pool *leasePool, port string) ([]byte, net.Addr) {
	if len(request) < 240 || request[0] != 1 || string(request[236:240]) != dhcpCookie {
		return nil, nil
	}
	// This service owns only the selected local LAN, not pools behind DHCP relays.
	if !net.IP(request[24:28]).Equal(net.IPv4zero) {
		return nil, nil
	}
	options := parseDHCPOptions(request[240:])
	message := first(options[53])
	if message == 0 {
		return nil, nil
	}
	mac := macString(request)
	if mac == "" {
		return nil, nil
	}
	isPXE := strings.Contains(strings.ToUpper(string(options[60])), "PXECLIENT") || len(options[93]) >= 2
	if cfg.Mode == ModeProxy && !isPXE {
		return nil, nil
	}
	// The LAN router owns address assignment and renewal; proxy replies must not interfere.
	if cfg.Mode == ModeProxy && ((port == "67" && message != 1) || (port == "4011" && message != 3 && message != 8)) {
		return nil, nil
	}
	serverIP := net.ParseIP(cfg.AdvertiseIP).To4()
	if serverIP == nil {
		return nil, nil
	}
	if len(options[53]) != 1 || (options[54] != nil && len(options[54]) != 4) || (options[50] != nil && len(options[50]) != 4) {
		return nil, nil
	}
	client := string([]byte{request[1]}) + mac
	if len(options[61]) >= 2 {
		client = string(options[61])
	}
	selected := net.IP(options[54]).To4()
	ciaddr := net.IP(request[12:16]).To4()
	requested := net.IP(options[50]).To4()
	responseType := byte(2)
	if message == 3 || message == 8 {
		responseType = 5
	}
	yiaddr := net.IPv4zero.To4()
	if cfg.Mode == ModeDHCP {
		if selected != nil && !selected.Equal(serverIP) {
			if message == 3 {
				pool.withdrawOffer(client)
			}
			return nil, nil
		}
		switch message {
		case 1:
			yiaddr = pool.offer(client, requested)
			if yiaddr == nil {
				return nil, nil
			} // Exhaustion is not a reason to NAK a DISCOVER.
		case 3:
			if selected != nil && (requested == nil || !ciaddr.Equal(net.IPv4zero)) {
				return nil, nil
			}
			if requested != nil && !ciaddr.Equal(net.IPv4zero) {
				return nil, nil
			}
			if requested == nil {
				requested = ciaddr
			}
			if requested.Equal(net.IPv4zero) {
				return nil, nil
			}
			mask := net.IPMask(net.ParseIP(cfg.DHCP.SubnetMask).To4())
			if !requested.Mask(mask).Equal(serverIP.Mask(mask)) {
				responseType = 6
			} else {
				responseType = pool.confirm(client, requested, selected != nil)
			}
			if responseType == 0 {
				return nil, nil
			}
			if responseType == 6 {
				return buildDHCPNAK(request, serverIP, options[61]), &net.UDPAddr{IP: net.IPv4bcast, Port: 68}
			}
			yiaddr = requested
		case 8:
			if ciaddr.Equal(net.IPv4zero) {
				return nil, nil
			}
		case 4, 7:
			if selected == nil {
				return nil, nil
			}
			if message == 7 {
				requested = ciaddr
			}
			pool.release(client, requested, message == 4)
			return nil, nil
		default:
			return nil, nil
		}
	}
	response := make([]byte, 240)
	copy(response[:8], request[:8])
	copy(response[10:16], request[10:16])
	copy(response[28:44], request[28:44])
	response[0] = 2
	response[3] = 0
	copy(response[16:20], yiaddr)
	copy(response[20:24], serverIP)
	copy(response[236:240], []byte(dhcpCookie))
	bootFile := bootFileFor(options, cfg)
	copy(response[108:236], []byte(bootFile))
	response = appendOption(response, 53, []byte{responseType})
	response = appendOption(response, 54, serverIP)
	response = appendOption(response, 61, options[61]) // RFC 6842: echo the client identifier.
	response = appendOption(response, 66, []byte(cfg.AdvertiseIP))
	response = appendOption(response, 67, []byte(bootFile))
	if cfg.Mode == ModeDHCP {
		response = appendOption(response, 1, net.ParseIP(cfg.DHCP.SubnetMask).To4())
		response = appendOption(response, 3, net.ParseIP(cfg.DHCP.Router).To4())
		response = appendOption(response, 6, net.ParseIP(cfg.DHCP.DNS).To4())
		if message != 8 {
			leaseBytes := make([]byte, 4)
			binary.BigEndian.PutUint32(leaseBytes, uint32(cfg.DHCP.LeaseSeconds))
			response = appendOption(response, 51, leaseBytes)
		}
	} else {
		response = appendOption(response, 60, []byte("PXEClient"))
	}
	response = append(response, 255)
	target := &net.UDPAddr{IP: net.IPv4bcast, Port: 68}
	if !ciaddr.Equal(net.IPv4zero) {
		target.IP = ciaddr
	}
	// Before address configuration, UDP cannot deliver to chaddr without altering ARP state.
	// RFC 2131 4.1 permits broadcast when pre-configuration unicast is not possible.
	if port == "4011" {
		return response, nil // Reply to the request's actual UDP source endpoint.
	}
	return response, target
}

func buildDHCPNAK(request []byte, serverIP net.IP, clientID []byte) []byte {
	response := make([]byte, 240)
	copy(response[:8], request[:8])
	copy(response[10:12], request[10:12])
	copy(response[28:44], request[28:44])
	response[0] = 2
	response[3] = 0
	copy(response[236:240], []byte(dhcpCookie))
	response = appendOption(response, 53, []byte{6})
	response = appendOption(response, 54, serverIP.To4())
	response = appendOption(response, 61, clientID)
	return append(response, 255)
}

func parseDHCPOptions(raw []byte) map[byte][]byte {
	options := make(map[byte][]byte)
	for i := 0; i < len(raw); {
		code := raw[i]
		i++
		if code == 0 {
			continue
		}
		if code == 255 {
			return options
		}
		if i >= len(raw) {
			return nil
		}
		length := int(raw[i])
		i++
		if i+length > len(raw) {
			return nil
		}
		options[code] = append(options[code], raw[i:i+length]...)
		i += length
	}
	return nil
}

func appendOption(packet []byte, code byte, value []byte) []byte {
	if len(value) == 0 || len(value) > 255 {
		return packet
	}
	packet = append(packet, code, byte(len(value)))
	return append(packet, value...)
}

// bootFileFor resolves the first-stage boot file: an explicit cfg.BootFile wins, otherwise it
// follows the client architecture. An empty result means no image is bundled for that client.
func bootFileFor(options map[byte][]byte, cfg config) string {
	if len(options[175]) > 0 || strings.Contains(strings.ToLower(string(options[77])), "ipxe") {
		return "http://" + net.JoinHostPort(cfg.AdvertiseIP, strconv.Itoa(cfg.HTTPPort)) + "/boot.ipxe"
	}
	if cfg.BootFile != "" {
		return cfg.BootFile
	}
	return bundledBootFile(clientArchitecture(options))
}

// bundledBootFile maps a client architecture to the image bundled for it; empty means none.
func bundledBootFile(architecture string) string {
	switch architecture {
	case "bios":
		return "undionly.kpxe"
	case "efi64":
		return "ipxe-x86_64.efi"
	case "arm64":
		return "ipxe-arm64.efi"
	default:
		return ""
	}
}

func macString(packet []byte) string {
	if len(packet) < 44 {
		return ""
	}
	length := int(packet[2])
	if length < 1 || length > 16 || 28+length > len(packet) {
		return ""
	}
	parts := make([]string, length)
	for i, value := range packet[28 : 28+length] {
		const hex = "0123456789ABCDEF"
		parts[i] = string([]byte{hex[value>>4], hex[value&15]})
	}
	return strings.Join(parts, ":")
}

func first(value []byte) byte {
	if len(value) == 0 {
		return 0
	}
	return value[0]
}

func ipToUint(ip net.IP) uint32 { return binary.BigEndian.Uint32(ip.To4()) }

func uintToIP(value uint32) net.IP {
	buffer := make([]byte, 4)
	binary.BigEndian.PutUint32(buffer, value)
	return net.IP(buffer)
}
