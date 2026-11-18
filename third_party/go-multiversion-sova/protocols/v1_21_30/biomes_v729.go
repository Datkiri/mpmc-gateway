package v1_21_30

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v729 "github.com/shawtymarco/go-multiversion/data/v729"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v729.BiomeDefinitions()
	io.Bytes(&definitions)
}
