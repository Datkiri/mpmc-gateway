// Package native2225 exposes the verified .30 source data through typed NBT
// decoders. Constructing these values does not enable a live protocol/registry.
package native2225

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	format "github.com/shawtymarco/go-multiversion/internal/native2225"
)

//go:embed *.nbt item_tags.json
var snapshots embed.FS

type (
	BlockState   = format.BlockState
	ItemEntry    = format.ItemEntry
	CreativeData = format.CreativeData
	CraftingData = format.CraftingData
	PotionData   = format.PotionData
	Recipe       = format.Recipe
	BiomeData    = format.BiomeData
)

func load[T any](name string) (T, error) {
	b, err := snapshots.ReadFile(name)
	if err != nil {
		var zero T
		return zero, err
	}
	v, err := format.Decode[T](b)
	if err != nil {
		return v, fmt.Errorf("decode %s: %w", name, err)
	}
	return v, nil
}
func Items() (map[string]ItemEntry, error) { return load[map[string]ItemEntry]("vanilla_items.nbt") }
func metadata() (format.Metadata, error) {
	m, err := load[format.Metadata]("packet_metadata.nbt")
	if err == nil && m.Version != 1 {
		err = fmt.Errorf("unsupported metadata schema %d", m.Version)
	}
	return m, err
}
func Creative() (CreativeData, error) {
	d, err := load[CreativeData]("creative_items.nbt")
	if err != nil {
		return d, err
	}
	m, err := metadata()
	if err != nil {
		return d, err
	}
	err = format.ApplyCreativeMetadata(&d, m)
	return d, err
}

// CreativePacket reconstructs the exact captured native creative registry from
// its typed assets and preserved metadata, including the original block hashes.
func CreativePacket() (*packet.CreativeContent, error) {
	items, err := Items()
	if err != nil {
		return nil, err
	}
	blocks, err := BlockStates()
	if err != nil {
		return nil, err
	}
	codec, err := format.New(items, blocks)
	if err != nil {
		return nil, err
	}
	creative, err := Creative()
	if err != nil {
		return nil, err
	}
	return codec.RestoreCreative(creative)
}
func Crafting() (CraftingData, error) {
	d, err := load[CraftingData]("crafting_data.nbt")
	if err != nil {
		return d, err
	}
	m, err := metadata()
	if err != nil {
		return d, err
	}
	err = format.ApplyCraftingMetadata(&d, m)
	return d, err
}
func Smithing() ([]Recipe, error) {
	d, err := load[[]Recipe]("smithing_data.nbt")
	if err != nil {
		return d, err
	}
	m, err := metadata()
	if err != nil {
		return d, err
	}
	err = format.ApplyRecipesMetadata(d, m.Smithing)
	return d, err
}
func SmithingTrim() ([]Recipe, error) {
	d, err := load[[]Recipe]("smithing_trim_data.nbt")
	if err != nil {
		return d, err
	}
	m, err := metadata()
	if err != nil {
		return d, err
	}
	err = format.ApplyRecipesMetadata(d, m.Trim)
	return d, err
}
func Potions() (PotionData, error) { return load[PotionData]("potion_data.nbt") }
func Biomes() (BiomeData, error)   { return load[BiomeData]("biome_definitions.nbt") }
func EntityIdentifiers() (map[string]any, error) {
	return load[map[string]any]("entity_identifiers.nbt")
}
func ItemTags() (map[string][]string, error) {
	b, err := snapshots.ReadFile("item_tags.json")
	if err != nil {
		return nil, err
	}
	var tags map[string][]string
	err = json.Unmarshal(b, &tags)
	return tags, err
}
func RawSnapshot(name string) ([]byte, error) { return snapshots.ReadFile(name) }
func BlockStates() ([]BlockState, error) {
	b, err := snapshots.ReadFile("block_states.nbt")
	if err != nil {
		return nil, err
	}
	buf := bytes.NewBuffer(b)
	d := nbt.NewDecoder(buf)
	var states []BlockState
	for buf.Len() > 0 {
		var state BlockState
		if err := d.Decode(&state); err != nil {
			return nil, fmt.Errorf("block %d: %w", len(states), err)
		}
		states = append(states, state)
	}
	return states, nil
}
