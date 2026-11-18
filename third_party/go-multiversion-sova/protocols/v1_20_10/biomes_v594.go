package v1_20_10

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v594 "github.com/shawtymarco/go-multiversion/data/v594"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v594.BiomeDefinitions()
	io.Bytes(&definitions)
}
