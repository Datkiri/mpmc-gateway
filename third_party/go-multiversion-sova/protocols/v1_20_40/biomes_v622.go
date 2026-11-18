package v1_20_40

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v622 "github.com/shawtymarco/go-multiversion/data/v622"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v622.BiomeDefinitions()
	io.Bytes(&definitions)
}
