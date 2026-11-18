package v1_20_10

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump594                 = 20
	idTickSync594                      = 23
	idPlayerInput594                   = 57
	idCompressedBiomeDefinitionList594 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket594 interface{ legacyOnly594() }

type passengerJump594 struct{ JumpStrength int32 }

func (*passengerJump594) ID() uint32                { return idPassengerJump594 }
func (*passengerJump594) legacyOnly594()            {}
func (pk *passengerJump594) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync594 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync594) ID() uint32     { return idTickSync594 }
func (*tickSync594) legacyOnly594() {}
func (pk *tickSync594) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput594 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput594) ID() uint32     { return idPlayerInput594 }
func (*playerInput594) legacyOnly594() {}
func (pk *playerInput594) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList594 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList594) ID() uint32     { return idCompressedBiomeDefinitionList594 }
func (*compressedBiomeDefinitionList594) legacyOnly594() {}
func (pk *compressedBiomeDefinitionList594) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
