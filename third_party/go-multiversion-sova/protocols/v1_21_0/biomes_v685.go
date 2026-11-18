package v1_21_0

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v685 "github.com/shawtymarco/go-multiversion/data/v685"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v685.BiomeDefinitions()
	io.Bytes(&definitions)
}
