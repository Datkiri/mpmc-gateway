package v1_20_80

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v671 "github.com/shawtymarco/go-multiversion/data/v671"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v671.BiomeDefinitions()
	io.Bytes(&definitions)
}
