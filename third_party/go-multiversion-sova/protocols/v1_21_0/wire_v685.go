package v1_21_0

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalUpdateAttributes685(io *wireIO, raw packet.Packet) {
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

func marshalEmote685(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.Emote)
	io.ActorRuntimeID(&pk.EntityRuntimeID)
	io.String(&pk.EmoteID)
	io.String(&pk.XUID)
	io.String(&pk.PlatformID)
	io.Uint8(&pk.Flags)
}

func marshalEntityLink685(raw protocol.IO, link *protocol.EntityLink) {
	io := asWireIO(raw)
	io.ActorUniqueID(&link.RiddenEntityUniqueID)
	io.ActorUniqueID(&link.RiderEntityUniqueID)
	io.Uint8(&link.Type)
	io.Bool(&link.Immediate)
	io.Bool(&link.RiderInitiated)
}

func marshalSetActorLink685(io *wireIO, raw packet.Packet) {
	marshalEntityLink685(io, &raw.(*packet.SetActorLink).EntityLink)
}

func marshalDisconnect685(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.Disconnect)
	io.Varint32(&pk.Reason)
	io.Bool(&pk.HideDisconnectionScreen)
	if !pk.HideDisconnectionScreen {
		io.String(&pk.Message)
	}
}

func marshalChangeDimension685(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.ChangeDimension)
	io.Varint32(&pk.Dimension)
	io.Vec3(&pk.Position)
	io.Bool(&pk.Respawn)
}

func marshalSetTitle685(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.SetTitle)
	io.Varint32(&pk.ActionType)
	io.String(&pk.Text)
	io.Varint32(&pk.FadeInDuration)
	io.Varint32(&pk.RemainDuration)
	io.Varint32(&pk.FadeOutDuration)
	io.String(&pk.XUID)
	io.String(&pk.PlatformOnlineID)
}

func marshalStopSound685(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.StopSound)
	io.String(&pk.SoundName)
	io.Bool(&pk.StopAll)
}
