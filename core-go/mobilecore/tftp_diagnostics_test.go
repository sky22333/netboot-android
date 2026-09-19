package mobilecore

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"testing"
	"time"
)

// Immediate read timeouts expose every retry deadline without sleeping.
type tftpTestConn struct {
	net.PacketConn
	deadlines             []time.Duration
	writes                int
	readError, writeError error
	packets               [][]byte
	client                net.Addr
}

func (c *tftpTestConn) SetWriteDeadline(time.Time) error { return nil }
func (c *tftpTestConn) SetReadDeadline(d time.Time) error {
	c.deadlines = append(c.deadlines, time.Until(d))
	return nil
}
func (c *tftpTestConn) WriteTo(b []byte, _ net.Addr) (int, error) {
	c.writes++
	if c.writeError != nil {
		return 0, c.writeError
	}
	return len(b), nil
}
func (c *tftpTestConn) ReadFrom(b []byte) (int, net.Addr, error) {
	if len(c.packets) > 0 {
		p := c.packets[0]
		c.packets = c.packets[1:]
		return copy(b, p), c.client, nil
	}
	if c.readError != nil {
		return 0, nil, c.readError
	}
	return 0, nil, os.ErrDeadlineExceeded
}

func TestTFTPBackoffAndFailureCauses(t *testing.T) {
	client := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 12345}
	for _, opcode := range []byte{tftpOACK, tftpData} {
		c := &tftpTestConn{client: client}
		retries, err := sendTFTPPacketWithAck(context.Background(), c, client, []byte{0, opcode, 0, 1}, 1)
		var failure *tftpFailure
		if !errors.As(err, &failure) || failure.reason != "ack_timeout" || !errors.Is(err, os.ErrDeadlineExceeded) || retries != 3 || c.writes != 4 || len(c.deadlines) != 4 {
			t.Fatalf("retries=%d writes=%d error=%v", retries, c.writes, err)
		}
		for i, want := range []time.Duration{2 * time.Second, 4 * time.Second, 8 * time.Second, 16 * time.Second} {
			if got := c.deadlines[i]; got > want || got < want-100*time.Millisecond {
				t.Fatalf("interval %d=%v want %v", i, got, want)
			}
		}
	}
	broken := errors.New("injected socket failure")
	for _, reason := range []string{"socket_read", "socket_write"} {
		c := &tftpTestConn{client: client}
		if reason == "socket_read" {
			c.readError = broken
		} else {
			c.writeError = broken
		}
		retries, err := sendTFTPPacketWithAck(context.Background(), c, client, []byte{0, 3, 0, 1}, 1)
		var failure *tftpFailure
		if retries != 0 || !errors.As(err, &failure) || failure.reason != reason || !errors.Is(err, broken) || c.writes != 1 {
			t.Fatalf("%s: %v", reason, err)
		}
	}
}

func TestTFTPDuplicateAckDoesNotResendOrExtendDeadline(t *testing.T) {
	client := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 12345}
	c := &tftpTestConn{client: client, packets: [][]byte{{0, 4, 0, 0}, {0, 4, 0, 1}}}
	retries, err := sendTFTPPacketWithAck(context.Background(), c, client, []byte{0, 3, 0, 1}, 1)
	if err != nil || retries != 0 || c.writes != 1 || len(c.deadlines) != 1 {
		t.Fatalf("retries=%d writes=%d deadlines=%v error=%v", retries, c.writes, c.deadlines, err)
	}
}

type tftpBrokenReader struct{}

func (tftpBrokenReader) Read([]byte) (int, error) { return 0, io.ErrClosedPipe }
func TestTFTPReadFailurePreservesCause(t *testing.T) {
	client := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 12345}
	c := &tftpTestConn{client: client}
	progress := &tftpProgress{}
	err := sendTFTPData(context.Background(), c, client, tftpBrokenReader{}, 512, progress)
	var failure *tftpFailure
	if !errors.As(err, &failure) || failure.reason != "file_read" || !errors.Is(err, io.ErrClosedPipe) || progress.phase != "read" || progress.acknowledged != 0 {
		t.Fatalf("progress=%+v error=%v", progress, err)
	}
	if c.writes != 1 {
		t.Fatal("missing peer error")
	}
}

func TestTFTPTerminalEvents(t *testing.T) {
	for _, tc := range []struct {
		name                                      string
		options                                   map[string]string
		action, code, reason, phase, acknowledged string
	}{
		{"query_peer_stop", map[string]string{"tsize": "0"}, "error", "transfer_peer_stopped", "peer_error", "oack", "0"},
		{"download_oack_refused", map[string]string{"blksize": "8"}, "error", "transfer_peer_stopped", "peer_error", "oack", "0"},
		{"data_peer_error", nil, "error", "transfer_peer_stopped", "peer_error", "data", "0"},
		{"oack_cancelled", map[string]string{"tsize": "0"}, "cancel", "transfer_cancelled", "cancelled", "oack", "0"},
		{"data_cancelled", nil, "cancel", "transfer_cancelled", "cancelled", "data", "0"},
		{"success", nil, "ack", "file_served", "", "data", "6"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			client, err := net.ListenPacket("udp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			listener := &collectingListener{}
			done := make(chan struct{})
			go func() {
				defer close(done)
				serveTFTPFile(ctx, config{ListenIP: "127.0.0.1", IPXEScript: "#!ipxe"}, &eventSink{listener: listener}, client.LocalAddr(), tftpRequest{name: "boot.ipxe", options: tc.options})
			}()
			_ = client.SetReadDeadline(time.Now().Add(time.Second))
			packet := make([]byte, 1500)
			_, server, err := client.ReadFrom(packet)
			if err != nil {
				t.Fatal(err)
			}
			switch tc.action {
			case "error":
				_, err = client.WriteTo(append([]byte{0, 5, 0, 8}, []byte("User aborted transfer\x00")...), server)
			case "cancel":
				cancel()
			case "ack":
				_, err = client.WriteTo([]byte{0, 4, 0, 1}, server)
			}
			if err != nil {
				t.Fatal(err)
			}
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("transfer did not finish")
			}
			if len(listener.events) != 2 {
				t.Fatalf("events=%v", listener.events)
			}
			var start, end event
			if err = json.Unmarshal([]byte(listener.events[0]), &start); err != nil {
				t.Fatal(err)
			}
			if err = json.Unmarshal([]byte(listener.events[1]), &end); err != nil {
				t.Fatal(err)
			}
			if start.Code != "transfer_started" || end.Code != tc.code || end.Arguments["reason"] != tc.reason || end.Arguments["phase"] != tc.phase || end.Arguments["acknowledgedBytes"] != tc.acknowledged {
				t.Fatalf("end=%+v", end)
			}
			for _, key := range []string{"server", "client", "requestedOptions", "blockSize", "windowSize", "retransmissions", "duration", "block"} {
				if end.Arguments[key] == "" {
					t.Fatalf("missing %s", key)
				}
			}
			if tc.action == "error" && (end.Arguments["peerCode"] != "8" || end.Arguments["peerMessage"] != "User aborted transfer") {
				t.Fatalf("lost peer error: %+v", end)
			}
			if tc.name == "query_peer_stop" && end.Level != "info" {
				t.Fatalf("query stop treated as failure: %+v", end)
			}
			if start.Arguments["server"] != end.Arguments["server"] {
				t.Fatal("transfer endpoint changed")
			}
		})
	}
}

func TestTFTPOACKTimeoutHasTerminalEvent(t *testing.T) {
	t.Parallel()
	client, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	listener := &collectingListener{}
	serveTFTPFile(ctx, config{ListenIP: "127.0.0.1", IPXEScript: "#!ipxe"}, &eventSink{listener: listener}, client.LocalAddr(), tftpRequest{name: "boot.ipxe", options: map[string]string{"tsize": "0"}})
	if len(listener.events) != 2 {
		t.Fatalf("events=%v", listener.events)
	}
	var end event
	if err = json.Unmarshal([]byte(listener.events[1]), &end); err != nil {
		t.Fatal(err)
	}
	if end.Code != "transfer_timeout" || end.Arguments["reason"] != "ack_timeout" || end.Arguments["phase"] != "oack" || end.Arguments["block"] != "0" || end.Arguments["retransmissions"] != "3" {
		t.Fatalf("end=%+v", end)
	}
}
