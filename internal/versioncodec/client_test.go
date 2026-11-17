package versioncodec

import (
	"bytes"
	"testing"

	"bedrock-cross-version-relay/internal/blocktranslate"
	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_40"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_50"
)

func TestRecordingReaderRemainingLength(t *testing.T) {
	source := bytes.NewBufferString("abcdef")
	r := &recordingReader{ByteReader: source}
	if r.Len() != 6 {
		t.Fatalf("remaining length = %d, want 6", r.Len())
	}
	_, _ = r.ReadByte()
	_, _ = r.Read(make([]byte, 2))
	if r.Len() != 3 || r.captured.String() != "abc" {
		t.Fatalf("remaining=%d captured=%q", r.Len(), r.captured.String())
	}
}

func TestLegacyClientReceivesEmbeddedRegistry(t *testing.T) {
	blocks, err := blocktranslate.NativeRegistry(blocktranslate.NativeProtocol)
	if err != nil {
		t.Fatal(err)
	}
	native, err := blocktranslate.NativeItems()
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []int32{748, 766} {
		var server, base minecraft.Protocol
		if id == 748 {
			server, err = v1_21_40.NewWithRegistries(blocks, native)
			base = v1_21_40.New()
		} else {
			server, err = v1_21_50.NewWithRegistries(blocks, native)
			base = v1_21_50.New()
		}
		if err != nil {
			t.Fatal(err)
		}
		client := Client(base)
		decode := func(pk packet.Packet) []packet.Packet {
			t.Helper()
			wire := &bytes.Buffer{}
			out := server.ConvertFromLatest(pk, nil)
			if len(out) != 1 {
				t.Fatalf("%d: got %d wire packets", id, len(out))
			}
			out[0].Marshal(server.NewWriter(wire, 0))
			incoming := client.Packets(false)[pk.ID()]()
			incoming.Marshal(client.NewReader(wire, 0, true))
			if wire.Len() != 0 {
				t.Fatalf("%d unread bytes", wire.Len())
			}
			return client.ConvertToLatest(incoming, nil)
		}
		got := decode(&packet.StartGame{PropertyData: map[string]any{}, GameRules: []protocol.GameRule{{Name: "doDaylightCycle", Value: true}, {Name: "randomTickSpeed", Value: uint32(3)}, {Name: "test", Value: float32(1.25)}}, WorldSpawn: protocol.BlockPos{-13, 64, 23}})
		if len(got) != 1 {
			t.Fatalf("%d: unexpected StartGame conversion", id)
		}
		registry := decode(&packet.ItemRegistry{Items: native})
		items := registry[0].(*packet.ItemRegistry).Items
		want, err := blocktranslate.Items(id)
		if err != nil {
			t.Fatal(err)
		}
		if len(items) != len(want) {
			t.Fatalf("%d: got %d entries, want %d", id, len(items), len(want))
		}
		ids := map[string]int16{}
		for _, item := range items {
			ids[item.Name] = item.RuntimeID
		}
		for _, item := range want {
			if ids[item.Name] != item.RuntimeID {
				t.Fatalf("%d: %s wrong ID", id, item.Name)
			}
		}
		// Changing captured wire data must affect the recovered result. This guards
		// against replacing a missing received table with the expected catalog.
		wrapped := client.(*legacyClient)
		wrapped.items[0].RuntimeID = 30000
		registry = decode(&packet.ItemRegistry{Items: native})
		if registry[0].(*packet.ItemRegistry).Items[0].RuntimeID != 30000 {
			t.Fatal("received ID was replaced by pinned ID")
		}
		if _, err := LegacyStartGameItems([]byte{1, 2, 3}); err == nil {
			t.Fatal("truncated payload accepted")
		}
	}
}
