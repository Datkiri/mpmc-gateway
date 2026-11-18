package v1_21_20

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v712 "github.com/shawtymarco/go-multiversion/data/v712"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v712.BiomeDefinitions()
	io.Bytes(&definitions)
}
