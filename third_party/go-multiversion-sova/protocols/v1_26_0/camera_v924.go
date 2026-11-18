package v1_26_0

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalCameraInstruction(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.CameraInstruction)
	protocol.OptionalMarshaler(io.directional(), &pk.Set)
	protocol.OptionalFunc(io.directional(), &pk.Clear, io.Bool)
	protocol.OptionalMarshaler(io.directional(), &pk.Fade)
	protocol.OptionalMarshaler(io.directional(), &pk.Target)
	protocol.OptionalFunc(io.directional(), &pk.RemoveTarget, io.Bool)
	protocol.OptionalFunc(io.directional(), &pk.FieldOfView, func(value *protocol.CameraInstructionFieldOfView) {
		marshalCameraFieldOfView924(io, value)
	})
	protocol.OptionalFunc(io.directional(), &pk.Spline, func(value *protocol.CameraSplineInstruction) {
		marshalCameraSplineInstruction924(io, value)
	})
	protocol.OptionalFunc(io.directional(), &pk.AttachToEntity, io.Int64)
	protocol.OptionalFunc(io.directional(), &pk.DetachFromEntity, io.Bool)
}

func marshalCameraFieldOfView924(io *wireIO, value *protocol.CameraInstructionFieldOfView) {
	io.Float32(&value.FieldOfView)
	io.Float32(&value.EaseTime)
	easeType := uint8(value.EaseType)
	if !io.reading && value.EaseType > protocol.EasingTypeInOutElastic {
		easeType = uint8(protocol.EasingTypeLinear)
	}
	io.Uint8(&easeType)
	value.EaseType = int32(easeType)
	io.Bool(&value.Clear)
}

func marshalCameraSplineInstruction924(io *wireIO, value *protocol.CameraSplineInstruction) {
	io.Float32(&value.TotalTime)
	splineType := protocol.Option(value.SplineType)
	protocol.OptionalFunc(io.directional(), &splineType, io.Uint8)
	protocol.FuncSlice(io.directional(), &value.Curve, io.Vec3)
	protocol.FuncIOSlice(io.directional(), &value.ProgressKeyFrames, func(raw protocol.IO, keyframe *protocol.CameraProgressOption) {
		marshalCameraProgressOption924(asWireIO(raw), keyframe)
	})
	protocol.FuncIOSlice(io.directional(), &value.RotationOptions, func(raw protocol.IO, option *protocol.CameraRotationOption) {
		marshalCameraRotationOption924(asWireIO(raw), option)
	})
	if io.reading {
		value.SplineType, _ = splineType.Value()
		value.SplineIdentifier = ""
		value.LoadFromJson = false
	}
}

func marshalCameraProgressOption924(io *wireIO, value *protocol.CameraProgressOption) {
	io.Float32(&value.Value)
	io.Float32(&value.Time)
	easeType := protocol.Option(uint8(value.EaseType))
	if !io.reading && value.EaseType > protocol.EasingTypeInOutElastic {
		easeType = protocol.Option(uint8(protocol.EasingTypeLinear))
	}
	protocol.OptionalFunc(io.directional(), &easeType, io.Uint8)
	if encoded, ok := easeType.Value(); ok {
		value.EaseType = int32(encoded)
	} else if io.reading {
		value.EaseType = protocol.EasingTypeLinear
	}
}

func marshalCameraRotationOption924(io *wireIO, value *protocol.CameraRotationOption) {
	io.Vec3(&value.Value)
	io.Float32(&value.Time)
	easeType := protocol.Option(uint8(value.EaseType))
	if !io.reading && value.EaseType > protocol.EasingTypeInOutElastic {
		easeType = protocol.Option(uint8(protocol.EasingTypeLinear))
	}
	protocol.OptionalFunc(io.directional(), &easeType, io.Uint8)
	if encoded, ok := easeType.Value(); ok {
		value.EaseType = int32(encoded)
	} else if io.reading {
		value.EaseType = protocol.EasingTypeLinear
	}
}

// marshalCameraSplineEase924 encodes the optional easing of a spline definition key frame, which protocol
// 924 writes as an optional uint8 rather than by name.
func marshalCameraSplineEase924(io *wireIO, easeType *protocol.Optional[int32]) {
	var encoded protocol.Optional[uint8]
	if value, ok := easeType.Value(); ok {
		if value > protocol.EasingTypeInOutElastic {
			value = protocol.EasingTypeLinear
		}
		encoded = protocol.Option(uint8(value))
	}
	protocol.OptionalFunc(io.directional(), &encoded, io.Uint8)
	if io.reading {
		*easeType = protocol.Optional[int32]{}
		if value, ok := encoded.Value(); ok {
			*easeType = protocol.Option(int32(value))
		}
	}
}

func marshalCameraSpline(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.CameraSpline)
	protocol.FuncIOSlice(io.directional(), &pk.Splines, func(raw protocol.IO, definition *protocol.CameraSplineDefinition) {
		legacy := asWireIO(raw)
		legacy.String(&definition.Name)
		legacy.Float32(&definition.TotalTime)
		var splineType protocol.Optional[uint8]
		switch definition.SplineType {
		case "":
		case protocol.SplineTypeLinear:
			splineType = protocol.Option(uint8(1))
		default:
			splineType = protocol.Option(uint8(0))
		}
		protocol.OptionalFunc(legacy.directional(), &splineType, legacy.Uint8)
		protocol.FuncSlice(legacy.directional(), &definition.ControlPoints, legacy.Vec3)
		protocol.FuncIOSlice(legacy.directional(), &definition.ProgressKeyFrames, func(raw protocol.IO, keyframe *protocol.CameraSplineProgressKeyFrame) {
			w := asWireIO(raw)
			w.Float32(&keyframe.Value)
			w.Float32(&keyframe.Time)
			marshalCameraSplineEase924(w, &keyframe.EaseType)
		})
		protocol.FuncIOSlice(legacy.directional(), &definition.RotationKeyFrames, func(raw protocol.IO, keyframe *protocol.CameraSplineRotationKeyFrame) {
			w := asWireIO(raw)
			w.Vec3(&keyframe.Value)
			w.Float32(&keyframe.Time)
			marshalCameraSplineEase924(w, &keyframe.EaseType)
		})
		if legacy.reading {
			definition.SplineType = ""
			if value, ok := splineType.Value(); ok {
				definition.SplineType = protocol.SplineTypeCatmullRom
				if value == 1 {
					definition.SplineType = protocol.SplineTypeLinear
				}
			}
		}
	})
}
