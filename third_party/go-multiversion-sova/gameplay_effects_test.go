package multiversion_test

import (
	"bytes"
	"fmt"
	"maps"
	"reflect"
	"testing"

	"github.com/df-mc/worldupgrader/blockupgrader"
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	multiversion "github.com/shawtymarco/go-multiversion"
	native "github.com/shawtymarco/go-multiversion/data/v2225"
	"github.com/shawtymarco/go-multiversion/mapping"
)

// Use real common block states with deliberately different native IDs. In
// particular, lime wool occupies zero: zero is a valid block RID, not air.
type effectsRegistry []mapping.BlockState

func (r effectsRegistry) BlockCount() int { return len(r) }
func (r effectsRegistry) AirRuntimeID() uint32 {
	for id, state := range r {
		if state.Name == "minecraft:air" {
			return uint32(id)
		}
	}
	panic("fixture has no air")
}
func (r effectsRegistry) RuntimeIDToState(id uint32) (string, map[string]any, bool) {
	if id >= uint32(len(r)) {
		return "", nil, false
	}
	return r[id].Name, maps.Clone(r[id].Properties), true
}

func effectsProtocols(t *testing.T) []minecraft.Protocol {
	t.Helper()
	states, err := native.BlockStates()
	if err != nil {
		t.Fatal(err)
	}
	registry := make(effectsRegistry, 0, len(states))
	seen := make(map[string]bool)
	for i := len(states) - 1; i >= 0; i-- {
		state := states[i]
		upgraded := blockupgrader.Upgrade(blockupgrader.BlockState{Name: state.Name, Properties: maps.Clone(state.Properties), Version: state.Version})
		key, err := mapping.StateKey(upgraded.Name, upgraded.Properties)
		if err != nil {
			t.Fatal(err)
		}
		if !seen[key] {
			registry = append(registry, mapping.BlockState{Name: upgraded.Name, Properties: upgraded.Properties, Version: upgraded.Version})
			seen[key] = true
		}
	}
	for i, state := range registry {
		if state.Name == "minecraft:lime_wool" {
			registry[0], registry[i] = registry[i], registry[0]
			break
		}
	}
	if registry[0].Name != "minecraft:lime_wool" {
		t.Fatal("lime wool missing from locked snapshot")
	}
	snapshot, err := native.Items()
	if err != nil {
		t.Fatal(err)
	}
	items := make([]protocol.ItemEntry, 0, len(snapshot))
	for name, item := range snapshot {
		if name == "minecraft:white_wool" {
			item.RuntimeID = -5000
		}
		items = append(items, protocol.ItemEntry{Name: name, RuntimeID: int16(item.RuntimeID), ComponentBased: item.ComponentBased, Version: item.Version, Data: item.Data})
	}
	adapters, err := multiversion.ProtocolsWithRegistries(registry, items)
	if err != nil {
		t.Fatal(err)
	}
	return adapters
}

func effectWire(t *testing.T, p minecraft.Protocol, input packet.Packet) (packet.Packet, []byte) {
	t.Helper()
	converted := p.ConvertFromLatest(input, nil)
	if len(converted) != 1 {
		t.Fatalf("%T converted into %d packets", input, len(converted))
	}
	var body bytes.Buffer
	converted[0].Marshal(p.NewWriter(&body, 0))
	encoded := bytes.Clone(body.Bytes())
	constructor, ok := p.Packets(false)[converted[0].ID()]
	if !ok {
		t.Fatalf("packet %d missing from target server pool", converted[0].ID())
	}
	decoded := constructor()
	decoded.Marshal(p.NewReader(&body, 0, true))
	if body.Len() != 0 {
		t.Fatalf("%T left %d unread bytes", input, body.Len())
	}
	return decoded, encoded
}

func TestGameplayEffectsAcrossProtocols(t *testing.T) {
	for _, p := range effectsProtocols(t) {
		t.Run(fmt.Sprint(p.ID()), func(t *testing.T) {
			mapper := p.(interface{ MapBlockRuntimeID(uint32) (uint32, bool) })
			targetWool, ok := mapper.MapBlockRuntimeID(0)
			if !ok || targetWool == 0 {
				t.Fatal("fixture must exercise non-identity lime wool mapping")
			}
			for _, sound := range []string{packet.SoundEventPlace, packet.SoundEventHit, packet.SoundEventItemUseOn,
				packet.SoundEventDoorOpen, packet.SoundEventDoorClose, packet.SoundEventTrapdoorOpen,
				packet.SoundEventTrapdoorClose, packet.SoundEventFenceGateOpen, packet.SoundEventFenceGateClose} {
				t.Run("sound/"+sound, func(t *testing.T) {
					input := &packet.LevelSoundEvent{SoundType: sound, ExtraData: 0, Position: mgl32.Vec3{-3, 70, 5}, EntityType: ":"}
					before := *input
					decoded, body := effectWire(t, p, input)
					if p.ID() <= 486 && sound != packet.SoundEventPlace && sound != packet.SoundEventHit && sound != packet.SoundEventItemUseOn {
						door, ok := decoded.(*packet.LevelEvent)
						if !ok || door.EventType != packet.LevelEventSoundOpenDoor || door.Position != input.Position || door.EventData != 0 {
							t.Fatalf("legacy door sound = %#v", decoded)
						}
						if !reflect.DeepEqual(*input, before) {
							t.Fatal("door input mutated")
						}
						return
					}
					reader := p.NewReader(bytes.NewBuffer(body), 0, true)
					if p.ID() >= 1001 {
						var name string
						reader.String(&name)
						if name != sound {
							t.Fatalf("sound = %q, want %q", name, sound)
						}
					} else {
						var id uint32
						reader.Varuint32(&id)
					}
					var position mgl32.Vec3
					var extra int32
					reader.Vec3(&position)
					reader.Varint32(&extra)
					if extra != int32(targetWool) {
						t.Errorf("wire block RID = %d, want historical lime wool %d", extra, targetWool)
					}
					latest := p.ConvertToLatest(decoded, nil)
					if len(latest) != 1 || latest[0].(*packet.LevelSoundEvent).ExtraData != 0 {
						t.Fatalf("sound did not return to native lime wool: %#v", latest)
					}
					if !reflect.DeepEqual(*input, before) {
						t.Fatal("sound input mutated")
					}
				})
			}
			t.Run("unsupported_sound", func(t *testing.T) {
				if p.ID() >= 1001 {
					return
				}
				input := &packet.LevelSoundEvent{SoundType: "test:unsupported_sound", ExtraData: -1}
				if output := p.ConvertFromLatest(input, nil); len(output) != 0 {
					t.Fatalf("unrepresentable sound was not omitted: %#v", output)
				}
			})
			t.Run("non_block_sound_data", func(t *testing.T) {
				for _, input := range []*packet.LevelSoundEvent{
					{SoundType: packet.SoundEventNote, ExtraData: 0x1234},
					{SoundType: packet.SoundEventPlace, ExtraData: -1},
				} {
					decoded, _ := effectWire(t, p, input)
					latest := p.ConvertToLatest(decoded, nil)
					if len(latest) != 1 || latest[0].(*packet.LevelSoundEvent).ExtraData != input.ExtraData {
						t.Fatal("non-block sound data changed")
					}
				}
			})
			t.Run("block_correction", func(t *testing.T) {
				input := &packet.UpdateBlock{Position: protocol.BlockPos{-3, 70, 5}, NewBlockRuntimeID: 0, Flags: packet.BlockUpdateNetwork}
				before := *input
				decoded, body := effectWire(t, p, input)
				reader := p.NewReader(bytes.NewBuffer(body), 0, true)
				var x, z int32
				reader.Varint32(&x)
				if p.ID() <= 924 {
					var y uint32
					reader.Varuint32(&y)
				} else {
					var y int32
					reader.Varint32(&y)
				}
				reader.Varint32(&z)
				var rid uint32
				reader.Varuint32(&rid)
				if rid != targetWool {
					t.Fatal("block correction disagrees with sound and particle palette")
				}
				latest := p.ConvertToLatest(decoded, nil)
				if len(latest) != 1 || !reflect.DeepEqual(latest[0], input) || *input != before {
					t.Fatal("block correction changed state or mutated input")
				}
			})
			t.Run("signed_item_particle", func(t *testing.T) {
				input := &packet.LevelEvent{EventType: packet.LevelEventParticleLegacyEvent | 14, EventData: (-5000 << 16) | 0xbeef}
				before := *input
				decoded, _ := effectWire(t, p, input)
				latest := p.ConvertToLatest(decoded, nil)
				if len(latest) != 1 || !reflect.DeepEqual(latest[0], input) {
					t.Fatalf("signed item particle did not round trip: %#v", latest)
				}
				if *input != before {
					t.Fatal("item particle input mutated")
				}
			})
			t.Run("unmapped_item_particle", func(t *testing.T) {
				input := &packet.LevelEvent{EventType: packet.LevelEventParticleLegacyEvent | 14, EventData: 30000 << 16}
				if output := p.ConvertFromLatest(input, nil); len(output) != 0 {
					t.Fatal("unmapped item particle was emitted")
				}
			})
			for _, event := range []int32{packet.LevelEventParticlesDestroyBlock, packet.LevelEventParticlesCrackBlock, packet.LevelEventParticleLegacyEvent | 21} {
				t.Run(fmt.Sprintf("particle/%d", event), func(t *testing.T) {
					input := &packet.LevelEvent{EventType: event, Position: mgl32.Vec3{-3, 70, 5}}
					want := int32(targetWool)
					if event == packet.LevelEventParticlesCrackBlock {
						input.EventData = 5 << 24
						want |= 5 << 24
					}
					before := *input
					decoded, _ := effectWire(t, p, input)
					wire := decoded.(*packet.LevelEvent)
					if wire.EventData != want {
						t.Errorf("particle block data = %d, want %d", wire.EventData, want)
					}
					wantType := event
					if event == packet.LevelEventParticleLegacyEvent|21 && p.ID() <= 486 {
						wantType--
					}
					if wire.EventType != wantType {
						t.Errorf("particle type = %d, want %d", wire.EventType, wantType)
					}
					latest := p.ConvertToLatest(decoded, nil)
					if len(latest) != 1 || !reflect.DeepEqual(latest[0], input) {
						t.Fatalf("particle round trip = %#v", latest)
					}
					if !reflect.DeepEqual(*input, before) {
						t.Fatal("particle input mutated")
					}
				})
			}
			t.Run("placement_zero_runtime_id", func(t *testing.T) {
				input := &packet.InventoryTransaction{TransactionData: &protocol.UseItemTransactionData{
					ActionType: protocol.UseItemActionClickBlock, BlockPosition: protocol.BlockPos{-3, 70, 5}, BlockFace: 1,
				}}
				decoded, _ := effectWire(t, p, input)
				// This is an independently constructed client packet, not the result of
				// downgrading zero and upgrading the same (possibly broken) value.
				client := &packet.InventoryTransaction{TransactionData: &protocol.UseItemTransactionData{
					ActionType: protocol.UseItemActionClickBlock, BlockRuntimeID: targetWool,
				}}
				latest := p.ConvertToLatest(client, nil)
				if len(latest) != 1 || latest[0].(*packet.InventoryTransaction).TransactionData.(*protocol.UseItemTransactionData).BlockRuntimeID != 0 {
					t.Fatal("historical wool did not map to native RID zero")
				}
				upgraded := p.ConvertToLatest(decoded, nil)
				if len(upgraded) != 1 || upgraded[0].(*packet.InventoryTransaction).TransactionData.(*protocol.UseItemTransactionData).BlockRuntimeID != 0 {
					t.Fatal("placement wire round trip failed")
				}
				// Exercise a valid zero in the historical registry independently.
				var nativeZero uint32
				for id := uint32(1); ; id++ {
					mapped, valid := mapper.MapBlockRuntimeID(id)
					if !valid {
						t.Fatal("historical zero has no native block")
					}
					if mapped == 0 {
						nativeZero = id
						break
					}
				}
				zero := &packet.InventoryTransaction{TransactionData: &protocol.UseItemTransactionData{ActionType: protocol.UseItemActionClickBlock}}
				latest = p.ConvertToLatest(zero, nil)
				if len(latest) != 1 || latest[0].(*packet.InventoryTransaction).TransactionData.(*protocol.UseItemTransactionData).BlockRuntimeID != nativeZero {
					t.Fatalf("historical zero did not map to native block %d", nativeZero)
				}
				interaction := protocol.UseItemTransactionData{ActionType: protocol.UseItemActionClickBlock}
				auth := &packet.PlayerAuthInput{ItemInteractionData: protocol.Option(interaction)}
				latest = p.ConvertToLatest(auth, nil)
				if len(latest) != 1 {
					t.Fatal("block interaction input dropped")
				}
				mapped, present := latest[0].(*packet.PlayerAuthInput).ItemInteractionData.Value()
				if !present || mapped.BlockRuntimeID != nativeZero {
					t.Fatal("embedded zero block reference was not mapped")
				}
				unchanged, _ := auth.ItemInteractionData.Value()
				if unchanged.BlockRuntimeID != 0 {
					t.Fatal("embedded input was mutated")
				}
				air := &packet.InventoryTransaction{TransactionData: &protocol.UseItemTransactionData{ActionType: protocol.UseItemActionClickAir}}
				latest = p.ConvertToLatest(air, nil)
				if len(latest) != 1 || latest[0].(*packet.InventoryTransaction).TransactionData.(*protocol.UseItemTransactionData).BlockRuntimeID != 0 {
					t.Fatal("air click sentinel was mapped")
				}
			})
		})
	}
}
