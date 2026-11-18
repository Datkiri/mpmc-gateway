package v1_16_100

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/data/v419"
	"github.com/shawtymarco/go-multiversion/internal/actors"
)

var targetActors = actors.MustLoad(v419.EntityIdentifiers())

func targetActorIdentifiers() *packet.AvailableActorIdentifiers {
	return &packet.AvailableActorIdentifiers{SerialisedEntityIdentifiers: targetActors.Raw()}
}
