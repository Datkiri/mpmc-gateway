package packetio

import "github.com/sandertv/gophertunnel/minecraft/protocol"

// LegacyHeightMap encodes the pre-1.26.50 row-major, unprefixed 256 heights.
// The native array has the same coordinates but its wire format prefixes each row.
func LegacyHeightMap(io protocol.IO, heights *protocol.HeightMap) {
	for z := range heights {
		for x := range heights[z] {
			io.Int8(&heights[z][x])
		}
	}
}

// LegacyDimensionRange preserves the old exclusive-maximum/minimum ordering.
func LegacyDimensionRange(io protocol.IO, definition *protocol.DimensionDefinition, reading bool) {
	maximum, minimum := definition.MinimumY+definition.HeightRange+1, definition.MinimumY
	io.Varint32(&maximum)
	io.Varint32(&minimum)
	if reading {
		definition.MinimumY, definition.HeightRange = minimum, maximum-minimum-1
		definition.DefaultBiome = ""
	}
}

// LegacyFilteredName bridges a required historical string to the native optional.
func LegacyFilteredName(io protocol.IO, name *protocol.Optional[string], reading bool) {
	value, _ := name.Value()
	io.String(&value)
	if reading {
		*name = protocol.Option(value)
	}
}
