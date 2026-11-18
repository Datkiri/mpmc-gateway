package v1_20_60

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump649                 = 20
	idTickSync649                      = 23
	idPlayerInput649                   = 57
	idCompressedBiomeDefinitionList649 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket649 interface{ legacyOnly649() }

type passengerJump649 struct{ JumpStrength int32 }

func (*passengerJump649) ID() uint32                { return idPassengerJump649 }
func (*passengerJump649) legacyOnly649()            {}
func (pk *passengerJump649) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync649 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync649) ID() uint32     { return idTickSync649 }
func (*tickSync649) legacyOnly649() {}
func (pk *tickSync649) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput649 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput649) ID() uint32     { return idPlayerInput649 }
func (*playerInput649) legacyOnly649() {}
func (pk *playerInput649) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList649 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList649) ID() uint32     { return idCompressedBiomeDefinitionList649 }
func (*compressedBiomeDefinitionList649) legacyOnly649() {}
func (pk *compressedBiomeDefinitionList649) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
