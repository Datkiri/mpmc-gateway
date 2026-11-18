package v1_20_60

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v649 "github.com/shawtymarco/go-multiversion/data/v649"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v649.BiomeDefinitions()
	io.Bytes(&definitions)
}
