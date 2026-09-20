package mobilecore

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
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
	if len(packet) < 4 || len(packet) > 512 || packet[len(packet)-1] != 0 {
		return tftpRequest{}, errors.New("packet is too short")
	}
	opcode := binary.BigEndian.Uint16(packet[:2])
	if opcode != tftpRRQ && opcode != tftpWRQ {
		return tftpRequest{}, errors.New("unsupported opcode")
	}
	parts := strings.Split(string(packet[2:]), "\x00")
	if len(parts) < 3 || len(parts)%2 != 1 || parts[0] == "" || !strings.EqualFold(parts[1], "octet") {
		return tftpRequest{}, errors.New("invalid request fields")
	}
	options := make(map[string]string)
	for index := 2; index+1 < len(parts); index += 2 {
		key := strings.ToLower(parts[index])
		if _, duplicate := options[key]; key == "" || parts[index+1] == "" || duplicate {
			return tftpRequest{}, errors.New("invalid duplicate option")
		}
		options[key] = parts[index+1]
	}
	return tftpRequest{opcode: opcode, name: parts[0], options: options}, nil
}

// tftpFailure preserves the cause without exposing private filesystem paths in events.
type tftpFailure struct {
	reason      string
	cause       error
	peerCode    uint16
	peerMessage string
}

func (e *tftpFailure) Error() string { return fmt.Sprintf("TFTP %s: %v", e.reason, e.cause) }
func (e *tftpFailure) Unwrap() error { return e.cause }

type tftpProgress struct {
	phase           string
	block           uint16
	acknowledged    int64
	retransmissions int
}

func serveTFTPFile(ctx context.Context, cfg config, sink *eventSink, client net.Addr, request tftpRequest) {
	started := time.Now()
	progress := tftpProgress{phase: "open"}
	requested, _ := json.Marshal(request.options)
	arguments := map[string]string{
		"client": client.String(), "path": request.name, "requestedOptions": string(requested),
	}
	err := transferTFTPFile(ctx, cfg, sink, client, request, arguments, &progress)
	if err != nil && ctx.Err() != nil {
		err = ctx.Err()
	}
	arguments["phase"] = progress.phase
	arguments["block"] = strconv.Itoa(int(progress.block))
	arguments["acknowledgedBytes"] = strconv.FormatInt(progress.acknowledged, 10)
	arguments["retransmissions"] = strconv.Itoa(progress.retransmissions)
	arguments["duration"] = strconv.FormatInt(time.Since(started).Milliseconds(), 10)
	level, code := "info", "file_served"
	if err != nil {
		level, code = "warning", "transfer_failed"
		var failure *tftpFailure
		switch {
		case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
			level, code = "info", "transfer_cancelled"
			arguments["reason"] = "cancelled"
		case errors.As(err, &failure):
			arguments["reason"] = failure.reason
			switch failure.reason {
			case "ack_timeout":
				code = "transfer_timeout"
			case "file_unavailable":
				code = "file_unavailable"
			case "socket_open", "socket_read", "socket_write":
				arguments["error"] = failure.cause.Error()
			case "peer_error":
				code = "transfer_peer_stopped"
				arguments["peerCode"] = strconv.Itoa(int(failure.peerCode))
				arguments["peerMessage"] = failure.peerMessage
				// UEFI GetInfo may stop a tsize query with ERROR; this does not prove a download failure.
				if progress.phase == "oack" && request.options["tsize"] == "0" {
					level = "info"
				}
			}
		}
	}
	sink.emit(level, "tftp", code, arguments)
}

func transferTFTPFile(ctx context.Context, cfg config, sink *eventSink, client net.Addr, request tftpRequest, arguments map[string]string, progress *tftpProgress) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	conn, err := net.ListenPacket("udp4", net.JoinHostPort(cfg.ListenIP, "0"))
	if err != nil {
		return &tftpFailure{reason: "socket_open", cause: err}
	}
	defer conn.Close()
	stopCancel := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stopCancel()
	arguments["server"] = conn.LocalAddr().String()
	blockSize := negotiatedBlockSize(request.options["blksize"])
	if blockSize == 0 || (request.options["tsize"] != "" && request.options["tsize"] != "0") {
		sendTFTPError(conn, client, 8, "invalid option value")
		return &tftpFailure{reason: "invalid_options"}
	}
	arguments["blockSize"] = strconv.Itoa(blockSize)
	arguments["windowSize"] = "1"
	var reader io.ReadCloser
	var size int64
	if isScriptName(request.name) {
		reader = io.NopCloser(strings.NewReader(cfg.IPXEScript))
		size = int64(len(cfg.IPXEScript))
	} else {
		path, err := safeReadPath(cfg.Root, request.name)
		if err != nil {
			sendTFTPError(conn, client, 1, "file not found")
			return &tftpFailure{reason: "file_unavailable", cause: err}
		}
		file, err := os.Open(path)
		if err != nil {
			sendTFTPError(conn, client, 1, "file not found")
			return &tftpFailure{reason: "file_unavailable", cause: err}
		}
		info, err := file.Stat()
		if err != nil {
			_ = file.Close()
			sendTFTPError(conn, client, 0, "cannot read file")
			return &tftpFailure{reason: "file_unavailable", cause: err}
		}
		reader, size = file, info.Size()
	}
	defer reader.Close()
	arguments["bytes"] = strconv.FormatInt(size, 10)
	sink.emit("info", "tftp", "transfer_started", arguments)
	options := make(map[string]string)
	if _, ok := request.options["blksize"]; ok {
		options["blksize"] = strconv.Itoa(blockSize)
	}
	if _, ok := request.options["tsize"]; ok {
		options["tsize"] = strconv.FormatInt(size, 10)
	}
	if len(options) > 0 {
		progress.phase = "oack"
		retries, err := sendTFTPPacketWithAck(ctx, conn, client, buildOACK(options), 0)
		progress.retransmissions += retries
		if err != nil {
			return err
		}
	}
	return sendTFTPData(ctx, conn, client, reader, blockSize, progress)
}

func sendTFTPData(ctx context.Context, conn net.PacketConn, client net.Addr, reader io.Reader, blockSize int, progress *tftpProgress) error {
	buffer := make([]byte, blockSize+4)
	for progress.block = 1; ; progress.block++ {
		progress.phase = "read"
		if err := ctx.Err(); err != nil {
			return err
		}
		n, readErr := io.ReadFull(reader, buffer[4:])
		if readErr != nil && readErr != io.ErrUnexpectedEOF && readErr != io.EOF {
			sendTFTPError(conn, client, 0, "cannot read file")
			return &tftpFailure{reason: "file_read", cause: readErr}
		}
		progress.phase = "data"
		binary.BigEndian.PutUint16(buffer[0:2], tftpData)
		binary.BigEndian.PutUint16(buffer[2:4], progress.block)
		retries, err := sendTFTPPacketWithAck(ctx, conn, client, buffer[:4+n], progress.block)
		progress.retransmissions += retries
		if err != nil {
			return err
		}
		progress.acknowledged += int64(n)
		if n < blockSize {
			return nil
		}
	}
}

func negotiatedBlockSize(raw string) int {
	if raw == "" {
		return 512
	}
	for _, digit := range raw {
		if digit < '0' || digit > '9' {
			return 0
		}
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 8 || value > 65464 {
		return 0
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

func sendTFTPPacketWithAck(ctx context.Context, conn net.PacketConn, client net.Addr, packet []byte, expected uint16) (retries int, result error) {
	ack := make([]byte, 516)
	// RFC 1123 section 4.2.3.2 requires at least exponential timeout backoff.
	// Four bounded attempts wait 2, 4, 8 and 16 seconds; a new exchange starts at 2s.
	timeout := 2 * time.Second
	for attempt := 0; attempt < 4; attempt++ {
		if err := ctx.Err(); err != nil {
			return retries, err
		}
		if err := conn.SetWriteDeadline(time.Now().Add(2 * time.Second)); err != nil {
			return retries, &tftpFailure{reason: "socket_write", cause: err}
		}
		if _, err := conn.WriteTo(packet, client); err != nil {
			if ctx.Err() != nil {
				return retries, ctx.Err()
			}
			return retries, &tftpFailure{reason: "socket_write", cause: err}
		}
		retries = attempt
		if err := conn.SetReadDeadline(time.Now().Add(timeout)); err != nil {
			return retries, &tftpFailure{reason: "socket_read", cause: err}
		}
		for {
			if err := ctx.Err(); err != nil {
				return retries, err
			}
			n, source, err := conn.ReadFrom(ack)
			if err != nil {
				if ctx.Err() != nil {
					return retries, ctx.Err()
				}
				var networkError net.Error
				if errors.As(err, &networkError) && networkError.Timeout() {
					break
				}
				return retries, &tftpFailure{reason: "socket_read", cause: err}
			}
			if source.String() != client.String() {
				sendTFTPError(conn, source, 5, "unknown transfer ID")
				continue
			}
			if n >= 5 && binary.BigEndian.Uint16(ack[:2]) == tftpError && ack[n-1] == 0 {
				return retries, &tftpFailure{reason: "peer_error", peerCode: binary.BigEndian.Uint16(ack[2:4]), peerMessage: string(ack[4 : n-1])}
			}
			if n == 4 && binary.BigEndian.Uint16(ack[:2]) == tftpAck && binary.BigEndian.Uint16(ack[2:4]) == expected {
				return retries, nil
			}
			// Duplicate ACKs neither trigger retransmission nor extend the deadline (RFC 1123).
		}
		timeout *= 2
	}
	return retries, &tftpFailure{reason: "ack_timeout", cause: os.ErrDeadlineExceeded}
}

func sendTFTPError(conn net.PacketConn, client net.Addr, code uint16, message string) {
	_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	packet := make([]byte, 4, 5+len(message))
	binary.BigEndian.PutUint16(packet[0:2], tftpError)
	binary.BigEndian.PutUint16(packet[2:4], code)
	packet = append(packet, []byte(message)...)
	packet = append(packet, 0)
	_, _ = conn.WriteTo(packet, client)
}
