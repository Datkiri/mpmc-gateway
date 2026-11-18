package v1_18_10

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/data/v486"
	"github.com/shawtymarco/go-multiversion/internal/actors"
)

var targetActors = actors.MustLoad(v486.EntityIdentifiers())

func targetActorIdentifiers() *packet.AvailableActorIdentifiers {
	return &packet.AvailableActorIdentifiers{SerialisedEntityIdentifiers: targetActors.Raw()}
}
