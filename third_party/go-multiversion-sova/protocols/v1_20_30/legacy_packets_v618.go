package v1_20_30

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump618                 = 20
	idTickSync618                      = 23
	idPlayerInput618                   = 57
	idCompressedBiomeDefinitionList618 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket618 interface{ legacyOnly618() }

type passengerJump618 struct{ JumpStrength int32 }

func (*passengerJump618) ID() uint32                { return idPassengerJump618 }
func (*passengerJump618) legacyOnly618()            {}
func (pk *passengerJump618) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync618 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync618) ID() uint32     { return idTickSync618 }
func (*tickSync618) legacyOnly618() {}
func (pk *tickSync618) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput618 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput618) ID() uint32     { return idPlayerInput618 }
func (*playerInput618) legacyOnly618() {}
func (pk *playerInput618) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList618 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList618) ID() uint32     { return idCompressedBiomeDefinitionList618 }
func (*compressedBiomeDefinitionList618) legacyOnly618() {}
func (pk *compressedBiomeDefinitionList618) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
