package v1_21_0

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump685                 = 20
	idTickSync685                      = 23
	idPlayerInput685                   = 57
	idCompressedBiomeDefinitionList685 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket685 interface{ legacyOnly685() }

type passengerJump685 struct{ JumpStrength int32 }

func (*passengerJump685) ID() uint32                { return idPassengerJump685 }
func (*passengerJump685) legacyOnly685()            {}
func (pk *passengerJump685) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync685 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync685) ID() uint32     { return idTickSync685 }
func (*tickSync685) legacyOnly685() {}
func (pk *tickSync685) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput685 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput685) ID() uint32     { return idPlayerInput685 }
func (*playerInput685) legacyOnly685() {}
func (pk *playerInput685) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList685 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList685) ID() uint32     { return idCompressedBiomeDefinitionList685 }
func (*compressedBiomeDefinitionList685) legacyOnly685() {}
func (pk *compressedBiomeDefinitionList685) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
