package v1_21_80

import (
	"math"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func marshalAvailableCommands800(io *wireIO, pk *packet.AvailableCommands) {
	protocol.FuncSlice(io.directional(), &pk.EnumValues, io.String)
	protocol.FuncSlice(io.directional(), &pk.ChainedSubcommandValues, io.String)
	protocol.FuncSlice(io.directional(), &pk.Suffixes, io.String)
	protocol.FuncIOSlice(io.directional(), &pk.Enums, func(raw protocol.IO, value *protocol.CommandEnum) {
		marshalCommandEnum800(asWireIO(raw), value, len(pk.EnumValues))
	})
	protocol.FuncIOSlice(io.directional(), &pk.ChainedSubcommands, marshalChainedSubcommand800)
	protocol.FuncIOSlice(io.directional(), &pk.Commands, marshalCommand800)
	protocol.FuncIOSlice(io.directional(), &pk.DynamicEnums, marshalDynamicEnum800)
	protocol.FuncIOSlice(io.directional(), &pk.Constraints, marshalCommandConstraint800)
}

func marshalCommandEnum800(io *wireIO, value *protocol.CommandEnum, enumValueCount int) {
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

func marshalChainedSubcommand800(raw protocol.IO, value *protocol.ChainedSubcommand) {
	io := asWireIO(raw)
	io.String(&value.Name)
	protocol.FuncIOSlice(io.directional(), &value.Values, func(raw protocol.IO, entry *protocol.ChainedSubcommandValue) {
		legacy := asWireIO(raw)
		index, kind := uint16(entry.Index), uint16(entry.Value)
		legacy.Uint16(&index)
		legacy.Uint16(&kind)
		entry.Index, entry.Value = uint32(index), uint32(kind)
	})
}

func marshalCommand800(raw protocol.IO, value *protocol.Command) {
	io := asWireIO(raw)
	io.String(&value.Name)
	io.String(&value.Description)
	io.Uint16(&value.Flags)
	io.Uint8(&value.PermissionLevel)
	io.Uint32(&value.AliasesOffset)
	count := uint32(len(value.ChainedSubcommandOffsets))
	io.Varuint32(&count)
	if io.reading {
		value.ChainedSubcommandOffsets = make([]uint32, count)
	}
	for index := range value.ChainedSubcommandOffsets {
		offset := uint16(value.ChainedSubcommandOffsets[index])
		io.Uint16(&offset)
		value.ChainedSubcommandOffsets[index] = uint32(offset)
	}
	protocol.FuncIOSlice(io.directional(), &value.Overloads, marshalCommandOverload800)
}

func marshalCommandOverload800(raw protocol.IO, value *protocol.CommandOverload) {
	io := asWireIO(raw)
	io.Bool(&value.Chaining)
	protocol.FuncIOSlice(io.directional(), &value.Parameters, marshalCommandParameter800)
}

func marshalCommandParameter800(raw protocol.IO, value *protocol.CommandParameter) {
	io := asWireIO(raw)
	io.String(&value.Name)
	if io.reading {
		io.Uint32(&value.Type)
		value.Type = commandArgumentFrom800(io, value.Type)
	} else {
		legacyType := commandArgumentTo800(io, value.Type)
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

func commandArgumentTo800(io *wireIO, value uint32) uint32 {
	// CommandArgTypeChainedCommand (0x8000000) is a flag, not a basic kind:
	// gophertunnel-2225/minecraft/protocol/command.go:171. BDS 1.21.80 sends a
	// "chainedCommand" parameter typed 0x8100000 (flag|CommandArgValid); pass
	// it through like the other flag bits instead of treating it as kind 0.
	if value&(protocol.CommandArgEnum|protocol.CommandArgSoftEnum|protocol.CommandArgSuffixed|protocol.CommandArgTypeChainedCommand) != 0 {
		return value
	}
	flags, kind := value&^0xfffff, value&0xfffff
	if kind == protocol.CommandArgTypeStandaloneTarget || kind == protocol.CommandArgTypeNonIDTarget {
		return flags | 8
	}
	legacy, ok := commandArgument800ByCurrent[kind]
	if !ok {
		// Protocol 800 has no representation for newer basic argument types.
		// RValue is the historical generic parser and keeps one unsupported
		// command usage from terminating the entire server write loop.
		return flags | 4
	}
	return flags | legacy
}

func commandArgumentFrom800(io *wireIO, value uint32) uint32 {
	if value&(protocol.CommandArgEnum|protocol.CommandArgSoftEnum|protocol.CommandArgSuffixed|protocol.CommandArgTypeChainedCommand) != 0 {
		return value
	}
	flags, kind := value&^0xfffff, value&0xfffff
	// The legacy wire encodes int as 0; the native map below lists only the
	// symbolic source constants, so handle this one numeric encoding explicitly.
	if kind == 0 {
		return flags | protocol.CommandArgTypeInt
	}
	current, ok := commandArgumentCurrentBy800[kind]
	if !ok {
		io.UnknownEnumOption(kind, "protocol 800 command argument type")
		return flags
	}
	return flags | current
}

var commandArgument800ByCurrent = map[uint32]uint32{
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
	protocol.CommandArgTypeEquipmentSlots:  47,
	protocol.CommandArgTypeString:          56,
	protocol.CommandArgTypeBlockPosition:   64,
	protocol.CommandArgTypePosition:        65,
	// BDS 1.21.x sends message/reason/titleText parameters as kind 68 (0x44);
	// gophertunnel-2225/minecraft/protocol/command.go defines
	// CommandArgTypeMessageRoot=68 (Message=67 is the non-root variant).
	protocol.CommandArgTypeMessage:     67,
	protocol.CommandArgTypeMessageRoot: 68,
	protocol.CommandArgTypeRawText:     70,
	protocol.CommandArgTypeJSON:        74,
	// BDS 1.21.80 sends the "blockStates" parameter as kind 84 (0x100054).
	// gophertunnel v1.57.0+ defines CommandArgTypeBlockStates=84; the old
	// hard-coded 83 came from gophertunnel v1.54/v1.55 and no longer matches
	// the wire. See https://github.com/sandertv/gophertunnel/blob/v1.57.0/minecraft/protocol/command.go
	protocol.CommandArgTypeBlockStates: 84,
	// BDS 1.21.x sends command parameters as kind 87 (0x57); gophertunnel
	// v1.56.0+ defines CommandArgTypeCommand=87 (v1.54/v1.55 used 86).
	protocol.CommandArgTypeCommand: 87,
}

var commandArgumentCurrentBy800 = func() map[uint32]uint32 {
	reverse := make(map[uint32]uint32, len(commandArgument800ByCurrent))
	for current, legacy := range commandArgument800ByCurrent {
		reverse[legacy] = current
	}
	return reverse
}()

func marshalDynamicEnum800(raw protocol.IO, value *protocol.DynamicEnum) {
	io := asWireIO(raw)
	io.String(&value.Type)
	protocol.FuncSlice(io.directional(), &value.Values, io.String)
}

func marshalCommandConstraint800(raw protocol.IO, value *protocol.CommandEnumConstraint) {
	io := asWireIO(raw)
	io.Uint32(&value.EnumValueIndex)
	io.Uint32(&value.EnumIndex)
	protocol.FuncSlice(io.directional(), &value.Constraints, io.Uint8)
}
