package v1_21_60

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump776                 = 20
	idTickSync776                      = 23
	idPlayerInput776                   = 57
	idCompressedBiomeDefinitionList776 = uint32(packet.IDTrimData - 1)
	idSetMovementAuthority776          = uint32(packet.IDCameraAimAssistPresets - 1)
)

type legacyOnlyPacket776 interface{ legacyOnly776() }

type passengerJump776 struct{ JumpStrength int32 }

func (*passengerJump776) ID() uint32                { return idPassengerJump776 }
func (*passengerJump776) legacyOnly776()            {}
func (pk *passengerJump776) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync776 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync776) ID() uint32     { return idTickSync776 }
func (*tickSync776) legacyOnly776() {}
func (pk *tickSync776) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput776 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput776) ID() uint32     { return idPlayerInput776 }
func (*playerInput776) legacyOnly776() {}
func (pk *playerInput776) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type setMovementAuthority776 struct{ MovementType byte }

func (*setMovementAuthority776) ID() uint32                { return idSetMovementAuthority776 }
func (*setMovementAuthority776) legacyOnly776()            {}
func (pk *setMovementAuthority776) Marshal(io protocol.IO) { io.Uint8(&pk.MovementType) }

type compressedBiomeDefinitionList776 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList776) ID() uint32     { return idCompressedBiomeDefinitionList776 }
func (*compressedBiomeDefinitionList776) legacyOnly776() {}
func (pk *compressedBiomeDefinitionList776) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
