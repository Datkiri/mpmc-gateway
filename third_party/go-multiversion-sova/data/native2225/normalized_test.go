package native2225_test

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"reflect"
	"slices"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	data "github.com/shawtymarco/go-multiversion/data/native2225"
	legacy "github.com/shawtymarco/go-multiversion/data/v1001"
	format "github.com/shawtymarco/go-multiversion/internal/native2225"
)

func TestNormalizedFilesIntegrity(t *testing.T) {
	var report struct {
		Outputs []struct {
			Path   string
			Bytes  int
			SHA256 string
		}
	}
	if err := json.Unmarshal(read(t, "normalization.json"), &report); err != nil {
		t.Fatal(err)
	}
	if len(report.Outputs) != 9 {
		t.Fatal("incomplete NBT/tag normalization")
	}
	for _, f := range report.Outputs {
		b := read(t, f.Path)
		hash := sha256.Sum256(b)
		if len(b) != f.Bytes || hex.EncodeToString(hash[:]) != f.SHA256 {
			t.Fatalf("normalized bytes changed: %s", f.Path)
		}
	}
}

func TestTypedLoadersAndNativePacketReconstruction(t *testing.T) {
	items, err := data.Items()
	if err != nil {
		t.Fatal(err)
	}
	blocks, err := data.BlockStates()
	if err != nil {
		t.Fatal(err)
	}
	codec, err := format.New(items, blocks)
	if err != nil {
		t.Fatal(err)
	}
	var d format.Data
	d.Creative, err = data.Creative()
	if err != nil {
		t.Fatal(err)
	}
	d.Crafting, err = data.Crafting()
	if err != nil {
		t.Fatal(err)
	}
	d.Smithing, err = data.Smithing()
	if err != nil {
		t.Fatal(err)
	}
	d.SmithingTrim, err = data.SmithingTrim()
	if err != nil {
		t.Fatal(err)
	}
	d.Potions, err = data.Potions()
	if err != nil {
		t.Fatal(err)
	}
	d.Biomes, err = data.Biomes()
	if err != nil {
		t.Fatal(err)
	}
	counts := map[string][2]int{"groups": {len(d.Creative.Groups), 124}, "creative": {len(d.Creative.Items), 1980}, "shaped": {len(d.Crafting.Shaped), 1219}, "shapeless": {len(d.Crafting.Shapeless), 2068}, "user-data": {len(d.Crafting.UserDataShapeless), 1456}, "multi": {len(d.Crafting.Multi), 14}, "smithing": {len(d.Smithing), 12}, "trim": {len(d.SmithingTrim), 1}, "potions": {len(d.Potions.Potions), 210}, "containers": {len(d.Potions.ContainerChanges), 2}, "biomes": {len(d.Biomes.Definitions), 89}}
	for name, count := range counts {
		if count[0] != count[1] {
			t.Fatalf("%s count = %d, want %d", name, count[0], count[1])
		}
	}
	creative, err := codec.RestoreCreative(d.Creative)
	if err != nil {
		t.Fatal(err)
	}
	crafting, err := codec.RestoreCrafting(d)
	if err != nil {
		t.Fatal(err)
	}
	biomes, err := format.RestoreBiomes(d.Biomes)
	if err != nil {
		t.Fatal(err)
	}
	for name, restored := range map[string]packet.Packet{"creative_content": creative, "crafting_data": crafting, "biome_definitions": biomes} {
		t.Run(name, func(t *testing.T) {
			factory := minecraft.DefaultProtocol.Packets(false)[restored.ID()]
			original := factory()
			raw := bytes.NewBuffer(read(t, "raw/"+name+".bin"))
			original.Marshal(protocol.NewReader(raw, 358, false))
			if raw.Len() != 0 {
				t.Fatal("unread capture bytes")
			}
			var encoded bytes.Buffer
			restored.Marshal(protocol.NewWriter(&encoded, 358))
			back := factory()
			back.Marshal(protocol.NewReader(&encoded, 358, false))
			if encoded.Len() != 0 || !reflect.DeepEqual(original, back) {
				t.Fatal("NBT-to-native reconstruction changed captured fields")
			}
		})
	}
	stations := map[string]int{}
	for _, r := range d.Crafting.Shapeless {
		stations[r.Block]++
	}
	for station, want := range map[string]int{"furnace": 134, "blast_furnace": 62, "smoker": 9, "campfire": 9, "soul_campfire": 9} {
		if stations[station] != want {
			t.Fatalf("lost %s recipes: %d", station, stations[station])
		}
	}
	tags, err := data.ItemTags()
	if err != nil {
		t.Fatal(err)
	}
	if len(tags) != 77 || !slices.Contains(tags["minecraft:planks"], "minecraft:acacia_planks") {
		t.Fatal("item tags are not indexed by tag")
	}
	if _, err := data.EntityIdentifiers(); err != nil {
		t.Fatal(err)
	}
}

// These are the already-used historical loader types, independent of the new
// richer schema. Separate packet metadata must not alter these strict schemas.
func TestExistingLoaderSchemasAcceptConvertedAssets(t *testing.T) {
	var creative legacy.CreativeData
	var crafting legacy.CraftingData
	var potions legacy.PotionData
	var smithing, trim []legacy.ShapelessRecipe
	for path, value := range map[string]any{"creative_items.nbt": &creative, "crafting_data.nbt": &crafting, "potion_data.nbt": &potions, "smithing_data.nbt": &smithing, "smithing_trim_data.nbt": &trim} {
		buf := bytes.NewBuffer(read(t, path))
		if err := nbt.NewDecoder(buf).Decode(value); err != nil || buf.Len() != 0 {
			t.Fatalf("%s loader: %v, unread=%d", path, err, buf.Len())
		}
	}
	if len(creative.Items) != 1980 || len(crafting.Shaped) != 1219 || len(potions.Potions) != 210 || len(smithing) != 12 || len(trim) != 1 {
		t.Fatal("existing loader lost entries")
	}
	for _, r := range smithing {
		if len(r.Input) != 3 || len(r.Output) != 1 {
			t.Fatal("smithing input/output order/shape changed")
		}
	}
	for _, r := range trim {
		if len(r.Input) != 3 || len(r.Output) != 0 {
			t.Fatal("trim input/output shape changed")
		}
	}
	full, err := data.Creative()
	if err != nil {
		t.Fatal(err)
	}
	for i, x := range full.Items {
		if x.BlockName != "" {
			if x.Name != x.BlockName {
				t.Fatal("creative block state uses item name in the block lookup")
			}
		} else if x.Name != x.ItemName {
			t.Fatal("creative item identifier changed")
		}
		if creative.Items[i].Name != x.Name || creative.Items[i].Meta != x.Meta || creative.Items[i].GroupIndex != x.GroupIndex || !reflect.DeepEqual(creative.Items[i].BlockProperties, x.BlockProperties) || !reflect.DeepEqual(creative.Items[i].NBT, x.NBT) {
			t.Fatal("legacy creative loader projection differs")
		}
	}
	for _, r := range potions.Potions {
		if r.Input.Count != 1 || r.Reagent.Count != 1 || r.Output.Count != 1 {
			t.Fatal("potion loader counts changed")
		}
	}
}
