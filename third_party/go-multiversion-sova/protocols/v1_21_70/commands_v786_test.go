package v1_21_70

import (
	"bytes"
	"encoding/hex"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

// Regression: BDS 1.21.70 sends command parameter kinds that the old hard-coded
// map did not know. Each value below was captured from a live BDS 1.21.70
// AvailableCommands packet (see enum-fixer report), and maps to a constant in
// gophertunnel-2225/minecraft/protocol/command.go.
func TestCommandArgumentFrom786CapturedKinds(t *testing.T) {
	tests := []struct {
		name string
		wire uint32
		want uint32
	}{
		{"int", 0x100001, 0x100000 | protocol.CommandArgTypeInt},
		{"blockStates (captured kind 84)", 0x100054, 0x100000 | protocol.CommandArgTypeBlockStates},
		{"messageRoot (captured kind 68)", 0x100044, 0x100000 | protocol.CommandArgTypeMessageRoot},
		{"command (captured kind 87)", 0x100057, 0x100000 | protocol.CommandArgTypeCommand},
		// chainedCommand parameter: 0x8000000 is CommandArgTypeChainedCommand
		// (command.go:171), a flag that must pass through untouched, not kind 0.
		{"chainedCommand flag", 0x8100000, 0x8100000},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := protocol.NewReader(bytes.NewReader(nil), 0, false)
			io := newWireIO(r, true)
			got := commandArgumentFrom786(io, tc.wire)
			if got != tc.want {
				t.Fatalf("wire %#x: got %#x, want %#x", tc.wire, got, tc.want)
			}
		})
	}
}

// Regression: BDS 1.21.70 sends CameraAimAssistPresets (packet ID 320) in the
// flat layout, not the older grouped layout used by protocol 776. The grouped
// layout read a "group identifier" string before the category list; the flat
// layout reads categories directly and ends with an Operation byte. A raw
// 1071-byte BDS capture parses to exactly 1071 bytes with the flat layout.
func TestCameraAimAssistPresets786FlatLayout(t *testing.T) {
	raw, err := hex.DecodeString("010764656661756c7400000000010170000000000001")
	if err != nil {
		t.Fatal(err)
	}
	r := protocol.NewReader(bytes.NewReader(raw), 0, false)
	pk := &packet.CameraAimAssistPresets{}
	marshalCameraAimAssistPresets(newWireIO(r, true), pk)
	if len(pk.Categories) != 1 || pk.Categories[0].Name != "default" {
		t.Fatalf("categories: %+v", pk.Categories)
	}
	if len(pk.Presets) != 1 || pk.Presets[0].Identifier != "p" {
		t.Fatalf("presets: %+v", pk.Presets)
	}
	if pk.Operation != 1 {
		t.Fatalf("operation: got %d, want 1", pk.Operation)
	}

	var out bytes.Buffer
	w := protocol.NewWriter(&out, 0)
	marshalCameraAimAssistPresets(newWireIO(w, false), pk)
	if !bytes.Equal(out.Bytes(), raw) {
		t.Fatalf("round-trip mismatch:\n got %x\nwant %x", out.Bytes(), raw)
	}
}
