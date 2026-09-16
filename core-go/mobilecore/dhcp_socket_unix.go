//go:build !windows

package mobilecore

import (
	"context"
	"net"
	"syscall"
)

func listenDHCP(address string) (net.PacketConn, error) {
	var controlErr error
	config := net.ListenConfig{Control: func(_, _ string, raw syscall.RawConn) error {
		if err := raw.Control(func(fd uintptr) {
			controlErr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_BROADCAST, 1)
		}); err != nil {
			return err
		}
		return controlErr
	}}
	return config.ListenPacket(context.Background(), "udp4", address)
}
