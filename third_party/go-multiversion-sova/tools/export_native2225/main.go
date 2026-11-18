// Command export_native2225 installs the verified .30 snapshots into Dragonfly's
// existing loader formats. Block file order is local: NativeProtocol must enable
// hashed network IDs; these sorted records are not vanilla numeric runtime IDs.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"slices"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/shawtymarco/go-multiversion/data/native2225"
	format "github.com/shawtymarco/go-multiversion/internal/native2225"
)

func main() {
	out := flag.String("out", "", "existing Dragonfly checkout")
	flag.Parse()
	if *out == "" {
		panic("-out is required")
	}
	if err := export(*out); err != nil {
		panic(err)
	}
}

func export(root string) error {
	files := map[string]string{
		"block_states.nbt": "server/world/block_states.nbt", "vanilla_items.nbt": "server/world/vanilla_items.nbt",
		"creative_items.nbt": "server/item/creative/creative_items.nbt", "crafting_data.nbt": "server/item/recipe/crafting_data.nbt",
		"smithing_data.nbt": "server/item/recipe/smithing_data.nbt", "smithing_trim_data.nbt": "server/item/recipe/smithing_trim_data.nbt",
		"potion_data.nbt": "server/item/recipe/potion_data.nbt", "item_tags.json": "server/item/recipe/item_tags.json",
		"data_driven_blocks.nbt": "server/world/data_driven_blocks.nbt",
	}
	paths := make([]string, 0, len(files))
	for source := range files {
		paths = append(paths, source)
	}
	slices.Sort(paths)
	outputs := make(map[string][]byte, len(files))
	type entry struct {
		Source, Destination, SourceSHA256, SHA256 string
		Bytes                                     int
	}
	var entries []entry
	for _, source := range paths {
		data, err := native2225.RawSnapshot(source)
		if err != nil {
			return err
		}
		original := digest(data)
		if source == "data_driven_blocks.nbt" {
			var definitions map[string]map[string]any
			if err := nbt.Unmarshal(data, &definitions); err != nil {
				return err
			}
			names := make([]string, 0, len(definitions))
			for name := range definitions {
				names = append(names, name)
			}
			slices.Sort(names)
			type block struct {
				Name       string         `nbt:"name"`
				Components map[string]any `nbt:"components"`
			}
			wrapped := struct {
				Blocks []block `nbt:"blocks"`
			}{}
			for _, name := range names {
				wrapped.Blocks = append(wrapped.Blocks, block{name, definitions[name]})
			}
			data, err = format.MarshalEncoding(wrapped, nbt.LittleEndian)
			if err != nil {
				return err
			}
		}
		path := filepath.Join(root, filepath.FromSlash(files[source]))
		if _, err := os.Stat(path); err != nil {
			return fmt.Errorf("expected existing loader asset: %w", err)
		}
		outputs[path] = data
		entries = append(entries, entry{source, files[source], original, digest(data), len(data)})
	}
	for path, data := range outputs {
		if err := os.WriteFile(path, data, 0644); err != nil {
			return err
		}
	}
	manifest, err := json.MarshalIndent(map[string]any{
		"version": "1.26.60.30", "protocol": 2225,
		"source_repository":  "https://github.com/shawtymarco/go-multiversion",
		"source_data_commit": "07388dcd962bb24eb7de996b0db26844e4404e38",
		"source_directory":   "data/native2225", "block_network_ids": "hashes; source file indexes are local only",
		"files": entries,
	}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(root, "server/world/native_snapshot.json"), append(manifest, '\n'), 0644)
}

func digest(data []byte) string { sum := sha256.Sum256(data); return hex.EncodeToString(sum[:]) }
