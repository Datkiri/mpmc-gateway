package v1_21_30

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump729                 = 20
	idTickSync729                      = 23
	idPlayerInput729                   = 57
	idCompressedBiomeDefinitionList729 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket729 interface{ legacyOnly729() }

type passengerJump729 struct{ JumpStrength int32 }

func (*passengerJump729) ID() uint32                { return idPassengerJump729 }
func (*passengerJump729) legacyOnly729()            {}
func (pk *passengerJump729) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync729 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync729) ID() uint32     { return idTickSync729 }
func (*tickSync729) legacyOnly729() {}
func (pk *tickSync729) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput729 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput729) ID() uint32     { return idPlayerInput729 }
func (*playerInput729) legacyOnly729() {}
func (pk *playerInput729) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList729 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList729) ID() uint32     { return idCompressedBiomeDefinitionList729 }
func (*compressedBiomeDefinitionList729) legacyOnly729() {}
func (pk *compressedBiomeDefinitionList729) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
