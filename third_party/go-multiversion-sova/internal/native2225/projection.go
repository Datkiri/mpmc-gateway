package native2225

import "fmt"

// The existing NBT decoder rejects unknown fields. Keep its exact schemas in
// the gameplay files and store additional native wire metadata separately.
type loaderInput struct {
	Name  string     `nbt:"name"`
	Meta  int32      `nbt:"meta"`
	Count int32      `nbt:"count"`
	State BlockState `nbt:"block,omitempty"`
	Tag   string     `nbt:"tag,omitempty"`
}
type loaderOutput struct {
	Name  string         `nbt:"name"`
	Meta  int32          `nbt:"meta"`
	Count int16          `nbt:"count"`
	State BlockState     `nbt:"block,omitempty"`
	Data  map[string]any `nbt:"data,omitempty"`
}
type loaderShapeless struct {
	Input    []loaderInput  `nbt:"input"`
	Output   []loaderOutput `nbt:"output"`
	Block    string         `nbt:"block"`
	Priority int32          `nbt:"priority"`
}
type loaderShaped struct {
	Input    []loaderInput  `nbt:"input"`
	Output   []loaderOutput `nbt:"output"`
	Block    string         `nbt:"block"`
	Priority int32          `nbt:"priority"`
	Width    int32          `nbt:"width"`
	Height   int32          `nbt:"height"`
}
type loaderCreativeItem struct {
	Name            string         `nbt:"name"`
	Meta            int16          `nbt:"meta"`
	NBT             map[string]any `nbt:"nbt,omitempty"`
	BlockProperties map[string]any `nbt:"block_properties,omitempty"`
	GroupIndex      int32          `nbt:"group_index,omitempty"`
}
type loaderCreativeGroup struct {
	Category int32              `nbt:"category"`
	Name     string             `nbt:"name"`
	Icon     loaderCreativeItem `nbt:"icon"`
}

func project[A, B any](in []A, fn func(A) B) []B {
	out := make([]B, len(in))
	for i, x := range in {
		out[i] = fn(x)
	}
	return out
}
func coreInput(x Input) loaderInput { return loaderInput{x.Name, x.Meta, x.Count, x.State, x.Tag} }
func coreOutput(x Output) loaderOutput {
	return loaderOutput{x.Name, x.Meta, x.Count, x.State, x.NBTData}
}
func coreCreative(x CreativeItem) loaderCreativeItem {
	return loaderCreativeItem{x.Name, x.Meta, x.NBT, x.BlockProperties, x.GroupIndex}
}
func coreShapeless(x Recipe) loaderShapeless {
	return loaderShapeless{project(x.Input, coreInput), project(x.Output, coreOutput), x.Block, x.Priority}
}

func LoaderValues(d Data) map[string]any {
	crafting := struct {
		Shaped    []loaderShaped    `nbt:"shaped"`
		Shapeless []loaderShapeless `nbt:"shapeless"`
		UserData  []loaderShapeless `nbt:"shulker_box"`
		Multi     []string          `nbt:"multi"`
	}{project(d.Crafting.Shaped, func(r Recipe) loaderShaped {
		return loaderShaped{project(r.Input, coreInput), project(r.Output, coreOutput), r.Block, r.Priority, r.Width, r.Height}
	}), project(d.Crafting.Shapeless, coreShapeless), project(d.Crafting.UserDataShapeless, coreShapeless), d.Crafting.Multi}
	creative := struct {
		Groups []loaderCreativeGroup `nbt:"groups"`
		Items  []loaderCreativeItem  `nbt:"items"`
	}{
		project(d.Creative.Groups, func(g CreativeGroup) loaderCreativeGroup {
			return loaderCreativeGroup{g.Category, g.Name, coreCreative(g.Icon)}
		}), project(d.Creative.Items, coreCreative)}
	type potion struct {
		Input   loaderInput  `nbt:"input"`
		Reagent loaderInput  `nbt:"reagent"`
		Output  loaderOutput `nbt:"output"`
	}
	type container struct {
		Input   string      `nbt:"input"`
		Reagent loaderInput `nbt:"reagent"`
		Output  string      `nbt:"output"`
	}
	potions := struct {
		Potions    []potion    `nbt:"potions"`
		Containers []container `nbt:"container_changes"`
	}{project(d.Potions.Potions, func(r PotionRecipe) potion {
		return potion{coreInput(r.Input), coreInput(r.Reagent), coreOutput(r.Output)}
	}), project(d.Potions.ContainerChanges, func(r ContainerChange) container { return container{r.Input, coreInput(r.Reagent), r.Output} })}
	return map[string]any{"creative_items.nbt": creative, "crafting_data.nbt": crafting, "potion_data.nbt": potions, "smithing_data.nbt": project(d.Smithing, coreShapeless), "smithing_trim_data.nbt": project(d.SmithingTrim, coreShapeless), "biome_definitions.nbt": d.Biomes, "packet_metadata.nbt": MetadataFor(d)}
}

type InputMetadata struct {
	Kind       uint8  `nbt:"kind"`
	WireName   string `nbt:"wire_name,omitempty"`
	Expression string `nbt:"expression,omitempty"`
	Version    int16  `nbt:"version,omitempty"`
}
type StackMetadata struct {
	Empty        bool     `nbt:"empty,omitempty"`
	CanPlace     []string `nbt:"can_place,omitempty"`
	CanBreak     []string `nbt:"can_break,omitempty"`
	BlockingTick int64    `nbt:"blocking_tick,omitempty"`
}
type CreativeMetadata struct {
	Stack     StackMetadata `nbt:"stack"`
	ItemName  string        `nbt:"item_name"`
	BlockName string        `nbt:"block_name,omitempty"`
	Count     int16         `nbt:"count"`
	ID        int64         `nbt:"selection_id"`
}
type RecipeMetadata struct {
	ID        string          `nbt:"id"`
	UUID      string          `nbt:"uuid"`
	NetworkID int64           `nbt:"network_id"`
	Symmetry  bool            `nbt:"assume_symmetry"`
	Unlock    Unlock          `nbt:"unlock"`
	Inputs    []InputMetadata `nbt:"inputs"`
	Outputs   []StackMetadata `nbt:"outputs"`
}
type Metadata struct {
	Version            int32              `nbt:"schema_version"`
	CreativeGroups     []CreativeMetadata `nbt:"creative_groups"`
	CreativeItems      []CreativeMetadata `nbt:"creative_items"`
	Shaped             []RecipeMetadata   `nbt:"shaped"`
	Shapeless          []RecipeMetadata   `nbt:"shapeless"`
	UserData           []RecipeMetadata   `nbt:"shulker_box"`
	Smithing           []RecipeMetadata   `nbt:"smithing"`
	Trim               []RecipeMetadata   `nbt:"smithing_trim"`
	MultiIDs           []int64            `nbt:"multi_network_ids"`
	Clear              bool               `nbt:"clear_recipes"`
	ShapedChemistry    []Recipe           `nbt:"shaped_chemistry"`
	ShapelessChemistry []Recipe           `nbt:"shapeless_chemistry"`
}

func stackMetadata(x Output) StackMetadata {
	return StackMetadata{x.Empty, x.CanPlace, x.CanBreak, x.BlockingTick}
}
func creativeMetadata(x CreativeItem) CreativeMetadata {
	return CreativeMetadata{Stack: StackMetadata{x.Empty, x.CanPlace, x.CanBreak, x.BlockingTick}, ItemName: x.ItemName, BlockName: x.BlockName, Count: x.Count, ID: x.SelectionID}
}
func recipeMetadata(x Recipe) RecipeMetadata {
	return RecipeMetadata{ID: x.ID, UUID: x.UUID, NetworkID: x.NetworkID, Symmetry: x.AssumeSymmetry, Unlock: x.Unlock, Inputs: project(x.Input, func(i Input) InputMetadata {
		return InputMetadata{i.DescriptorType, i.WireName, i.Expression, i.ExpressionVersion}
	}), Outputs: project(x.Output, stackMetadata)}
}
func MetadataFor(d Data) Metadata {
	return Metadata{Version: 1, CreativeGroups: project(d.Creative.Groups, func(g CreativeGroup) CreativeMetadata { return creativeMetadata(g.Icon) }), CreativeItems: project(d.Creative.Items, creativeMetadata), Shaped: project(d.Crafting.Shaped, recipeMetadata), Shapeless: project(d.Crafting.Shapeless, recipeMetadata), UserData: project(d.Crafting.UserDataShapeless, recipeMetadata), Smithing: project(d.Smithing, recipeMetadata), Trim: project(d.SmithingTrim, recipeMetadata), MultiIDs: d.Crafting.MultiNetworkIDs, Clear: d.Crafting.ClearRecipes, ShapedChemistry: d.Crafting.ShapedChemistry, ShapelessChemistry: d.Crafting.ShapelessChemistry}
}

func ApplyCreativeMetadata(d *CreativeData, m Metadata) error {
	if m.Version != 1 || len(d.Groups) != len(m.CreativeGroups) || len(d.Items) != len(m.CreativeItems) {
		return fmt.Errorf("creative metadata schema/count mismatch")
	}
	apply := func(x *CreativeItem, v CreativeMetadata) {
		x.ItemName = v.ItemName
		x.BlockName = v.BlockName
		x.Count = v.Count
		x.SelectionID = v.ID
		x.Empty = v.Stack.Empty
		x.CanPlace = v.Stack.CanPlace
		x.CanBreak = v.Stack.CanBreak
		x.BlockingTick = v.Stack.BlockingTick
	}
	for i := range d.Groups {
		apply(&d.Groups[i].Icon, m.CreativeGroups[i])
	}
	for i := range d.Items {
		apply(&d.Items[i], m.CreativeItems[i])
	}
	return nil
}
func ApplyRecipesMetadata(rs []Recipe, ms []RecipeMetadata) error {
	if len(rs) != len(ms) {
		return fmt.Errorf("recipe metadata count mismatch")
	}
	for i := range rs {
		r, m := &rs[i], ms[i]
		if len(r.Input) != len(m.Inputs) || len(r.Output) != len(m.Outputs) {
			return fmt.Errorf("recipe %d metadata shape mismatch", i)
		}
		r.ID = m.ID
		r.UUID = m.UUID
		r.NetworkID = m.NetworkID
		r.AssumeSymmetry = m.Symmetry
		r.Unlock = m.Unlock
		for j, v := range m.Inputs {
			r.Input[j].DescriptorType = v.Kind
			r.Input[j].WireName = v.WireName
			r.Input[j].Expression = v.Expression
			r.Input[j].ExpressionVersion = v.Version
		}
		for j, v := range m.Outputs {
			r.Output[j].Empty = v.Empty
			r.Output[j].CanPlace = v.CanPlace
			r.Output[j].CanBreak = v.CanBreak
			r.Output[j].BlockingTick = v.BlockingTick
		}
	}
	return nil
}
func ApplyCraftingMetadata(d *CraftingData, m Metadata) error {
	if m.Version != 1 || len(d.Multi) != len(m.MultiIDs) {
		return fmt.Errorf("crafting metadata schema/count mismatch")
	}
	if err := ApplyRecipesMetadata(d.Shaped, m.Shaped); err != nil {
		return err
	}
	if err := ApplyRecipesMetadata(d.Shapeless, m.Shapeless); err != nil {
		return err
	}
	if err := ApplyRecipesMetadata(d.UserDataShapeless, m.UserData); err != nil {
		return err
	}
	d.MultiNetworkIDs = m.MultiIDs
	d.ClearRecipes = m.Clear
	d.ShapedChemistry = m.ShapedChemistry
	d.ShapelessChemistry = m.ShapelessChemistry
	return nil
}
func ApplyMetadata(d *Data, m Metadata) error {
	if err := ApplyCreativeMetadata(&d.Creative, m); err != nil {
		return err
	}
	if err := ApplyCraftingMetadata(&d.Crafting, m); err != nil {
		return err
	}
	if err := ApplyRecipesMetadata(d.Smithing, m.Smithing); err != nil {
		return err
	}
	return ApplyRecipesMetadata(d.SmithingTrim, m.Trim)
}
