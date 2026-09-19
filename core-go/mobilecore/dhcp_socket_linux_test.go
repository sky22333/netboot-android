//go:build linux

package mobilecore

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func TestDHCPServerCanSendBroadcast(t *testing.T) {
	receiver, err := listenDHCP("0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	defer receiver.Close()
	sender, err := listenDHCP("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	payload := []byte("broadcast-offer")
	target := &net.UDPAddr{IP: net.IPv4(127, 255, 255, 255), Port: receiver.LocalAddr().(*net.UDPAddr).Port}
	if _, err := sender.WriteTo(payload, target); err != nil {
		t.Fatal(err)
	}
	_ = receiver.SetReadDeadline(time.Now().Add(time.Second))
	buffer := make([]byte, 100)
	n, _, err := receiver.ReadFrom(buffer)
	if err != nil || !bytes.Equal(buffer[:n], payload) {
		t.Fatalf("broadcast: %q %v", buffer[:n], err)
	}
}

func TestDHCPServerPortRemainsExclusive(t *testing.T) {
	server, err := listenDHCP("0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	second, err := listenDHCP(server.LocalAddr().String())
	if err == nil {
		second.Close()
		t.Fatal("unexpectedly shared a server socket")
	}
}
