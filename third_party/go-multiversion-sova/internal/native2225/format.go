// Package native2225 owns the lossless, loader-compatible projection of the
// locked native2225 acquisition. It does not enable a protocol or live registry.
package native2225

type BlockState struct {
	Name       string         `nbt:"name"`
	Properties map[string]any `nbt:"states"`
	Version    int32          `nbt:"version"`
}
type ItemEntry struct {
	RuntimeID      int32          `nbt:"runtime_id"`
	ComponentBased bool           `nbt:"component_based"`
	Version        int32          `nbt:"version"`
	Data           map[string]any `nbt:"data,omitempty"`
}

// CreativeItem retains the existing Dragonfly fields, plus wire metadata which
// the older loader does not consume. Name is the block name when a block state
// is attached; ItemName retains the separately resolved item identifier.
type CreativeItem struct {
	Name            string         `nbt:"name"`
	Meta            int16          `nbt:"meta"`
	NBT             map[string]any `nbt:"nbt,omitempty"`
	BlockProperties map[string]any `nbt:"block_properties,omitempty"`
	GroupIndex      int32          `nbt:"group_index,omitempty"`
	ItemName        string         `nbt:"item_name"`
	BlockName       string         `nbt:"block_name,omitempty"`
	Count           int16          `nbt:"count"`
	SelectionID     int64          `nbt:"selection_id"`
	Empty           bool           `nbt:"empty"`
	CanPlace        []string       `nbt:"can_place"`
	CanBreak        []string       `nbt:"can_break"`
	BlockingTick    int64          `nbt:"blocking_tick"`
}
type CreativeGroup struct {
	Category int32        `nbt:"category"`
	Name     string       `nbt:"name"`
	Icon     CreativeItem `nbt:"icon"`
}
type CreativeData struct {
	Groups []CreativeGroup `nbt:"groups"`
	Items  []CreativeItem  `nbt:"items"`
}

type Input struct {
	Name              string     `nbt:"name"`
	Meta              int32      `nbt:"meta"`
	Count             int32      `nbt:"count"`
	State             BlockState `nbt:"block,omitempty"`
	Tag               string     `nbt:"tag,omitempty"`
	DescriptorType    uint8      `nbt:"descriptor_type"`
	WireName          string     `nbt:"wire_name,omitempty"`
	Expression        string     `nbt:"expression,omitempty"`
	ExpressionVersion int16      `nbt:"expression_version,omitempty"`
}
type Output struct {
	Name         string         `nbt:"name"`
	Meta         int32          `nbt:"meta"`
	Count        int16          `nbt:"count"`
	State        BlockState     `nbt:"block,omitempty"`
	NBTData      map[string]any `nbt:"data,omitempty"`
	Empty        bool           `nbt:"empty"`
	CanPlace     []string       `nbt:"can_place"`
	CanBreak     []string       `nbt:"can_break"`
	BlockingTick int64          `nbt:"blocking_tick"`
}
type Unlock struct {
	Present     bool    `nbt:"present"`
	Context     int32   `nbt:"context"`
	Ingredients []Input `nbt:"ingredients"`
}

// Recipe combines the existing loader fields and the separate packet metadata
// after decoding. Existing Dragonfly loaders read only the core recipe files;
// adding these assets does not implement extra wire features in gameplay.
type Recipe struct {
	Input          []Input  `nbt:"input"`
	Output         []Output `nbt:"output"`
	Block          string   `nbt:"block"`
	Priority       int32    `nbt:"priority"`
	Width          int32    `nbt:"width"`
	Height         int32    `nbt:"height"`
	ID             string   `nbt:"id"`
	UUID           string   `nbt:"uuid"`
	NetworkID      int64    `nbt:"network_id"`
	AssumeSymmetry bool     `nbt:"assume_symmetry"`
	Unlock         Unlock   `nbt:"unlock"`
}
type CraftingData struct {
	Shaped             []Recipe `nbt:"shaped"`
	Shapeless          []Recipe `nbt:"shapeless"`
	UserDataShapeless  []Recipe `nbt:"shulker_box"`
	Multi              []string `nbt:"multi"`
	MultiNetworkIDs    []int64  `nbt:"multi_network_ids"`
	ShapedChemistry    []Recipe `nbt:"shaped_chemistry"`
	ShapelessChemistry []Recipe `nbt:"shapeless_chemistry"`
	ClearRecipes       bool     `nbt:"clear_recipes"`
}
type PotionRecipe struct {
	Input   Input  `nbt:"input"`
	Reagent Input  `nbt:"reagent"`
	Output  Output `nbt:"output"`
}
type ContainerChange struct {
	Input   string `nbt:"input"`
	Reagent Input  `nbt:"reagent"`
	Output  string `nbt:"output"`
}
type PotionData struct {
	Potions          []PotionRecipe    `nbt:"potions"`
	ContainerChanges []ContainerChange `nbt:"container_changes"`
}

// BiomeData is a typed NBT projection of the captured modern biome fields. It
// preserves the shared string dictionary and optional-tag presence. No captured
// definition contains client-side chunk generation; conversion rejects it.
type Biome struct {
	NameIndex      int16   `nbt:"name_index"`
	ID             int16   `nbt:"id"`
	Temperature    float32 `nbt:"temperature"`
	Downfall       float32 `nbt:"downfall"`
	FoliageSnow    float32 `nbt:"foliage_snow"`
	Depth          float32 `nbt:"depth"`
	Scale          float32 `nbt:"scale"`
	MapWaterColour int32   `nbt:"map_water_colour"`
	Rain           bool    `nbt:"rain"`
	TagsPresent    bool    `nbt:"tags_present"`
	Tags           []int32 `nbt:"tags"`
}
type BiomeData struct {
	Definitions []Biome  `nbt:"definitions"`
	Strings     []string `nbt:"strings"`
}
type Data struct {
	Creative     CreativeData
	Crafting     CraftingData
	Smithing     []Recipe
	SmithingTrim []Recipe
	Potions      PotionData
	Biomes       BiomeData
}
