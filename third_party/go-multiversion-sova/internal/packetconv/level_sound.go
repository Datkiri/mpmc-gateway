package packetconv

import "github.com/sandertv/gophertunnel/minecraft/protocol/packet"

// LegacyDoorSound preserves the target-era door toggle event used by
// Dragonfly 5ac88dcd/677c8fa1. These clients predate distinct block-backed
// door, trapdoor and fence-gate level sounds. The legacy event has no RID.
func LegacyDoorSound(pk *packet.LevelSoundEvent) *packet.LevelEvent {
	switch pk.SoundType {
	case packet.SoundEventDoorOpen, packet.SoundEventDoorClose,
		packet.SoundEventTrapdoorOpen, packet.SoundEventTrapdoorClose,
		packet.SoundEventFenceGateOpen, packet.SoundEventFenceGateClose:
		return &packet.LevelEvent{EventType: packet.LevelEventSoundOpenDoor, Position: pk.Position}
	default:
		return nil
	}
}

// MapLevelSoundBlockRuntimeID maps ExtraData only for level sounds whose
// payload is a block runtime ID. Other sounds pack unrelated values such as a
// note instrument and pitch into the same field and must remain untouched.
func MapLevelSoundBlockRuntimeID(pk *packet.LevelSoundEvent, mapRuntimeID func(uint32) (uint32, bool)) bool {
	if !levelSoundUsesBlockRuntimeID(pk.SoundType) || pk.ExtraData < 0 {
		return true
	}
	if mapRuntimeID == nil {
		return false
	}
	mapped, ok := mapRuntimeID(uint32(pk.ExtraData))
	if !ok {
		return false
	}
	pk.ExtraData = int32(mapped)
	return true
}

func levelSoundUsesBlockRuntimeID(soundType string) bool {
	switch soundType {
	case packet.SoundEventDoorOpen,
		packet.SoundEventDoorClose,
		packet.SoundEventTrapdoorOpen,
		packet.SoundEventTrapdoorClose,
		packet.SoundEventFenceGateOpen,
		packet.SoundEventFenceGateClose,
		packet.SoundEventPlace,
		packet.SoundEventHit,
		packet.SoundEventItemUseOn:
		return true
	default:
		return false
	}
}
