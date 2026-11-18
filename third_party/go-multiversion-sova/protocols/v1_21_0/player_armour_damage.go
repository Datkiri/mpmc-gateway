package v1_21_0

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const armourSlotCount685 = 4

func marshalPlayerArmourDamage685(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.PlayerArmourDamage)
	if io.reading {
		var bitset uint8
		io.Uint8(&bitset)
		pk.List = make([]protocol.PlayerArmourDamageEntry, 0, armourSlotCount685)
		for slot := range armourSlotCount685 {
			if bitset&(1<<slot) == 0 {
				continue
			}
			var damage int32
			io.Varint32(&damage)
			if damage < -1<<15 || damage > 1<<15-1 {
				io.InvalidValue(damage, "armour damage", "does not fit the native int16 representation")
				return
			}
			pk.List = append(pk.List, protocol.PlayerArmourDamageEntry{ArmourSlot: int32(slot), Damage: int16(damage)})
		}
		return
	}

	var bitset uint8
	var damages [armourSlotCount685]int32
	for _, entry := range pk.List {
		if entry.ArmourSlot < 0 || entry.ArmourSlot >= armourSlotCount685 {
			io.InvalidValue(entry.ArmourSlot, "armour slot", "must be between 0 and 4 for protocol 685")
			return
		}
		mask := uint8(1 << entry.ArmourSlot)
		if bitset&mask != 0 {
			io.InvalidValue(entry.ArmourSlot, "armour slot", "duplicate slot cannot be represented by protocol 685")
			return
		}
		bitset |= mask
		damages[entry.ArmourSlot] = int32(entry.Damage)
	}
	io.Uint8(&bitset)
	for slot, damage := range damages {
		if bitset&(1<<slot) != 0 {
			io.Varint32(&damage)
		}
	}
}
