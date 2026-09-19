package mobilecore

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func TestTFTPOptionTransferAndEmptyFinalBlock(t *testing.T) {
	client, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		serveTFTPFile(ctx, config{ListenIP: "127.0.0.1", IPXEScript: "#!ipxe\nX"}, &eventSink{}, client.LocalAddr(), tftpRequest{name: "boot.ipxe", options: map[string]string{"blksize": "8", "tsize": "0"}})
	}()
	read := func() ([]byte, net.Addr) {
		t.Helper()
		_ = client.SetReadDeadline(time.Now().Add(4 * time.Second))
		b := make([]byte, 1500)
		n, a, e := client.ReadFrom(b)
		if e != nil {
			t.Fatal(e)
		}
		return b[:n], a
	}
	oack, server := read()
	if !bytes.Contains(oack, []byte("blksize\x008\x00")) {
		t.Fatalf("bad OACK %q", oack)
	}
	// Drop ACK0 once: the exact OACK is retransmitted from the same transfer socket.
	retry, source := read()
	if !bytes.Equal(oack, retry) || source.String() != server.String() {
		t.Fatal("bad retransmission")
	}
	_, _ = client.WriteTo([]byte{0, 4, 0, 0}, server)
	packet, _ := read()
	if len(packet) != 12 || binary.BigEndian.Uint16(packet[2:4]) != 1 {
		t.Fatalf("bad block %v", packet)
	}
	_, _ = client.WriteTo([]byte{0, 4, 0, 1}, server)
	packet, _ = read()
	if !bytes.Equal(packet, []byte{0, 3, 0, 2}) {
		t.Fatalf("missing zero-byte final block %v", packet)
	}
	_, _ = client.WriteTo([]byte{0, 4, 0, 1}, server) // A duplicate ACK must not advance the transfer.
	_, _ = client.WriteTo([]byte{0, 4, 0, 2}, server)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("transfer did not finish")
	}
}

func TestTFTPWrongTIDAndPeerError(t *testing.T) {
	client, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer client.Close()
	stranger, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer stranger.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		serveTFTPFile(ctx, config{ListenIP: "127.0.0.1", IPXEScript: "#!ipxe"}, &eventSink{}, client.LocalAddr(), tftpRequest{name: "boot.ipxe"})
	}()
	b := make([]byte, 512)
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	_, server, err := client.ReadFrom(b)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = stranger.WriteTo([]byte{0, 4, 0, 1}, server)
	_ = stranger.SetReadDeadline(time.Now().Add(time.Second))
	n, _, err := stranger.ReadFrom(b)
	if err != nil || n < 4 || binary.BigEndian.Uint16(b[2:4]) != 5 {
		t.Fatal("missing unknown TID error")
	}
	_, _ = client.WriteTo([]byte{0, 5, 0, 0, 0}, server)
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("peer ERROR did not terminate transfer")
	}
}

func TestTFTPCancelInterruptsWaitingForAck(t *testing.T) {
	client, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		serveTFTPFile(ctx, config{ListenIP: "127.0.0.1", IPXEScript: "#!ipxe"}, &eventSink{}, client.LocalAddr(), tftpRequest{name: "boot.ipxe"})
	}()
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 512)
	if _, _, err := client.ReadFrom(b); err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("cancel did not release transfer")
	}
}

func TestTFTPConcurrentTransferLimit(t *testing.T) {
	listener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	client, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		serveTFTP(ctx, listener, config{ListenIP: "127.0.0.1", IPXEScript: "#!ipxe", MaxTransfers: 1}, &eventSink{})
	}()
	request := append([]byte{0, 1}, []byte("boot.ipxe\x00octet\x00")...)
	_, _ = client.WriteTo(request, listener.LocalAddr())
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	packet := make([]byte, 512)
	n, _, err := client.ReadFrom(packet)
	if err != nil || n < 4 || packet[1] != 3 {
		t.Fatalf("first transfer: %v", err)
	}
	// Keep the first transfer waiting for ACK while requesting a second one.
	_, _ = client.WriteTo(request, listener.LocalAddr())
	n, _, err = client.ReadFrom(packet)
	if err != nil || n < 4 || packet[1] != 5 || !bytes.Contains(packet[:n], []byte("server busy")) {
		t.Fatalf("limit: %v %q", err, packet[:n])
	}
	cancel()
	_ = listener.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("active transfer leaked")
	}
}
