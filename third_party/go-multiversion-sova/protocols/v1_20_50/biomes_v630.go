package v1_20_50

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v630 "github.com/shawtymarco/go-multiversion/data/v630"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v630.BiomeDefinitions()
	io.Bytes(&definitions)
}
