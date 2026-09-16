package mobilecore

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	tftpRRQ   = 1
	tftpWRQ   = 2
	tftpData  = 3
	tftpAck   = 4
	tftpError = 5
	tftpOACK  = 6
)

type tftpRequest struct {
	opcode  uint16
	name    string
	options map[string]string
}

func serveTFTP(ctx context.Context, conn net.PacketConn, cfg config, sink *eventSink) {
	semaphore := make(chan struct{}, cfg.MaxTransfers)
	var transfers sync.WaitGroup
	defer transfers.Wait()
	buffer := make([]byte, 2048)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(time.Second))
		n, client, err := conn.ReadFrom(buffer)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		request, err := parseTFTPRequest(buffer[:n])
		if err != nil {
			sink.emit("warning", "tftp", "request_rejected", map[string]string{"client": client.String(), "reason": "invalid_request"})
			sendTFTPError(conn, client, 4, "invalid request")
			continue
		}
		if request.opcode == tftpWRQ {
			sink.emit("warning", "tftp", "request_rejected", map[string]string{"client": client.String(), "path": request.name, "reason": "write_disabled"})
			sendTFTPError(conn, client, 2, "write requests are disabled")
			continue
		}
		select {
		case semaphore <- struct{}{}:
			transfers.Add(1)
			go func() {
				defer transfers.Done()
				defer func() { <-semaphore }()
				serveTFTPFile(ctx, cfg, sink, client, request)
			}()
		default:
			sink.emit("warning", "tftp", "request_rejected", map[string]string{"client": client.String(), "path": request.name, "reason": "server_busy"})
			sendTFTPError(conn, client, 0, "server busy")
		}
	}
}

func parseTFTPRequest(packet []byte) (tftpRequest, error) {
	if len(packet) < 4 {
		return tftpRequest{}, errors.New("packet is too short")
	}
	opcode := binary.BigEndian.Uint16(packet[:2])
	if opcode != tftpRRQ && opcode != tftpWRQ {
		return tftpRequest{}, errors.New("unsupported opcode")
	}
	parts := strings.Split(string(packet[2:]), "\x00")
	if len(parts) < 3 || parts[0] == "" || !strings.EqualFold(parts[1], "octet") {
		return tftpRequest{}, errors.New("invalid request fields")
	}
	options := make(map[string]string)
	for index := 2; index+1 < len(parts); index += 2 {
		key := strings.ToLower(parts[index])
		if key != "" {
			options[key] = parts[index+1]
		}
	}
	return tftpRequest{opcode: opcode, name: parts[0], options: options}, nil
}

func serveTFTPFile(ctx context.Context, cfg config, sink *eventSink, client net.Addr, request tftpRequest) {
	var reader io.ReadCloser
	var size int64
	if isScriptName(request.name) {
		reader = io.NopCloser(strings.NewReader(cfg.IPXEScript))
		size = int64(len(cfg.IPXEScript))
	} else {
		path, err := safeReadPath(cfg.Root, request.name)
		if err != nil {
			sink.emit("warning", "tftp", "file_unavailable", map[string]string{"client": client.String(), "path": request.name})
			if conn, openErr := net.ListenPacket("udp4", ":0"); openErr == nil {
				sendTFTPError(conn, client, 1, "file not found")
				_ = conn.Close()
			}
			return
		}
		file, err := os.Open(path)
		if err != nil {
			sink.emit("warning", "tftp", "file_unavailable", map[string]string{"client": client.String(), "path": request.name})
			return
		}
		info, err := file.Stat()
		if err != nil {
			_ = file.Close()
			sink.emit("warning", "tftp", "file_unavailable", map[string]string{"client": client.String(), "path": request.name})
			return
		}
		reader = file
		size = info.Size()
	}
	defer reader.Close()
	conn, err := net.ListenPacket("udp4", ":0")
	if err != nil {
		return
	}
	defer conn.Close()
	blockSize := negotiatedBlockSize(request.options["blksize"])
	started := time.Now()
	sink.emit("info", "tftp", "transfer_started", map[string]string{
		"bytes": strconv.FormatInt(size, 10), "client": client.String(), "path": request.name,
	})
	if len(request.options) > 0 {
		options := make(map[string]string)
		if _, ok := request.options["blksize"]; ok {
			options["blksize"] = strconv.Itoa(blockSize)
		}
		if _, ok := request.options["tsize"]; ok {
			options["tsize"] = strconv.FormatInt(size, 10)
		}
		if len(options) > 0 && !sendTFTPPacketWithAck(ctx, conn, client, buildOACK(options), 0) {
			return
		}
	}
	buffer := make([]byte, blockSize)
	block := uint16(1)
	for {
		n, readErr := io.ReadFull(reader, buffer)
		if readErr != nil && readErr != io.ErrUnexpectedEOF && readErr != io.EOF {
			return
		}
		packet := make([]byte, 4+n)
		binary.BigEndian.PutUint16(packet[0:2], tftpData)
		binary.BigEndian.PutUint16(packet[2:4], block)
		copy(packet[4:], buffer[:n])
		if !sendTFTPPacketWithAck(ctx, conn, client, packet, block) {
			sink.emit("warning", "tftp", "transfer_timeout", map[string]string{"path": request.name, "client": client.String()})
			return
		}
		if n < blockSize {
			sink.emit("info", "tftp", "file_served", map[string]string{
				"bytes": strconv.FormatInt(size, 10), "client": client.String(),
				"duration": strconv.FormatInt(time.Since(started).Milliseconds(), 10), "path": request.name,
			})
			return
		}
		block++
	}
}

func negotiatedBlockSize(raw string) int {
	value, err := strconv.Atoi(raw)
	if err != nil || value < 512 {
		return 512
	}
	if value > 1428 {
		return 1428
	}
	return value
}

func buildOACK(options map[string]string) []byte {
	packet := []byte{0, tftpOACK}
	if value, ok := options["blksize"]; ok {
		packet = append(packet, []byte("blksize")...)
		packet = append(packet, 0)
		packet = append(packet, []byte(value)...)
		packet = append(packet, 0)
	}
	if value, ok := options["tsize"]; ok {
		packet = append(packet, []byte("tsize")...)
		packet = append(packet, 0)
		packet = append(packet, []byte(value)...)
		packet = append(packet, 0)
	}
	return packet
}

func sendTFTPPacketWithAck(ctx context.Context, conn net.PacketConn, client net.Addr, packet []byte, expected uint16) bool {
	ack := make([]byte, 516)
	for attempt := 0; attempt < 4; attempt++ {
		if _, err := conn.WriteTo(packet, client); err != nil {
			return false
		}
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		for {
			if ctx.Err() != nil {
				return false
			}
			n, source, err := conn.ReadFrom(ack)
			if err != nil {
				break
			}
			if source.String() == client.String() && n >= 4 && binary.BigEndian.Uint16(ack[:2]) == tftpAck && binary.BigEndian.Uint16(ack[2:4]) == expected {
				return true
			}
		}
	}
	return false
}

func sendTFTPError(conn net.PacketConn, client net.Addr, code uint16, message string) {
	packet := make([]byte, 4, 5+len(message))
	binary.BigEndian.PutUint16(packet[0:2], tftpError)
	binary.BigEndian.PutUint16(packet[2:4], code)
	packet = append(packet, []byte(message)...)
	packet = append(packet, 0)
	_, _ = conn.WriteTo(packet, client)
}
