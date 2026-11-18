package v1_21_80

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const idSetMovementAuthority800 = uint32(packet.IDCameraAimAssistPresets - 1)

type legacyOnlyPacket800 interface{ legacyOnly800() }

type setMovementAuthority800 struct{ MovementType byte }

func (*setMovementAuthority800) ID() uint32                { return idSetMovementAuthority800 }
func (*setMovementAuthority800) legacyOnly800()            {}
func (pk *setMovementAuthority800) Marshal(io protocol.IO) { io.Uint8(&pk.MovementType) }
