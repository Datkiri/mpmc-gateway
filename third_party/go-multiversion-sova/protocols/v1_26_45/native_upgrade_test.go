package v1_26_45

import (
	"bytes"
	"encoding/base64"
	"reflect"
	"testing"

	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/mapping"
)

func TestStackRequestMapsAfterReadingTheTargetRuntimeID(t *testing.T) {
	states := []mapping.BlockState{{Name: "minecraft:air"}, {Name: "test:a"}, {Name: "test:b"}, {Name: "test:c"}, {Name: "test:d"}, {Name: "minecraft:granite"}}
	blocks, err := mapping.NewBlockMapper(mappingTestRegistry{states: states}, []mapping.BlockState{states[0], states[5]})
	if err != nil {
		t.Fatal(err)
	}
	target := blocks.TargetStates()[1]
	want, _ := blocks.TargetToNative(1)
	if want == 1 {
		t.Fatal("fixture must exercise different runtime IDs")
	}
	var data bytes.Buffer
	protocol.NewWriter(&data, 0).StackRequestItem(&protocol.StackRequestItem{Identifier: target.Name, Count: 1, BlockRuntimeID: 1})
	p := Protocol{runtime: &runtimeData{blocks: blocks}}
	var decoded protocol.StackRequestItem
	p.NewReader(&data, 0, true).StackRequestItem(&decoded)
	if data.Len() != 0 || decoded.BlockRuntimeID != int32(want) {
		t.Fatalf("decoded runtime ID = %d, want %d; unread=%d", decoded.BlockRuntimeID, want, data.Len())
	}
}

// These bytes were emitted by the independent historical gophertunnel v1.61.0
// (283a5a97dfe65da94bcc0b401807f6aefa9e72ee), including its filtered-name fix.
func TestPopulatedHistoricalInventoryOracles(t *testing.T) {
	tests := []struct {
		name, body string
		fixture    func() packet.Packet
		listener   bool
	}{
		{"player interaction", "AAAAQAAAQEAAAIC/AACAQgAAEEEAAAAAAAAAAAAAAAABAkQMAAAAAAAAAAAAAABHAAAAAAAAAAAAAAAAAQEDAAEBAQABAQMBAAcAAAAAAAAAAAAAAAAAAAAABAEDjAEGBQgAAAAAAAAAAAAAgD8AAABAAABAQAAAgD4AAAA/AABAP4ABAQEBAAEAAQABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", populatedInput, true},
		{"filtered stack response", "AQANAQEBAAABAgADAQGOAQNyYXcBBWNsZWFuEg==", populatedResponse, false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			want, err := base64.StdEncoding.DecodeString(test.body)
			if err != nil {
				t.Fatal(err)
			}
			source := test.fixture()
			p := Protocol{}
			converted := p.ConvertFromLatest(source, nil)
			if len(converted) != 1 {
				t.Fatalf("conversion count = %d", len(converted))
			}
			var encoded bytes.Buffer
			converted[0].Marshal(p.NewWriter(&encoded, 0))
			if !bytes.Equal(encoded.Bytes(), want) {
				t.Fatalf("historical body mismatch:\n got %x\nwant %x", encoded.Bytes(), want)
			}
			if !reflect.DeepEqual(source, test.fixture()) {
				t.Fatal("encoding mutated the source")
			}
			reader := bytes.NewBuffer(want)
			decoded := p.Packets(test.listener)[source.ID()]()
			decoded.Marshal(p.NewReader(reader, 0, true))
			if reader.Len() != 0 {
				t.Fatalf("unread bytes = %d", reader.Len())
			}
			latest := p.ConvertToLatest(decoded, nil)
			if len(latest) != 1 || !reflect.DeepEqual(latest[0], test.fixture()) {
				t.Fatalf("historical decode changed semantics: %#v", latest)
			}
		})
	}
}

func populatedInput() packet.Packet {
	flags := protocol.NewInputFlags(packet.InputFlagCount)
	flags.Set(packet.InputFlagPerformItemInteraction)
	flags.Set(packet.InputFlagJumping)
	return &packet.PlayerAuthInput{
		Pitch: 2, Yaw: 3, Position: mgl32.Vec3{-1, 64, 9}, Tick: 71, InputData: flags,
		ItemInteractionData: protocol.Option(protocol.UseItemTransactionData{
			LegacyRequestID: -2, Actions: []protocol.InventoryAction{{SourceType: protocol.InventoryActionSourceContainer, WindowID: protocol.Option(int8(3)), InventorySlot: 7}},
			ActionType: protocol.UseItemActionBreakBlock, TriggerType: 1, BlockPosition: protocol.BlockPos{-2, 70, 3}, BlockFace: 5, HotBarSlot: 4,
			Position: mgl32.Vec3{1, 2, 3}, ClickedPosition: mgl32.Vec3{.25, .5, .75}, BlockRuntimeID: 128, ClientPrediction: 1, ClientCooldownState: 1,
		}),
	}
}

func populatedResponse() packet.Packet {
	return &packet.ItemStackResponse{Responses: []protocol.ItemStackResponse{{RequestID: -7, ContainerInfo: []protocol.StackResponseContainerInfo{{
		SlotInfo: []protocol.StackResponseSlotInfo{{Slot: 2, Count: 3, StackNetworkID: 71, CustomName: "raw", FilteredCustomName: protocol.Option("clean"), DurabilityCorrection: 9}},
	}}}}}
}
