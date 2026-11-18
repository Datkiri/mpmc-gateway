package v1_21_20

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalFullContainerName712(io *wireIO, value *protocol.FullContainerName) {
	io.Uint8(&value.ContainerID)
	dynamicContainerID, _ := value.DynamicContainerID.Value()
	io.Uint32(&dynamicContainerID)
	if io.reading && dynamicContainerID != 0 {
		value.DynamicContainerID = protocol.Option(dynamicContainerID)
	}
}

func marshalUpdateAttributes712(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.UpdateAttributes)
	io.ActorRuntimeID(&pk.EntityRuntimeID)
	protocol.FuncIOSlice(io.directional(), &pk.Attributes, func(raw protocol.IO, attribute *protocol.Attribute) {
		legacy := asWireIO(raw)
		legacy.Float32(&attribute.Min)
		legacy.Float32(&attribute.Max)
		legacy.Float32(&attribute.Value)
		legacy.Float32(&attribute.Default)
		legacy.String(&attribute.Name)
		protocol.Slice(legacy.directional(), &attribute.Modifiers)
	})
	io.Varuint64(&pk.Tick)
}

func marshalEmote712(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.Emote)
	io.ActorRuntimeID(&pk.EntityRuntimeID)
	io.String(&pk.EmoteID)
	io.String(&pk.XUID)
	io.String(&pk.PlatformID)
	io.Uint8(&pk.Flags)
}
