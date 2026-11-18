package v1_18_0

import (
	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

// marshalItemComponents implements the historical ItemComponent packet (0xa2):
// count, then identifier and NetworkLittleEndian component NBT. Runtime IDs are
// advertised in StartGame, not repeated as native ItemRegistry fields.
func marshalItemComponents(io *wireIO, raw packet.Packet) {
	pk := raw.(*packet.ItemRegistry)
	var entries []protocol.ItemEntry
	if !io.reading {
		for _, entry := range pk.Items {
			if entry.ComponentBased {
				entries = append(entries, entry)
			}
		}
	}
	protocol.FuncIOSlice(io.directional(), &entries, func(raw protocol.IO, entry *protocol.ItemEntry) {
		legacy := asWireIO(raw)
		legacy.String(&entry.Name)
		legacy.NBT(&entry.Data, nbt.NetworkLittleEndian)
		if io.reading {
			entry.ComponentBased = true
			if rid, ok := entry.Data["id"].(int32); ok {
				entry.RuntimeID = int16(rid)
			}
		}
	})
	if io.reading {
		pk.Items = entries
	}
}
