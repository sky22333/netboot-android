package mobilecore

import "testing"

func TestDHCPDiscoverPacket(t *testing.T) {
	packet, transactionID, err := dhcpDiscoverPacket()
	if err != nil {
		t.Fatal(err)
	}
	if len(packet) != 244 || packet[0] != 1 || transactionID == 0 {
		t.Fatalf("invalid discover packet: len=%d xid=%d", len(packet), transactionID)
	}
	if got := packet[240:244]; got[0] != 53 || got[2] != 1 || got[3] != 255 {
		t.Fatalf("invalid DHCP options: %v", got)
	}
}
