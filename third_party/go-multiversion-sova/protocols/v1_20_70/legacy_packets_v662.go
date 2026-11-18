package v1_20_70

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump662                 = 20
	idTickSync662                      = 23
	idPlayerInput662                   = 57
	idCompressedBiomeDefinitionList662 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket662 interface{ legacyOnly662() }

type passengerJump662 struct{ JumpStrength int32 }

func (*passengerJump662) ID() uint32                { return idPassengerJump662 }
func (*passengerJump662) legacyOnly662()            {}
func (pk *passengerJump662) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync662 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync662) ID() uint32     { return idTickSync662 }
func (*tickSync662) legacyOnly662() {}
func (pk *tickSync662) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput662 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput662) ID() uint32     { return idPlayerInput662 }
func (*playerInput662) legacyOnly662() {}
func (pk *playerInput662) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList662 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList662) ID() uint32     { return idCompressedBiomeDefinitionList662 }
func (*compressedBiomeDefinitionList662) legacyOnly662() {}
func (pk *compressedBiomeDefinitionList662) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
