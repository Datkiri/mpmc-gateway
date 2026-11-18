package v1_20_40

import (
	"github.com/go-gl/mathgl/mgl32"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

const (
	idPassengerJump622                 = 20
	idTickSync622                      = 23
	idPlayerInput622                   = 57
	idCompressedBiomeDefinitionList622 = uint32(packet.IDTrimData - 1)
)

type legacyOnlyPacket622 interface{ legacyOnly622() }

type passengerJump622 struct{ JumpStrength int32 }

func (*passengerJump622) ID() uint32                { return idPassengerJump622 }
func (*passengerJump622) legacyOnly622()            {}
func (pk *passengerJump622) Marshal(io protocol.IO) { io.Varint32(&pk.JumpStrength) }

type tickSync622 struct{ ClientRequestTimestamp, ServerReceptionTimestamp int64 }

func (*tickSync622) ID() uint32     { return idTickSync622 }
func (*tickSync622) legacyOnly622() {}
func (pk *tickSync622) Marshal(io protocol.IO) {
	io.Int64(&pk.ClientRequestTimestamp)
	io.Int64(&pk.ServerReceptionTimestamp)
}

type playerInput622 struct {
	Movement          mgl32.Vec2
	Jumping, Sneaking bool
}

func (*playerInput622) ID() uint32     { return idPlayerInput622 }
func (*playerInput622) legacyOnly622() {}
func (pk *playerInput622) Marshal(io protocol.IO) {
	io.Vec2(&pk.Movement)
	io.Bool(&pk.Jumping)
	io.Bool(&pk.Sneaking)
}

type compressedBiomeDefinitionList622 struct{ SerialisedBiomeDefinitions []byte }

func (*compressedBiomeDefinitionList622) ID() uint32     { return idCompressedBiomeDefinitionList622 }
func (*compressedBiomeDefinitionList622) legacyOnly622() {}
func (pk *compressedBiomeDefinitionList622) Marshal(io protocol.IO) {
	io.Bytes(&pk.SerialisedBiomeDefinitions)
}
