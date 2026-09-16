//go:build windows

package mobilecore

import "net"

func listenDHCP(address string) (net.PacketConn, error) {
	return net.ListenPacket("udp4", address)
}
