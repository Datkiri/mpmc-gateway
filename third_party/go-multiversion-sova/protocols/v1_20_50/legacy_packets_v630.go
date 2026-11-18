package v1_20_50

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump630                 = 20
	idTickSync630                      = 23
	idPlayerInput630                   = 57
	idCompressedBiomeDefinitionList630 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket630 interface{ legacyOnly630() }

type passengerJump630 struct{ JumpStrength int32 }

func (*passengerJump630) ID() uint32                { return idPassengerJump630 }
func (*passengerJump630) legacyOnly630()            {}
func (pk *passengerJump630) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync630 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync630) ID() uint32     { return idTickSync630 }
func (*tickSync630) legacyOnly630() {}
func (pk *tickSync630) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput630 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput630) ID() uint32     { return idPlayerInput630 }
func (*playerInput630) legacyOnly630() {}
func (pk *playerInput630) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList630 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList630) ID() uint32     { return idCompressedBiomeDefinitionList630 }
func (*compressedBiomeDefinitionList630) legacyOnly630() {}
func (pk *compressedBiomeDefinitionList630) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
