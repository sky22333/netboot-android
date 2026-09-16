package mobilecore

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"net"
	"time"
)

func detectDHCPServer(listenIP string, timeout time.Duration) (bool, error) {
	connection, err := listenDHCP(net.JoinHostPort(listenIP, "68"))
	if err != nil {
		return false, err
	}
	defer connection.Close()
	packet, transactionID, err := dhcpDiscoverPacket()
	if err != nil {
		return false, err
	}
	if err := connection.SetDeadline(time.Now().Add(timeout)); err != nil {
		return false, err
	}
	if _, err := connection.WriteTo(packet, &net.UDPAddr{IP: net.IPv4bcast, Port: 67}); err != nil {
		return false, err
	}
	buffer := make([]byte, 1500)
	for {
		read, _, readErr := connection.ReadFrom(buffer)
		if readErr != nil {
			var networkError net.Error
			if errors.As(readErr, &networkError) && networkError.Timeout() {
				return false, nil
			}
			return false, readErr
		}
		if read >= 240 && buffer[0] == 2 && binary.BigEndian.Uint32(buffer[4:8]) == transactionID {
			return true, nil
		}
	}
}

func dhcpDiscoverPacket() ([]byte, uint32, error) {
	packet := make([]byte, 244)
	packet[0] = 1
	packet[1] = 1
	packet[2] = 6
	packet[10] = 0x80
	packet[11] = 0x00
	if _, err := rand.Read(packet[4:8]); err != nil {
		return nil, 0, err
	}
	if _, err := rand.Read(packet[28:34]); err != nil {
		return nil, 0, err
	}
	copy(packet[236:240], []byte{99, 130, 83, 99})
	copy(packet[240:], []byte{53, 1, 1, 255})
	return packet, binary.BigEndian.Uint32(packet[4:8]), nil
}
