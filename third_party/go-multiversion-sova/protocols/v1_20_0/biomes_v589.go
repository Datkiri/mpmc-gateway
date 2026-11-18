package v1_20_0

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v589 "github.com/shawtymarco/go-multiversion/data/v589"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v589.BiomeDefinitions()
	io.Bytes(&definitions)
}
