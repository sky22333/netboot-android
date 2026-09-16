package mobilecore

import (
	"encoding/binary"
	"testing"
)

func TestParseTFTPRequest(t *testing.T) {
	packet := []byte{0, tftpRRQ}
	packet = append(packet, []byte("boot.ipxe\x00octet\x00blksize\x001024\x00tsize\x000\x00")...)
	request, err := parseTFTPRequest(packet)
	if err != nil {
		t.Fatal(err)
	}
	if request.name != "boot.ipxe" || request.options["blksize"] != "1024" {
		t.Fatalf("unexpected request: %+v", request)
	}
}

func TestTFTPRejectsWriteAndInvalidMode(t *testing.T) {
	packet := []byte{0, tftpWRQ}
	packet = append(packet, []byte("file\x00netascii\x00")...)
	if _, err := parseTFTPRequest(packet); err == nil {
		t.Fatal("expected invalid mode error")
	}
}

func TestBuildOACK(t *testing.T) {
	packet := buildOACK(map[string]string{"blksize": "1428", "tsize": "42"})
	if binary.BigEndian.Uint16(packet[:2]) != tftpOACK {
		t.Fatalf("unexpected opcode %v", packet[:2])
	}
}

func TestBlockSizeBounds(t *testing.T) {
	if negotiatedBlockSize("64") != 512 || negotiatedBlockSize("9000") != 1428 || negotiatedBlockSize("1024") != 1024 {
		t.Fatal("block size was not bounded")
	}
}
