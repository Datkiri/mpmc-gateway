package native2225

import (
	"bytes"
	"reflect"
	"testing"

	"github.com/google/uuid"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func testCodec(t *testing.T) *Codec {
	t.Helper()
	c, err := New(map[string]ItemEntry{"minecraft:air": {RuntimeID: -158}, "minecraft:stone": {RuntimeID: 1}}, []BlockState{{Name: "minecraft:stone", Properties: map[string]any{"zero": uint8(0)}, Version: 18168865}})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestZeroValuedBlockPropertiesSurviveCanonicalNBT(t *testing.T) {
	x := CreativeItem{BlockProperties: map[string]any{"zero": uint8(0)}}
	first, err := Marshal(x)
	if err != nil {
		t.Fatal(err)
	}
	for range 8 {
		b, err := Marshal(x)
		if err != nil || !bytes.Equal(first, b) {
			t.Fatal("unstable canonical encoding")
		}
	}
	back, err := Decode[CreativeItem](first)
	if err != nil || !reflect.DeepEqual(x.BlockProperties, back.BlockProperties) {
		t.Fatal("omitempty dropped a false/zero block state")
	}
}

func TestRecipeMetadataAndInputImmutability(t *testing.T) {
	c := testCodec(t)
	state := BlockState{Name: "minecraft:stone", Properties: map[string]any{"zero": uint8(0)}}
	pk := &packet.CraftingData{ClearRecipes: true, ShapedRecipes: []protocol.ShapedRecipe{{RecipeID: "test", Width: 1, Height: 1, UUID: uuid.New(), Block: "crafting_table", Priority: 3, AssumeSymmetry: false, RecipeNetworkID: 123,
		Input:             []protocol.ItemDescriptorCount{{Descriptor: &protocol.DefaultItemDescriptor{Name: "minecraft:stone:0"}, Count: 1}},
		Output:            []protocol.ItemStack{{ItemType: protocol.ItemType{NetworkID: 1}, Count: 2, BlockRuntimeID: int32(c.hashes[stateKey(state)]), NBTData: map[string]any{"nested": map[string]any{"byte": uint8(1)}, "bytes": []byte{1, 2}}, CanBePlacedOn: []string{"minecraft:stone"}, CanBreak: []string{"minecraft:stone"}}},
		UnlockRequirement: protocol.Option(protocol.RecipeUnlockRequirement{Context: protocol.RecipeUnlockContextNone, Ingredients: []protocol.ItemDescriptorCount{{Descriptor: &protocol.ItemTagItemDescriptor{Tag: "minecraft:stone"}, Count: 2}}}),
	}}}
	var originalBytes bytes.Buffer
	pk.Marshal(protocol.NewWriter(&originalBytes, 0))
	original := new(packet.CraftingData)
	original.Marshal(protocol.NewReader(bytes.NewBuffer(originalBytes.Bytes()), 0, false))
	pk = new(packet.CraftingData)
	pk.Marshal(protocol.NewReader(bytes.NewBuffer(originalBytes.Bytes()), 0, false))
	d, err := c.Crafting(pk)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(pk, original) {
		t.Fatal("conversion mutated source packet")
	}
	b, err := Marshal(d.Crafting)
	if err != nil {
		t.Fatal(err)
	}
	d.Crafting, err = Decode[CraftingData](b)
	if err != nil {
		t.Fatal(err)
	}
	restored, err := c.RestoreCrafting(d)
	if err != nil {
		t.Fatal(err)
	}
	var wire bytes.Buffer
	restored.Marshal(protocol.NewWriter(&wire, 0))
	back := new(packet.CraftingData)
	back.Marshal(protocol.NewReader(&wire, 0, false))
	if !reflect.DeepEqual(original, back) {
		t.Fatal("identity, asymmetric recipe, unlock or typed NBT changed")
	}
	d.Crafting.Shaped[0].Output[0].NBTData["nested"].(map[string]any)["byte"] = uint8(9)
	if pk.ShapedRecipes[0].Output[0].NBTData["nested"].(map[string]any)["byte"] != uint8(1) {
		t.Fatal("output aliases input NBT")
	}
}

func TestRejectUnresolvedOrUnrepresentedValues(t *testing.T) {
	c := testCodec(t)
	for _, name := range []string{"minecraft:missing", "minecraft:stone:4"} {
		if _, err := c.input(protocol.ItemDescriptorCount{Descriptor: &protocol.DefaultItemDescriptor{Name: name}, Count: 1}); err == nil {
			t.Fatalf("accepted unresolved ingredient %q", name)
		}
	}
	if _, err := c.output(protocol.ItemStack{ItemType: protocol.ItemType{NetworkID: 99}, Count: 1}); err == nil {
		t.Fatal("accepted unknown item ID")
	}
	if _, err := c.output(protocol.ItemStack{ItemType: protocol.ItemType{NetworkID: 1}, Count: 1, BlockRuntimeID: 7}); err == nil {
		t.Fatal("accepted unknown block hash")
	}
	if _, err := c.Crafting(&packet.CraftingData{MaterialReducers: []protocol.MaterialReducer{{}}}); err == nil {
		t.Fatal("silently dropped reducers")
	}
	if _, err := Biomes(&packet.BiomeDefinitionList{StringList: []string{"test"}, BiomeDefinitions: []protocol.BiomeDefinition{{ChunkGeneration: protocol.Option(protocol.BiomeChunkGeneration{})}}}); err == nil {
		t.Fatal("silently dropped generation data")
	}
}
