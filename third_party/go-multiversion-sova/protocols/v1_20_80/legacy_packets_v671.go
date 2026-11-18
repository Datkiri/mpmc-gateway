package v1_20_80

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump671                 = 20
	idTickSync671                      = 23
	idPlayerInput671                   = 57
	idCompressedBiomeDefinitionList671 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket671 interface{ legacyOnly671() }

type passengerJump671 struct{ JumpStrength int32 }

func (*passengerJump671) ID() uint32                { return idPassengerJump671 }
func (*passengerJump671) legacyOnly671()            {}
func (pk *passengerJump671) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync671 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync671) ID() uint32     { return idTickSync671 }
func (*tickSync671) legacyOnly671() {}
func (pk *tickSync671) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput671 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput671) ID() uint32     { return idPlayerInput671 }
func (*playerInput671) legacyOnly671() {}
func (pk *playerInput671) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList671 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList671) ID() uint32     { return idCompressedBiomeDefinitionList671 }
func (*compressedBiomeDefinitionList671) legacyOnly671() {}
func (pk *compressedBiomeDefinitionList671) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
