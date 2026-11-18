package v1_21_20

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump712                 = 20
	idTickSync712                      = 23
	idPlayerInput712                   = 57
	idCompressedBiomeDefinitionList712 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket712 interface{ legacyOnly712() }

type passengerJump712 struct{ JumpStrength int32 }

func (*passengerJump712) ID() uint32                { return idPassengerJump712 }
func (*passengerJump712) legacyOnly712()            {}
func (pk *passengerJump712) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync712 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync712) ID() uint32     { return idTickSync712 }
func (*tickSync712) legacyOnly712() {}
func (pk *tickSync712) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput712 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput712) ID() uint32     { return idPlayerInput712 }
func (*playerInput712) legacyOnly712() {}
func (pk *playerInput712) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList712 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList712) ID() uint32     { return idCompressedBiomeDefinitionList712 }
func (*compressedBiomeDefinitionList712) legacyOnly712() {}
func (pk *compressedBiomeDefinitionList712) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
