package v1_20_40

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalUpdateAttributes622(io *wireIO, raw packet.Packet) {
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

func marshalEmote622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.Emote)
	io.ActorRuntimeID(&pk.EntityRuntimeID)
	io.String(&pk.EmoteID)
	io.String(&pk.XUID)
	io.String(&pk.PlatformID)
	io.Uint8(&pk.Flags)
}

func marshalEntityLink622(raw protocol.IO, link *protocol.EntityLink) {
	io := asWireIO(raw)
	io.ActorUniqueID(&link.RiddenEntityUniqueID)
	io.ActorUniqueID(&link.RiderEntityUniqueID)
	io.Uint8(&link.Type)
	io.Bool(&link.Immediate)
	io.Bool(&link.RiderInitiated)
}

func marshalSetActorLink622(io *wireIO, raw packet.Packet) {
	marshalEntityLink622(io, &raw.(*packet.SetActorLink).EntityLink)
}

func marshalDisconnect622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.Disconnect)
	io.Varint32(&pk.Reason)
	io.Bool(&pk.HideDisconnectionScreen)
	if !pk.HideDisconnectionScreen {
		io.String(&pk.Message)
	}
}

func marshalChangeDimension622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.ChangeDimension)
	io.Varint32(&pk.Dimension)
	io.Vec3(&pk.Position)
	io.Bool(&pk.Respawn)
}

func marshalSetTitle622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.SetTitle)
	io.Varint32(&pk.ActionType)
	io.String(&pk.Text)
	io.Varint32(&pk.FadeInDuration)
	io.Varint32(&pk.RemainDuration)
	io.Varint32(&pk.FadeOutDuration)
	io.String(&pk.XUID)
	io.String(&pk.PlatformOnlineID)
}

func marshalStopSound622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.StopSound)
	io.String(&pk.SoundName)
	io.Bool(&pk.StopAll)
}

func marshalUpdatePlayerGameType622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.UpdatePlayerGameType)
	io.Varint32(&pk.GameType)
	io.ActorUniqueID(&pk.PlayerUniqueID)
}

func marshalSetActorMotion622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.SetActorMotion)
	io.ActorRuntimeID(&pk.EntityRuntimeID)
	io.Vec3(&pk.Velocity)
}

func marshalContainerClose622(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.ContainerClose)
	io.Uint8(&pk.WindowID)
	io.Bool(&pk.ServerSide)
}
