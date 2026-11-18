package v1_20_0

import (
	"math"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalAvailableCommands589(io *wireIO, pk *packet.AvailableCommands) {
	protocol.FuncSlice(io.directional(), &pk.EnumValues, io.String)
	protocol.FuncSlice(io.directional(), &pk.Suffixes, io.String)
	protocol.FuncIOSlice(io.directional(), &pk.Enums, func(raw protocol.IO, value *protocol.CommandEnum) {
		marshalCommandEnum589(asWireIO(raw), value, len(pk.EnumValues))
	})
	protocol.FuncIOSlice(io.directional(), &pk.Commands, marshalCommand589)
	protocol.FuncIOSlice(io.directional(), &pk.DynamicEnums, marshalDynamicEnum589)
	protocol.FuncIOSlice(io.directional(), &pk.Constraints, marshalCommandConstraint589)
}

func marshalCommandEnum589(io *wireIO, value *protocol.CommandEnum, enumValueCount int) {
	io.String(&value.Type)
	count := uint32(len(value.ValueIndices))
	io.Varuint32(&count)
	if io.reading {
		value.ValueIndices = make([]uint32, count)
	}
	for index := range value.ValueIndices {
		switch {
		case enumValueCount <= math.MaxUint8:
			entry := uint8(value.ValueIndices[index])
			io.Uint8(&entry)
			value.ValueIndices[index] = uint32(entry)
		case enumValueCount <= math.MaxUint16:
			entry := uint16(value.ValueIndices[index])
			io.Uint16(&entry)
			value.ValueIndices[index] = uint32(entry)
		default:
			io.Uint32(&value.ValueIndices[index])
		}
	}
}

func marshalCommand589(raw protocol.IO, value *protocol.Command) {
	io := asWireIO(raw)
	io.String(&value.Name)
	io.String(&value.Description)
	io.Uint16(&value.Flags)
	io.Uint8(&value.PermissionLevel)
	io.Uint32(&value.AliasesOffset)
	protocol.FuncIOSlice(io.directional(), &value.Overloads, marshalCommandOverload589)
}

func marshalCommandOverload589(raw protocol.IO, value *protocol.CommandOverload) {
	io := asWireIO(raw)
	protocol.FuncIOSlice(io.directional(), &value.Parameters, marshalCommandParameter589)
}

func marshalCommandParameter589(raw protocol.IO, value *protocol.CommandParameter) {
	io := asWireIO(raw)
	io.String(&value.Name)
	if io.reading {
		io.Uint32(&value.Type)
		value.Type = commandArgumentFrom589(io, value.Type)
	} else {
		legacyType := commandArgumentTo589(io, value.Type)
		io.Uint32(&legacyType)
	}
	io.Bool(&value.Optional)
	options := value.Options
	if !io.reading && options == protocol.ParamOptionAsChainedCommand {
		options = 3
	}
	io.Uint8(&options)
	if io.reading {
		if options == 3 {
			options = protocol.ParamOptionAsChainedCommand
		}
		value.Options = options
	}
}

func commandArgumentTo589(io *wireIO, value uint32) uint32 {
	if value&(protocol.CommandArgEnum|protocol.CommandArgSoftEnum|protocol.CommandArgSuffixed) != 0 {
		return value
	}
	flags, kind := value&^0xfffff, value&0xfffff
	if kind == protocol.CommandArgTypeStandaloneTarget || kind == protocol.CommandArgTypeNonIDTarget {
		return flags | 8
	}
	legacy, ok := commandArgument589ByCurrent[kind]
	if !ok {
		// Protocol 589 has no representation for newer basic argument types.
		// RValue is the historical generic parser and keeps one unsupported
		// command usage from terminating the entire server write loop.
		return flags | 4
	}
	return flags | legacy
}

func commandArgumentFrom589(io *wireIO, value uint32) uint32 {
	if value&(protocol.CommandArgEnum|protocol.CommandArgSoftEnum|protocol.CommandArgSuffixed) != 0 {
		return value
	}
	flags, kind := value&^0xfffff, value&0xfffff
	current, ok := commandArgumentCurrentBy589[kind]
	if !ok {
		io.UnknownEnumOption(kind, "protocol 589 command argument type")
		return flags
	}
	return flags | current
}

var commandArgument589ByCurrent = map[uint32]uint32{
	protocol.CommandArgTypeInt:             1,
	protocol.CommandArgTypeFloat:           3,
	protocol.CommandArgTypeRValue:          4,
	protocol.CommandArgTypeWildcardInt:     5,
	protocol.CommandArgTypeOperator:        6,
	protocol.CommandArgTypeCompareOperator: 7,
	protocol.CommandArgTypeTarget:          8,
	protocol.CommandArgTypeWildcardTarget:  10,
	protocol.CommandArgTypeFilepath:        17,
	protocol.CommandArgTypeIntegerRange:    23,
	protocol.CommandArgTypeEquipmentSlots:  43,
	protocol.CommandArgTypeString:          44,
	protocol.CommandArgTypeBlockPosition:   52,
	protocol.CommandArgTypePosition:        53,
	protocol.CommandArgTypeMessage:         55,
	protocol.CommandArgTypeRawText:         58,
	protocol.CommandArgTypeJSON:            62,
	protocol.CommandArgTypeBlockStates:     71,
	protocol.CommandArgTypeCommand:         74,
}

var commandArgumentCurrentBy589 = func() map[uint32]uint32 {
	reverse := make(map[uint32]uint32, len(commandArgument589ByCurrent))
	for current, legacy := range commandArgument589ByCurrent {
		reverse[legacy] = current
	}
	return reverse
}()

func marshalDynamicEnum589(raw protocol.IO, value *protocol.DynamicEnum) {
	io := asWireIO(raw)
	io.String(&value.Type)
	protocol.FuncSlice(io.directional(), &value.Values, io.String)
}

func marshalCommandConstraint589(raw protocol.IO, value *protocol.CommandEnumConstraint) {
	io := asWireIO(raw)
	io.Uint32(&value.EnumValueIndex)
	io.Uint32(&value.EnumIndex)
	protocol.FuncSlice(io.directional(), &value.Constraints, io.Uint8)
}
