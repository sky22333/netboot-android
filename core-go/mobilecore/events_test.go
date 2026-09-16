package mobilecore

import (
	"strings"
	"testing"
)

func TestCleanEventArguments(t *testing.T) {
	cleaned := cleanEventArguments(map[string]string{
		"message": "first\r\nsecond\x00third",
		"long":    strings.Repeat("x", 600),
	})

	if got := cleaned["message"]; got != "first  second third" {
		t.Fatalf("unexpected cleaned message: %q", got)
	}
	if got := len(cleaned["long"]); got != 512 {
		t.Fatalf("unexpected truncated length: %d", got)
	}
	if got := cleanEventArguments(nil); got != nil {
		t.Fatalf("nil arguments should remain nil: %#v", got)
	}
}
