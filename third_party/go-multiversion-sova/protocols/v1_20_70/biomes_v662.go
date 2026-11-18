package v1_20_70

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v662 "github.com/shawtymarco/go-multiversion/data/v662"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v662.BiomeDefinitions()
	io.Bytes(&definitions)
}
