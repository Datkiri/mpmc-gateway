package v1_18_0

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/data/v475"
	"github.com/shawtymarco/go-multiversion/internal/actors"
)

var targetActors = actors.MustLoad(v475.EntityIdentifiers())

func targetActorIdentifiers() *packet.AvailableActorIdentifiers {
	return &packet.AvailableActorIdentifiers{SerialisedEntityIdentifiers: targetActors.Raw()}
}
