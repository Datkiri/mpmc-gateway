package v1_20_30

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v618 "github.com/shawtymarco/go-multiversion/data/v618"
)

func marshalBiomeDefinitionList(io *wireIO, _ packet.Packet) {
	definitions := v618.BiomeDefinitions()
	io.Bytes(&definitions)
}
