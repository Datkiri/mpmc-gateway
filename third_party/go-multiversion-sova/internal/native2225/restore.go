package native2225

import (
	"fmt"
	"math"
	"slices"

	"github.com/google/uuid"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func networkID(id int64) (uint32, error) {
	if id < 0 || id > math.MaxUint32 {
		return 0, fmt.Errorf("network ID outside uint32: %d", id)
	}
	return uint32(id), nil
}
func (c *Codec) restoreInput(x Input) (protocol.ItemDescriptorCount, error) {
	y := protocol.ItemDescriptorCount{Count: x.Count}
	switch x.DescriptorType {
	case protocol.ItemDescriptorInvalid:
		y.Descriptor = &protocol.InvalidItemDescriptor{}
	case protocol.ItemDescriptorDefault:
		if _, err := c.itemID(x.Name); err != nil {
			return y, err
		}
		name := x.Name
		if x.WireName != "" {
			name = x.WireName
		}
		y.Descriptor = &protocol.DefaultItemDescriptor{Name: name, MetadataValue: x.Meta}
	case protocol.ItemDescriptorItemTag:
		y.Descriptor = &protocol.ItemTagItemDescriptor{Tag: x.Tag}
	case protocol.ItemDescriptorMoLang:
		y.Descriptor = &protocol.MoLangItemDescriptor{Expression: x.Expression, Version: x.ExpressionVersion}
	default:
		return y, fmt.Errorf("unsupported descriptor type %d", x.DescriptorType)
	}
	return y, nil
}
func (c *Codec) restoreOutput(x Output) (protocol.ItemStack, error) {
	if x.Empty {
		return protocol.ItemStack{}, nil
	}
	if x.Meta < 0 || x.Count < 0 {
		return protocol.ItemStack{}, fmt.Errorf("negative stack metadata/count")
	}
	id, err := c.itemID(x.Name)
	if err != nil {
		return protocol.ItemStack{}, err
	}
	y := protocol.ItemStack{ItemType: protocol.ItemType{NetworkID: id, MetadataValue: uint32(x.Meta)}, Count: uint16(x.Count), NBTData: cloneCompound(x.NBTData), CanBePlacedOn: slices.Clone(x.CanPlace), CanBreak: slices.Clone(x.CanBreak), BlockingTick: x.BlockingTick}
	if x.State.Name != "" {
		h, ok := c.hashes[stateKey(x.State)]
		if !ok {
			return y, fmt.Errorf("unresolved block state %s", stateKey(x.State))
		}
		y.BlockRuntimeID = int32(h)
	}
	return y, nil
}
func (c *Codec) restoreCreativeItem(x CreativeItem) (protocol.ItemStack, error) {
	return c.restoreOutput(Output{Name: x.ItemName, Meta: int32(x.Meta), Count: x.Count, State: BlockState{Name: x.BlockName, Properties: x.BlockProperties}, NBTData: x.NBT, Empty: x.Empty, CanPlace: x.CanPlace, CanBreak: x.CanBreak, BlockingTick: x.BlockingTick})
}

func (c *Codec) RestoreCreative(x CreativeData) (*packet.CreativeContent, error) {
	out := new(packet.CreativeContent)
	var err error
	out.Groups, err = mapValues(x.Groups, func(g CreativeGroup) (protocol.CreativeGroup, error) {
		if g.Category < 0 || g.Category > math.MaxUint8 {
			return protocol.CreativeGroup{}, fmt.Errorf("invalid category")
		}
		icon, e := c.restoreCreativeItem(g.Icon)
		return protocol.CreativeGroup{Category: byte(g.Category), Name: g.Name, Icon: icon}, e
	})
	if err != nil {
		return nil, err
	}
	out.Items, err = mapValues(x.Items, func(i CreativeItem) (protocol.CreativeItem, error) {
		if i.GroupIndex < 0 || int(i.GroupIndex) >= len(x.Groups) {
			return protocol.CreativeItem{}, fmt.Errorf("invalid group")
		}
		id, e := networkID(i.SelectionID)
		if e != nil {
			return protocol.CreativeItem{}, e
		}
		item, e := c.restoreCreativeItem(i)
		return protocol.CreativeItem{CreativeItemNetworkID: id, GroupIndex: uint32(i.GroupIndex), Item: item}, e
	})
	return out, err
}
func (c *Codec) restoreUnlock(x Unlock) (protocol.Optional[protocol.RecipeUnlockRequirement], error) {
	if !x.Present {
		return protocol.Optional[protocol.RecipeUnlockRequirement]{}, nil
	}
	in, err := mapValues(x.Ingredients, c.restoreInput)
	return protocol.Option(protocol.RecipeUnlockRequirement{Context: x.Context, Ingredients: in}), err
}
func (c *Codec) restoreShapeless(x Recipe) (protocol.ShapelessRecipe, error) {
	y := protocol.ShapelessRecipe{RecipeID: x.ID, Block: x.Block, Priority: x.Priority}
	var err error
	y.UUID, err = uuid.Parse(x.UUID)
	if err != nil {
		return y, err
	}
	y.RecipeNetworkID, err = networkID(x.NetworkID)
	if err != nil {
		return y, err
	}
	y.Input, err = mapValues(x.Input, c.restoreInput)
	if err != nil {
		return y, err
	}
	y.Output, err = mapValues(x.Output, c.restoreOutput)
	if err != nil {
		return y, err
	}
	y.UnlockRequirement, err = c.restoreUnlock(x.Unlock)
	return y, err
}
func (c *Codec) restoreShaped(x Recipe) (protocol.ShapedRecipe, error) {
	y, err := c.restoreShapeless(x)
	if err != nil {
		return protocol.ShapedRecipe{}, err
	}
	return protocol.ShapedRecipe{RecipeID: y.RecipeID, Width: x.Width, Height: x.Height, Input: y.Input, Output: y.Output, UUID: y.UUID, Block: y.Block, Priority: y.Priority, AssumeSymmetry: x.AssumeSymmetry, UnlockRequirement: y.UnlockRequirement, RecipeNetworkID: y.RecipeNetworkID}, nil
}

func (c *Codec) RestoreCrafting(x Data) (*packet.CraftingData, error) {
	out := &packet.CraftingData{ClearRecipes: x.Crafting.ClearRecipes}
	var err error
	out.ShapedRecipes, err = mapValues(x.Crafting.Shaped, c.restoreShaped)
	if err != nil {
		return nil, err
	}
	out.ShapelessRecipes, err = mapValues(x.Crafting.Shapeless, c.restoreShapeless)
	if err != nil {
		return nil, err
	}
	out.UserDataShapelessRecipes, err = mapValues(x.Crafting.UserDataShapeless, func(r Recipe) (protocol.UserDataShapelessRecipe, error) {
		p, e := c.restoreShapeless(r)
		return protocol.UserDataShapelessRecipe{ShapelessRecipe: p}, e
	})
	if err != nil {
		return nil, err
	}
	out.ShapedChemistryRecipes, err = mapValues(x.Crafting.ShapedChemistry, func(r Recipe) (protocol.ShapedChemistryRecipe, error) {
		p, e := c.restoreShaped(r)
		return protocol.ShapedChemistryRecipe{ShapedRecipe: p}, e
	})
	if err != nil {
		return nil, err
	}
	out.ShapelessChemistryRecipes, err = mapValues(x.Crafting.ShapelessChemistry, func(r Recipe) (protocol.ShapelessChemistryRecipe, error) {
		p, e := c.restoreShapeless(r)
		return protocol.ShapelessChemistryRecipe{ShapelessRecipe: p}, e
	})
	if err != nil {
		return nil, err
	}
	if len(x.Crafting.Multi) != len(x.Crafting.MultiNetworkIDs) {
		return nil, fmt.Errorf("multi recipe IDs mismatch")
	}
	for i, s := range x.Crafting.Multi {
		u, e := uuid.Parse(s)
		if e != nil {
			return nil, e
		}
		id, e := networkID(x.Crafting.MultiNetworkIDs[i])
		if e != nil {
			return nil, e
		}
		out.MultiRecipes = append(out.MultiRecipes, protocol.MultiRecipe{UUID: u, RecipeNetworkID: id})
	}
	out.SmithingTransformRecipes, err = mapValues(x.Smithing, func(r Recipe) (protocol.SmithingTransformRecipe, error) {
		y := protocol.SmithingTransformRecipe{RecipeID: r.ID, Block: r.Block}
		if len(r.Input) != 3 || len(r.Output) != 1 {
			return y, fmt.Errorf("invalid smithing transform shape")
		}
		in, e := mapValues(r.Input, c.restoreInput)
		if e != nil {
			return y, e
		}
		y.Base, y.Addition, y.Template = in[0], in[1], in[2]
		y.Result, e = c.restoreOutput(r.Output[0])
		if e != nil {
			return y, e
		}
		y.RecipeNetworkID, e = networkID(r.NetworkID)
		return y, e
	})
	if err != nil {
		return nil, err
	}
	out.SmithingTrimRecipes, err = mapValues(x.SmithingTrim, func(r Recipe) (protocol.SmithingTrimRecipe, error) {
		y := protocol.SmithingTrimRecipe{RecipeID: r.ID, Block: r.Block}
		if len(r.Input) != 3 || len(r.Output) != 0 {
			return y, fmt.Errorf("invalid smithing trim shape")
		}
		in, e := mapValues(r.Input, c.restoreInput)
		if e != nil {
			return y, e
		}
		y.Base, y.Addition, y.Template = in[0], in[1], in[2]
		y.RecipeNetworkID, e = networkID(r.NetworkID)
		return y, e
	})
	if err != nil {
		return nil, err
	}
	out.PotionRecipes, err = mapValues(x.Potions.Potions, func(r PotionRecipe) (protocol.PotionRecipe, error) {
		y := protocol.PotionRecipe{InputPotionMetadata: r.Input.Meta, ReagentItemMetadata: r.Reagent.Meta, OutputPotionMetadata: r.Output.Meta}
		var e error
		y.InputPotionID, e = c.itemID(r.Input.Name)
		if e != nil {
			return y, e
		}
		y.ReagentItemID, e = c.itemID(r.Reagent.Name)
		if e != nil {
			return y, e
		}
		y.OutputPotionID, e = c.itemID(r.Output.Name)
		return y, e
	})
	if err != nil {
		return nil, err
	}
	out.PotionContainerChangeRecipes, err = mapValues(x.Potions.ContainerChanges, func(r ContainerChange) (protocol.PotionContainerChangeRecipe, error) {
		var y protocol.PotionContainerChangeRecipe
		var e error
		y.InputItemID, e = c.itemID(r.Input)
		if e != nil {
			return y, e
		}
		y.ReagentItemID, e = c.itemID(r.Reagent.Name)
		if e != nil {
			return y, e
		}
		y.OutputItemID, e = c.itemID(r.Output)
		return y, e
	})
	return out, err
}

func RestoreBiomes(x BiomeData) (*packet.BiomeDefinitionList, error) {
	p := &packet.BiomeDefinitionList{StringList: slices.Clone(x.Strings)}
	var err error
	p.BiomeDefinitions, err = mapValues(x.Definitions, func(b Biome) (protocol.BiomeDefinition, error) {
		d := protocol.BiomeDefinition{NameIndex: b.NameIndex, BiomeID: b.ID, Temperature: b.Temperature, Downfall: b.Downfall, FoliageSnow: b.FoliageSnow, Depth: b.Depth, Scale: b.Scale, MapWaterColour: b.MapWaterColour, Rain: b.Rain}
		if b.TagsPresent {
			tags := make([]uint16, len(b.Tags))
			for i, t := range b.Tags {
				if t < 0 || t > math.MaxUint16 {
					return d, fmt.Errorf("invalid biome tag")
				}
				tags[i] = uint16(t)
			}
			d.Tags = protocol.Option(tags)
		}
		return d, nil
	})
	return p, err
}
