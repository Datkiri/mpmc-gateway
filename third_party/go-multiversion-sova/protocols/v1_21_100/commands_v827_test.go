package v1_21_100

import (
	"bytes"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
)

// Regression: BDS sends these command parameter kinds (captured from live
// AvailableCommands packets). Each maps to a named constant in
// gophertunnel-2225/minecraft/protocol/command.go.
func TestCommandArgumentFrom827CapturedKinds(t *testing.T) {
	tests := []struct {
		name string
		wire uint32
		want uint32
	}{
		{"int", 0x100001, 0x100000 | protocol.CommandArgTypeInt},
		{"blockStates (captured kind 84)", 0x100054, 0x100000 | protocol.CommandArgTypeBlockStates},
		{"messageRoot (captured kind 68)", 0x100044, 0x100000 | protocol.CommandArgTypeMessageRoot},
		{"command (captured kind 87)", 0x100057, 0x100000 | protocol.CommandArgTypeCommand},
		{"chainedCommand flag", 0x8100000, 0x8100000},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := protocol.NewReader(bytes.NewReader(nil), 0, false)
			io := newWireIO(r, true)
			got := commandArgumentFrom827(io, tc.wire)
			if got != tc.want {
				t.Fatalf("wire %#x: got %#x, want %#x", tc.wire, got, tc.want)
			}
		})
	}
}
