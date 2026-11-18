package native2225

import (
	"fmt"
	"hash/fnv"
	"math"
	"slices"
	"strconv"
	"strings"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

type Codec struct {
	items  map[int32]string
	ids    map[string]int32
	blocks map[uint32]BlockState
	hashes map[string]uint32
}

func New(items map[string]ItemEntry, states []BlockState) (*Codec, error) {
	c := &Codec{items: map[int32]string{}, ids: map[string]int32{}, blocks: map[uint32]BlockState{}, hashes: map[string]uint32{}}
	for name, item := range items {
		if _, ok := c.items[item.RuntimeID]; ok {
			return nil, fmt.Errorf("duplicate item ID %d", item.RuntimeID)
		}
		c.items[item.RuntimeID] = name
		c.ids[name] = item.RuntimeID
	}
	for _, state := range states {
		b, err := nbt.MarshalEncoding(orderedMap(map[string]any{"name": state.Name, "states": state.Properties}), nbt.LittleEndian)
		if err != nil {
			return nil, err
		}
		h := fnv.New32a()
		_, _ = h.Write(b)
		hash := h.Sum32()
		if state.Name == "minecraft:unknown" {
			hash = 0xfffffffe
		}
		if _, ok := c.blocks[hash]; ok {
			return nil, fmt.Errorf("duplicate block hash %d", hash)
		}
		c.blocks[hash] = state
		c.hashes[stateKey(state)] = hash
	}
	return c, nil
}

func mapValues[A, B any](in []A, fn func(A) (B, error)) ([]B, error) {
	out := make([]B, len(in))
	for i, x := range in {
		value, err := fn(x)
		if err != nil {
			return nil, fmt.Errorf("entry %d: %w", i, err)
		}
		out[i] = value
	}
	return out, nil
}
func cloneCompound(value map[string]any) map[string]any {
	if value == nil {
		return nil
	}
	out := make(map[string]any, len(value))
	for k, v := range value {
		out[k] = cloneValue(v)
	}
	return out
}
func cloneValue(v any) any {
	switch v := v.(type) {
	case map[string]any:
		return cloneCompound(v)
	case []any:
		out := make([]any, len(v))
		for i, x := range v {
			out[i] = cloneValue(x)
		}
		return out
	case []byte:
		return slices.Clone(v)
	case []int32:
		return slices.Clone(v)
	case []int64:
		return slices.Clone(v)
	default:
		return v
	}
}
func cloneState(s BlockState) BlockState { s.Properties = cloneCompound(s.Properties); return s }

func (c *Codec) itemName(id int32) (string, error) {
	name, ok := c.items[id]
	if !ok {
		return "", fmt.Errorf("unknown item ID %d", id)
	}
	return name, nil
}
func (c *Codec) itemID(name string) (int32, error) {
	id, ok := c.ids[name]
	if !ok {
		return 0, fmt.Errorf("unknown item %q", name)
	}
	return id, nil
}

func (c *Codec) input(x protocol.ItemDescriptorCount) (Input, error) {
	y := Input{Count: x.Count}
	if x.Count < 0 {
		return y, fmt.Errorf("negative ingredient count %d", x.Count)
	}
	switch d := x.Descriptor.(type) {
	case nil, *protocol.InvalidItemDescriptor:
		y.Name = "minecraft:air"
	case *protocol.DefaultItemDescriptor:
		y.DescriptorType = protocol.ItemDescriptorDefault
		y.Name = d.Name
		y.Meta = d.MetadataValue
		if _, err := c.itemID(d.Name); err != nil {
			// .30 emits both canonical identifiers and identifier:aux names.
			// Resolve the registry identifier only when the suffix agrees with
			// the separately encoded metadata, retaining the exact wire name.
			cut := strings.LastIndexByte(d.Name, ':')
			if cut < 0 {
				return y, err
			}
			aux, e := strconv.ParseInt(d.Name[cut+1:], 10, 32)
			if e != nil || int32(aux) != d.MetadataValue {
				return y, err
			}
			name := d.Name[:cut]
			if _, e := c.itemID(name); e != nil {
				return y, e
			}
			y.Name = name
			y.WireName = d.Name
		}
	case *protocol.ItemTagItemDescriptor:
		y.DescriptorType = protocol.ItemDescriptorItemTag
		y.Tag = d.Tag
	case *protocol.MoLangItemDescriptor:
		y.DescriptorType = protocol.ItemDescriptorMoLang
		y.Expression = d.Expression
		y.ExpressionVersion = d.Version
	default:
		return y, fmt.Errorf("unsupported descriptor %T", d)
	}
	return y, nil
}

func (c *Codec) output(x protocol.ItemStack) (Output, error) {
	y := Output{Name: "minecraft:air", Empty: x.NetworkID == 0}
	if y.Empty {
		return y, nil
	}
	if x.Count > math.MaxInt16 || x.MetadataValue > math.MaxInt32 {
		return y, fmt.Errorf("stack exceeds loader integer bounds")
	}
	name, err := c.itemName(x.NetworkID)
	if err != nil {
		return y, err
	}
	y.Name = name
	y.Meta = int32(x.MetadataValue)
	y.Count = int16(x.Count)
	y.NBTData = cloneCompound(x.NBTData)
	y.CanBreak = slices.Clone(x.CanBreak)
	y.CanPlace = slices.Clone(x.CanBePlacedOn)
	y.BlockingTick = x.BlockingTick
	if x.BlockRuntimeID != 0 {
		state, ok := c.blocks[uint32(x.BlockRuntimeID)]
		if !ok {
			return y, fmt.Errorf("unknown block hash %d for %s", x.BlockRuntimeID, name)
		}
		y.State = cloneState(state)
	}
	return y, nil
}

func (c *Codec) creativeItem(x protocol.ItemStack) (CreativeItem, error) {
	o, err := c.output(x)
	if err != nil {
		return CreativeItem{}, err
	}
	if o.Meta > math.MaxInt16 {
		return CreativeItem{}, fmt.Errorf("creative metadata exceeds int16")
	}
	y := CreativeItem{Name: o.Name, ItemName: o.Name, Meta: int16(o.Meta), Count: o.Count, NBT: o.NBTData, Empty: o.Empty, CanBreak: o.CanBreak, CanPlace: o.CanPlace, BlockingTick: o.BlockingTick}
	if o.State.Name != "" {
		y.Name = o.State.Name
		y.BlockName = o.State.Name
		y.BlockProperties = o.State.Properties
	}
	return y, nil
}

func (c *Codec) Creative(p *packet.CreativeContent) (CreativeData, error) {
	var out CreativeData
	var err error
	out.Groups, err = mapValues(p.Groups, func(g protocol.CreativeGroup) (CreativeGroup, error) {
		icon, e := c.creativeItem(g.Icon)
		return CreativeGroup{Category: int32(g.Category), Name: g.Name, Icon: icon}, e
	})
	if err != nil {
		return out, err
	}
	seen := map[uint32]bool{}
	out.Items, err = mapValues(p.Items, func(x protocol.CreativeItem) (CreativeItem, error) {
		if x.GroupIndex >= uint32(len(p.Groups)) {
			return CreativeItem{}, fmt.Errorf("invalid creative group %d", x.GroupIndex)
		}
		if seen[x.CreativeItemNetworkID] {
			return CreativeItem{}, fmt.Errorf("duplicate selection ID %d", x.CreativeItemNetworkID)
		}
		seen[x.CreativeItemNetworkID] = true
		y, e := c.creativeItem(x.Item)
		y.GroupIndex = int32(x.GroupIndex)
		y.SelectionID = int64(x.CreativeItemNetworkID)
		return y, e
	})
	return out, err
}

func (c *Codec) unlock(x protocol.Optional[protocol.RecipeUnlockRequirement]) (Unlock, error) {
	v, ok := x.Value()
	if !ok {
		return Unlock{}, nil
	}
	ingredients, err := mapValues(v.Ingredients, c.input)
	return Unlock{Present: true, Context: v.Context, Ingredients: ingredients}, err
}
func (c *Codec) shapeless(x protocol.ShapelessRecipe) (Recipe, error) {
	y := Recipe{ID: x.RecipeID, UUID: x.UUID.String(), Block: x.Block, Priority: x.Priority, NetworkID: int64(x.RecipeNetworkID)}
	var err error
	y.Input, err = mapValues(x.Input, c.input)
	if err != nil {
		return y, err
	}
	y.Output, err = mapValues(x.Output, c.output)
	if err != nil {
		return y, err
	}
	y.Unlock, err = c.unlock(x.UnlockRequirement)
	return y, err
}
func (c *Codec) shaped(x protocol.ShapedRecipe) (Recipe, error) {
	if x.Width <= 0 || x.Height <= 0 || int64(x.Width)*int64(x.Height) != int64(len(x.Input)) {
		return Recipe{}, fmt.Errorf("invalid shaped dimensions")
	}
	y, err := c.shapeless(protocol.ShapelessRecipe{RecipeID: x.RecipeID, Input: x.Input, Output: x.Output, UUID: x.UUID, Block: x.Block, Priority: x.Priority, UnlockRequirement: x.UnlockRequirement, RecipeNetworkID: x.RecipeNetworkID})
	y.Width = x.Width
	y.Height = x.Height
	y.AssumeSymmetry = x.AssumeSymmetry
	return y, err
}

func (c *Codec) Crafting(p *packet.CraftingData) (Data, error) {
	var out Data
	var err error
	if len(p.MaterialReducers) != 0 {
		return out, fmt.Errorf("material reducers require a separate schema; refusing to discard them")
	}
	out.Crafting.ClearRecipes = p.ClearRecipes
	out.Crafting.Shaped, err = mapValues(p.ShapedRecipes, c.shaped)
	if err != nil {
		return out, err
	}
	out.Crafting.Shapeless, err = mapValues(p.ShapelessRecipes, c.shapeless)
	if err != nil {
		return out, err
	}
	out.Crafting.UserDataShapeless, err = mapValues(p.UserDataShapelessRecipes, func(x protocol.UserDataShapelessRecipe) (Recipe, error) { return c.shapeless(x.ShapelessRecipe) })
	if err != nil {
		return out, err
	}
	out.Crafting.ShapedChemistry, err = mapValues(p.ShapedChemistryRecipes, func(x protocol.ShapedChemistryRecipe) (Recipe, error) { return c.shaped(x.ShapedRecipe) })
	if err != nil {
		return out, err
	}
	out.Crafting.ShapelessChemistry, err = mapValues(p.ShapelessChemistryRecipes, func(x protocol.ShapelessChemistryRecipe) (Recipe, error) { return c.shapeless(x.ShapelessRecipe) })
	if err != nil {
		return out, err
	}
	for _, r := range p.MultiRecipes {
		out.Crafting.Multi = append(out.Crafting.Multi, r.UUID.String())
		out.Crafting.MultiNetworkIDs = append(out.Crafting.MultiNetworkIDs, int64(r.RecipeNetworkID))
	}
	out.Smithing, err = mapValues(p.SmithingTransformRecipes, func(x protocol.SmithingTransformRecipe) (Recipe, error) {
		r := Recipe{ID: x.RecipeID, Block: x.Block, NetworkID: int64(x.RecipeNetworkID)}
		r.Input, err = mapValues([]protocol.ItemDescriptorCount{x.Base, x.Addition, x.Template}, c.input)
		if err != nil {
			return r, err
		}
		r.Output, err = mapValues([]protocol.ItemStack{x.Result}, c.output)
		return r, err
	})
	if err != nil {
		return out, err
	}
	out.SmithingTrim, err = mapValues(p.SmithingTrimRecipes, func(x protocol.SmithingTrimRecipe) (Recipe, error) {
		r := Recipe{ID: x.RecipeID, Block: x.Block, NetworkID: int64(x.RecipeNetworkID)}
		r.Input, err = mapValues([]protocol.ItemDescriptorCount{x.Base, x.Addition, x.Template}, c.input)
		return r, err
	})
	if err != nil {
		return out, err
	}
	out.Potions.Potions, err = mapValues(p.PotionRecipes, func(x protocol.PotionRecipe) (PotionRecipe, error) {
		var r PotionRecipe
		input, e := c.itemName(x.InputPotionID)
		if e != nil {
			return r, e
		}
		reagent, e := c.itemName(x.ReagentItemID)
		if e != nil {
			return r, e
		}
		output, e := c.itemName(x.OutputPotionID)
		if e != nil {
			return r, e
		}
		r.Input = Input{Name: input, Meta: x.InputPotionMetadata, Count: 1, DescriptorType: protocol.ItemDescriptorDefault}
		r.Reagent = Input{Name: reagent, Meta: x.ReagentItemMetadata, Count: 1, DescriptorType: protocol.ItemDescriptorDefault}
		r.Output = Output{Name: output, Meta: x.OutputPotionMetadata, Count: 1}
		return r, nil
	})
	if err != nil {
		return out, err
	}
	out.Potions.ContainerChanges, err = mapValues(p.PotionContainerChangeRecipes, func(x protocol.PotionContainerChangeRecipe) (ContainerChange, error) {
		var r ContainerChange
		r.Input, err = c.itemName(x.InputItemID)
		if err != nil {
			return r, err
		}
		name, e := c.itemName(x.ReagentItemID)
		if e != nil {
			return r, e
		}
		r.Reagent = Input{Name: name, Count: 1, DescriptorType: protocol.ItemDescriptorDefault}
		r.Output, err = c.itemName(x.OutputItemID)
		return r, err
	})
	return out, err
}

func Biomes(p *packet.BiomeDefinitionList) (BiomeData, error) {
	out := BiomeData{Strings: slices.Clone(p.StringList)}
	var err error
	out.Definitions, err = mapValues(p.BiomeDefinitions, func(x protocol.BiomeDefinition) (Biome, error) {
		y := Biome{NameIndex: x.NameIndex, ID: x.BiomeID, Temperature: x.Temperature, Downfall: x.Downfall, FoliageSnow: x.FoliageSnow, Depth: x.Depth, Scale: x.Scale, MapWaterColour: x.MapWaterColour, Rain: x.Rain}
		if x.NameIndex < 0 || int(x.NameIndex) >= len(p.StringList) {
			return y, fmt.Errorf("invalid biome name index")
		}
		if _, ok := x.ChunkGeneration.Value(); ok {
			return y, fmt.Errorf("chunk generation requires a separate schema")
		}
		v, ok := x.Tags.Value()
		y.TagsPresent = ok
		for _, tag := range v {
			if int(tag) >= len(p.StringList) {
				return y, fmt.Errorf("invalid biome tag index")
			}
			y.Tags = append(y.Tags, int32(tag))
		}
		return y, nil
	})
	return out, err
}
