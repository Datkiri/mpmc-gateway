package v1_20_10

import (
	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalAgentAction594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.AgentAction)
	io.String(&pk.Identifier)
	io.Varint32(&pk.Action)
	io.ByteSlice(&pk.Response)
}

func marshalChangeMobProperty594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.ChangeMobProperty)
	entityUniqueID := uint64(pk.EntityUniqueID)
	io.Uint64(&entityUniqueID)
	pk.EntityUniqueID = int64(entityUniqueID)
	io.String(&pk.Property)
	io.Bool(&pk.BoolValue)
	io.String(&pk.StringValue)
	io.Varint32(&pk.IntValue)
	io.Float32(&pk.FloatValue)
}

func marshalJigsawStructureData594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.JigsawStructureData)
	var data []byte
	if !io.reading && pk.StructureData != nil {
		data, _ = nbt.MarshalEncoding(pk.StructureData, nbt.NetworkLittleEndian)
	}
	io.Bytes(&data)
	if io.reading && len(data) != 0 {
		_ = nbt.UnmarshalEncoding(data, &pk.StructureData, nbt.NetworkLittleEndian)
	}
}

func marshalRemoveVolumeEntity594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.RemoveVolumeEntity)
	runtimeID := uint64(pk.EntityRuntimeID)
	io.Uint64(&runtimeID)
	pk.EntityRuntimeID = uint32(runtimeID)
	io.Varint32(&pk.Dimension)
}

func marshalCameraAimAssist594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.CameraAimAssist)
	io.Vec2(&pk.Angle)
	io.Float32(&pk.Distance)
	io.Uint8(&pk.TargetMode)
	io.Uint8(&pk.Action)
	if io.reading {
		pk.Preset = ""
		pk.ShowDebugRender = false
	}
}

func marshalCameraPresets594(io *wireIO, _ packet.Packet) {
	data := map[string]any{}
	io.NBT(&data, nbt.NetworkLittleEndian)
}

func marshalCorrectPlayerMovePrediction594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.CorrectPlayerMovePrediction)
	io.Vec3(&pk.Position)
	io.Vec3(&pk.Delta)
	io.Bool(&pk.OnGround)
	io.Varuint64(&pk.Tick)
}

func marshalSetHud594(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.SetHud)
	elements := make([]byte, len(pk.Elements))
	if !io.reading {
		for index, element := range pk.Elements {
			elements[index] = byte(element)
		}
	}
	protocol.FuncSlice(io.directional(), &elements, io.Uint8)
	visibility := byte(pk.Visibility)
	io.Uint8(&visibility)
	if io.reading {
		pk.Elements = make([]int32, len(elements))
		for index, element := range elements {
			pk.Elements[index] = int32(element)
		}
		pk.Visibility = int32(visibility)
	}
}
