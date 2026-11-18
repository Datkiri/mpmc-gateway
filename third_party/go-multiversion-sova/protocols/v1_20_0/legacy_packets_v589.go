package v1_20_0

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump589                 = 20
	idTickSync589                      = 23
	idPlayerInput589                   = 57
	idCompressedBiomeDefinitionList589 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket589 interface{ legacyOnly589() }

type passengerJump589 struct{ JumpStrength int32 }

func (*passengerJump589) ID() uint32                { return idPassengerJump589 }
func (*passengerJump589) legacyOnly589()            {}
func (pk *passengerJump589) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync589 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync589) ID() uint32     { return idTickSync589 }
func (*tickSync589) legacyOnly589() {}
func (pk *tickSync589) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput589 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput589) ID() uint32     { return idPlayerInput589 }
func (*playerInput589) legacyOnly589() {}
func (pk *playerInput589) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList589 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList589) ID() uint32     { return idCompressedBiomeDefinitionList589 }
func (*compressedBiomeDefinitionList589) legacyOnly589() {}
func (pk *compressedBiomeDefinitionList589) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
