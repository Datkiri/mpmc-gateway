// Package v671 exposes the historical Dragonfly registry snapshots of
// Minecraft 1.20.80-1.20.81/protocol 671. Recipes and biomes are shared with data/v748.
package v671

import (
	"bytes"
	_ "embed"
	"fmt"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
	v748 "github.com/shawtymarco/go-multiversion/data/v748"
)

var (
	//go:embed block_states.nbt
	blockStateData []byte
	//go:embed item_runtime_ids.nbt
	itemRuntimeIDData []byte
	//go:embed creative_items.nbt
	creativeItemData []byte
)

// BlockState is one exact state in the historical network block registry.
type BlockState struct {
	Name       string         `nbt:"name"`
	Properties map[string]any `nbt:"states"`
	Version    int32          `nbt:"version"`
}

// CreativeItem contains one historical creative stack description.
type CreativeItem struct {
	Name            string         `nbt:"name"`
	Meta            int16          `nbt:"meta"`
	NBT             map[string]any `nbt:"nbt,omitempty"`
	BlockProperties map[string]any `nbt:"block_properties,omitempty"`
}

// RecipeBlockState is the exact block state attached to a recipe item.
type RecipeBlockState = v748.RecipeBlockState

// RecipeInput is one item or item tag consumed by a recipe.
type RecipeInput = v748.RecipeInput

// RecipeOutput is one item stack produced by a recipe.
type RecipeOutput = v748.RecipeOutput

// ShapedRecipe is a historical shaped crafting recipe.
type ShapedRecipe = v748.ShapedRecipe

// ShapelessRecipe is a historical shapeless crafting recipe.
type ShapelessRecipe = v748.ShapelessRecipe

// CraftingData contains all historical crafting recipe categories.
type CraftingData = v748.CraftingData

// PotionRecipe is a historical brewing recipe.
type PotionRecipe = v748.PotionRecipe

// PotionContainerChangeRecipe is a historical brewing container recipe.
type PotionContainerChangeRecipe = v748.PotionContainerChangeRecipe

// PotionData contains historical brewing and container-change recipes.
type PotionData = v748.PotionData

// FurnaceRecipe is one historical furnace-family recipe.
type FurnaceRecipe = v748.FurnaceRecipe

// BlockStates decodes every concatenated root compound in block_states.nbt.
func BlockStates() ([]BlockState, error) {
	reader := bytes.NewReader(blockStateData)
	decoder := nbt.NewDecoder(reader)
	states := make([]BlockState, 0, 12500)
	for {
		var state BlockState
		if err := decoder.Decode(&state); err != nil {
			// Dragonfly stores concatenated root compounds without an outer
			// length. The decoder reports its generic buffer-end error after the
			// final complete compound, so an exhausted verified blob is EOF.
			if reader.Len() == 0 {
				return states, nil
			}
			return nil, fmt.Errorf("decode block state %d: %w", len(states), err)
		}
		states = append(states, state)
	}
}

// Items decodes the historical identifier-keyed item registry.
func Items() (map[string]int32, error) {
	items := map[string]int32{}
	if err := nbt.Unmarshal(itemRuntimeIDData, &items); err != nil {
		return nil, fmt.Errorf("decode item runtime IDs: %w", err)
	}
	return items, nil
}

// Creative decodes historical creative groups and items.
func Creative() ([]CreativeItem, error) {
	var data []CreativeItem
	if err := nbt.Unmarshal(creativeItemData, &data); err != nil {
		return nil, fmt.Errorf("decode creative data: %w", err)
	}
	return data, nil
}

// BiomeDefinitions returns the exact target-era default network-NBT biome
// definitions used by gophertunnel during the spawn sequence.
func BiomeDefinitions() []byte { return v748.BiomeDefinitions() }

// Crafting decodes historical crafting recipes.
func Crafting() (CraftingData, error) { return v748.Crafting() }

// Potions decodes historical potion and container-change recipes.
func Potions() (PotionData, error) { return v748.Potions() }

// Furnaces decodes the historical furnace-family recipes.
func Furnaces() ([]FurnaceRecipe, error) { return v748.Furnaces() }

// Smithing decodes the historical smithing transform recipes.
func Smithing() ([]ShapelessRecipe, error) { return v748.Smithing() }

// RawSnapshot returns a copy of one embedded source blob by file name.
func RawSnapshot(name string) ([]byte, bool) {
	switch name {
	case "crafting_data.nbt", "potion_data.nbt", "furnace_data.nbt", "smithing_data.nbt", "biome_definitions.nbt":
		return v748.RawSnapshot(name)
	}
	var data []byte
	switch name {
	case "block_states.nbt":
		data = blockStateData
	case "item_runtime_ids.nbt":
		data = itemRuntimeIDData
	case "creative_items.nbt":
		data = creativeItemData
	default:
		return nil, false
	}
	return bytes.Clone(data), true
}
