// Command normalize_native2225 converts the locked .30 source bundle to typed
// loader NBT, then reconstructs its native packets to reject semantic loss.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/sandertv/gophertunnel/minecraft"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"sort"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	format "github.com/shawtymarco/go-multiversion/internal/native2225"
)

type fileHash struct {
	Path   string `json:"path"`
	Bytes  int    `json:"bytes"`
	SHA256 string `json:"sha256"`
}

func check(e error) {
	if e != nil {
		panic(e)
	}
}
func read(p string) []byte { b, e := os.ReadFile(p); check(e); return b }
func hash(path string, b []byte) fileHash {
	h := sha256.Sum256(b)
	return fileHash{path, len(b), hex.EncodeToString(h[:])}
}
func decode[T packet.Packet](b []byte, p T, shield int32) T {
	buf := bytes.NewBuffer(b)
	p.Marshal(protocol.NewReader(buf, shield, false))
	if buf.Len() != 0 {
		panic(fmt.Sprintf("packet %d: %d unread bytes", p.ID(), buf.Len()))
	}
	return p
}
func verify(original, restored packet.Packet, shield int32) {
	var b bytes.Buffer
	restored.Marshal(protocol.NewWriter(&b, shield))
	factory, ok := minecraft.DefaultProtocol.Packets(false)[original.ID()]
	if !ok {
		panic("missing packet factory")
	}
	back := decode(b.Bytes(), factory(), shield)
	if !reflect.DeepEqual(original, back) {
		panic(fmt.Sprintf("NBT roundtrip lost fields of %T", original))
	}
}
func jsonBytes(v any) []byte {
	b, e := json.MarshalIndent(v, "", "  ")
	check(e)
	return append(b, '\n')
}

func main() {
	source := flag.String("source", "data/native2225", "verified acquisition bundle")
	out := flag.String("out", "", "new scratch output directory")
	flag.Parse()
	if *out == "" {
		panic("-out is required; source data is never overwritten")
	}
	if _, e := os.Stat(*out); !os.IsNotExist(e) {
		panic("output directory must not exist")
	}
	var identity struct {
		Target struct {
			GameVersion string `json:"game_version"`
			ProtocolID  int32  `json:"protocol_id"`
		} `json:"target"`
	}
	check(json.Unmarshal(read(filepath.Join(*source, "source.json")), &identity))
	if identity.Target.GameVersion != "1.26.60.30" || identity.Target.ProtocolID != 2225 {
		panic("source bundle is not the locked .30/protocol-2225 acquisition")
	}
	var files []fileHash
	check(json.Unmarshal(read(filepath.Join(*source, "files.json")), &files))
	for _, f := range files {
		if !filepath.IsLocal(f.Path) {
			panic("nonlocal source path")
		}
		if hash(f.Path, read(filepath.Join(*source, f.Path))) != f {
			panic("source integrity mismatch: " + f.Path)
		}
	}
	items, e := format.Decode[map[string]format.ItemEntry](read(filepath.Join(*source, "vanilla_items.nbt")))
	check(e)
	var states []format.BlockState
	buf := bytes.NewBuffer(read(filepath.Join(*source, "block_states.nbt")))
	decoder := nbt.NewDecoder(buf)
	for buf.Len() > 0 {
		var state format.BlockState
		check(decoder.Decode(&state))
		states = append(states, state)
	}
	codec, e := format.New(items, states)
	check(e)
	shield := items["minecraft:shield"].RuntimeID
	creative := decode(read(filepath.Join(*source, "raw/creative_content.bin")), new(packet.CreativeContent), shield)
	crafting := decode(read(filepath.Join(*source, "raw/crafting_data.bin")), new(packet.CraftingData), shield)
	biomes := decode(read(filepath.Join(*source, "raw/biome_definitions.bin")), new(packet.BiomeDefinitionList), shield)
	d, e := codec.Crafting(crafting)
	check(e)
	d.Creative, e = codec.Creative(creative)
	check(e)
	d.Biomes, e = format.Biomes(biomes)
	check(e)
	values := format.LoaderValues(d)
	outputs := map[string][]byte{}
	for name, value := range values {
		b, e := format.Marshal(value)
		check(e)
		again, e := format.Marshal(value)
		check(e)
		if !bytes.Equal(b, again) {
			panic("nondeterministic output")
		}
		outputs[name] = b
		fmt.Printf("%s: %d bytes\n", name, len(b))
	}
	// Read exactly the types exposed by the new data loader before restoring wire values.
	d.Creative, e = format.Decode[format.CreativeData](outputs["creative_items.nbt"])
	check(e)
	d.Crafting, e = format.Decode[format.CraftingData](outputs["crafting_data.nbt"])
	check(e)
	d.Smithing, e = format.Decode[[]format.Recipe](outputs["smithing_data.nbt"])
	check(e)
	d.SmithingTrim, e = format.Decode[[]format.Recipe](outputs["smithing_trim_data.nbt"])
	check(e)
	d.Potions, e = format.Decode[format.PotionData](outputs["potion_data.nbt"])
	check(e)
	d.Biomes, e = format.Decode[format.BiomeData](outputs["biome_definitions.nbt"])
	check(e)
	metadata, e := format.Decode[format.Metadata](outputs["packet_metadata.nbt"])
	check(e)
	check(format.ApplyMetadata(&d, metadata))
	restoredCreative, e := codec.RestoreCreative(d.Creative)
	check(e)
	verify(creative, restoredCreative, shield)
	restoredCrafting, e := codec.RestoreCrafting(d)
	check(e)
	verify(crafting, restoredCrafting, shield)
	restoredBiomes, e := format.RestoreBiomes(d.Biomes)
	check(e)
	verify(biomes, restoredBiomes, shield)
	entities := read(filepath.Join(*source, "raw/entity_identifiers.bin"))
	_, e = format.Decode[map[string]any](entities)
	check(e)
	outputs["entity_identifiers.nbt"] = entities
	var byItem map[string][]string
	check(json.Unmarshal(read(filepath.Join(*source, "raw/item_tags.json")), &byItem))
	byTag := map[string][]string{}
	for name, tags := range byItem {
		if _, ok := items[name]; !ok {
			panic("tag item absent from registry")
		}
		for _, tag := range tags {
			byTag[tag] = append(byTag[tag], name)
		}
	}
	for tag, names := range byTag {
		sort.Strings(names)
		byTag[tag] = slices.Compact(names)
	}
	outputs["item_tags.json"] = jsonBytes(byTag)
	blocks := map[string]int{}
	recipeTags := map[string]bool{}
	for _, rs := range [][]format.Recipe{d.Crafting.Shaped, d.Crafting.Shapeless, d.Crafting.UserDataShapeless, d.Smithing, d.SmithingTrim} {
		for _, r := range rs {
			blocks[r.Block]++
			for _, in := range append(slices.Clone(r.Input), r.Unlock.Ingredients...) {
				if in.Tag != "" {
					recipeTags[in.Tag] = true
					if len(byTag[in.Tag]) == 0 {
						panic("missing recipe tag: " + in.Tag)
					}
				}
			}
		}
	}
	checksums := []fileHash{}
	for name, b := range outputs {
		checksums = append(checksums, hash(name, b))
	}
	sort.Slice(checksums, func(i, j int) bool { return checksums[i].Path < checksums[j].Path })
	report := map[string]any{"schema_version": 1, "game_version": "1.26.60.30", "protocol_id": 2225, "encoding": "NetworkLittleEndian",
		"dragonfly_schema_commit": "92ccc8ebb41e46c83114ac1c8d31dd0d6249f0cf", "historical_schema": "data/v1001 (shared by v2169)",
		"outputs": checksums, "recipes_by_block": blocks, "item_tags": len(byTag), "referenced_recipe_tags": len(recipeTags),
		"packet_roundtrips": []string{"CreativeContent", "CraftingData", "BiomeDefinitionList"}, "wire_metadata_preserved": true,
		"additional_fields":          "packet_metadata.nbt preserves recipe identity/network IDs, symmetry, unlock presence/ingredients, creative selection IDs/counts and block/item names without adding unknown fields to existing loader schemas",
		"runtime_registry_installed": false, "new_furnace_capture_required": false,
		"source_files": files}
	outputs["normalization.json"] = jsonBytes(report)
	check(os.MkdirAll(*out, 0755))
	for name, b := range outputs {
		check(os.WriteFile(filepath.Join(*out, name), b, 0644))
	}
	fmt.Printf("Validated %d NBT assets and %d recipe tags; native packet roundtrips preserved\n", len(values)+1, len(byTag))
}
