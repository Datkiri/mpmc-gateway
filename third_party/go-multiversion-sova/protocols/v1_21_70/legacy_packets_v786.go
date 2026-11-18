package v1_21_70

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump786                 = 20
	idTickSync786                      = 23
	idPlayerInput786                   = 57
	idCompressedBiomeDefinitionList786 = uint32(packet.IDTrimData - 1)
	idSetMovementAuthority786          = uint32(packet.IDCameraAimAssistPresets - 1)
)

type legacyOnlyPacket786 interface{ legacyOnly786() }

type passengerJump786 struct{ JumpStrength int32 }

func (*passengerJump786) ID() uint32                { return idPassengerJump786 }
func (*passengerJump786) legacyOnly786()            {}
func (pk *passengerJump786) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync786 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync786) ID() uint32     { return idTickSync786 }
func (*tickSync786) legacyOnly786() {}
func (pk *tickSync786) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput786 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput786) ID() uint32     { return idPlayerInput786 }
func (*playerInput786) legacyOnly786() {}
func (pk *playerInput786) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type setMovementAuthority786 struct{ MovementType byte }

func (*setMovementAuthority786) ID() uint32                { return idSetMovementAuthority786 }
func (*setMovementAuthority786) legacyOnly786()            {}
func (pk *setMovementAuthority786) Marshal(io protocol.IO) { io.Uint8(&pk.MovementType) }

type compressedBiomeDefinitionList786 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList786) ID() uint32     { return idCompressedBiomeDefinitionList786 }
func (*compressedBiomeDefinitionList786) legacyOnly786() {}
func (pk *compressedBiomeDefinitionList786) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
