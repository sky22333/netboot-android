package mobilecore

import (
	"fmt"
	"net"

	"golang.org/x/net/ipv4"
)

type dhcpInterfaceConn struct {
	net.PacketConn
	packets *ipv4.PacketConn
	index   int
	ip      net.IP
}

// Wildcard binding receives limited broadcasts. Ancillary data restricts both
// receive and reply to the selected LAN without raw sockets or routing changes.
func listenDHCPInterface(ip, port string) (net.PacketConn, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	for _, device := range interfaces {
		addresses, err := device.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			host, _, err := net.ParseCIDR(address.String())
			if err != nil || !host.Equal(net.ParseIP(ip)) {
				continue
			}
			conn, err := listenDHCP(net.JoinHostPort("0.0.0.0", port))
			if err != nil {
				return nil, err
			}
			packets := ipv4.NewPacketConn(conn)
			if err = packets.SetControlMessage(ipv4.FlagInterface, true); err != nil {
				conn.Close()
				return nil, fmt.Errorf("enable DHCP interface metadata: %w", err)
			}
			return &dhcpInterfaceConn{conn, packets, device.Index, host.To4()}, nil
		}
	}
	return nil, fmt.Errorf("network_interface_changed: no interface for %s", ip)
}

func (c *dhcpInterfaceConn) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		n, control, remote, err := c.packets.ReadFrom(b)
		if err != nil {
			return 0, nil, err
		}
		if control != nil && control.IfIndex == c.index {
			return n, remote, nil
		}
	}
}

func (c *dhcpInterfaceConn) WriteTo(b []byte, target net.Addr) (int, error) {
	return c.packets.WriteTo(b, &ipv4.ControlMessage{IfIndex: c.index, Src: c.ip}, target)
}
