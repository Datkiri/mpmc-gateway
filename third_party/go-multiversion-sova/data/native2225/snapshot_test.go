package native2225_test

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func read(t *testing.T, path string) []byte {
	t.Helper()
	b, e := os.ReadFile(path)
	if e != nil {
		t.Fatal(e)
	}
	return b
}

func TestSnapshotFileIntegrity(t *testing.T) {
	var files []struct {
		Path   string
		Bytes  int
		SHA256 string
	}
	if err := json.Unmarshal(read(t, "files.json"), &files); err != nil {
		t.Fatal(err)
	}
	if len(files) < 10 {
		t.Fatal("incomplete source manifest")
	}
	for _, f := range files {
		t.Run(f.Path, func(t *testing.T) {
			if !filepath.IsLocal(f.Path) {
				t.Fatal("nonlocal manifest path")
			}
			b := read(t, f.Path)
			hash := sha256.Sum256(b)
			if len(b) != f.Bytes || hex.EncodeToString(hash[:]) != f.SHA256 {
				t.Fatal("source bytes changed")
			}
		})
	}
}

func TestPortableExtractionSourceLocks(t *testing.T) {
	var source struct {
		ToolHashes []struct {
			Path   string
			SHA256 string
		} `json:"tool_hashes"`
	}
	if err := json.Unmarshal(read(t, "source.json"), &source); err != nil {
		t.Fatal(err)
	}
	if len(source.ToolHashes) == 0 {
		t.Fatal("missing extractor source locks")
	}
	for _, file := range source.ToolHashes {
		if strings.Contains(file.Path, `\`) || !filepath.IsLocal(file.Path) {
			t.Fatalf("nonportable source path %q", file.Path)
		}
		if digest, err := hex.DecodeString(file.SHA256); err != nil || len(digest) != sha256.Size {
			t.Fatal("invalid source digest")
		}
		data, err := os.ReadFile(filepath.Join("../..", file.Path))
		// The extractor is a separate Go module, excluded from the library's
		// module zip. Check its bytes when testing a complete source checkout.
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		hash := sha256.Sum256(data)
		if hex.EncodeToString(hash[:]) != file.SHA256 {
			t.Fatalf("extractor source changed: %s", file.Path)
		}
	}
}

func states(t *testing.T, path string) map[string]map[string]any {
	t.Helper()
	buf := bytes.NewBuffer(read(t, path))
	d := nbt.NewDecoder(buf)
	result := map[string]map[string]any{}
	for buf.Len() > 0 {
		var s map[string]any
		if e := d.Decode(&s); e != nil {
			t.Fatal(e)
		}
		key, e := json.Marshal([]any{s["name"], s["states"]})
		if e != nil {
			t.Fatal(e)
		}
		if _, ok := result[string(key)]; ok {
			t.Fatal("duplicate state")
		}
		result[string(key)] = s
		b, e := nbt.Marshal(s)
		if e != nil {
			t.Fatal(e)
		}
		var back map[string]any
		if e := nbt.Unmarshal(b, &back); e != nil || !reflect.DeepEqual(s, back) {
			t.Fatal("NBT type/value changed")
		}
	}
	return result
}

func TestExtractedBlockStatesMatchTypedPreviousSnapshot(t *testing.T) {
	current := states(t, "block_states.nbt")
	previous := states(t, "../native2223/raw/canonical_block_states.nbt")
	if len(current) != 23960 || !reflect.DeepEqual(current, previous) {
		t.Fatal(".30/.29 palette equality no longer established")
	}
	for _, key := range []string{`["minecraft:air",{}]`, `["minecraft:stone",{}]`, `["minecraft:structure_void",{}]`, `["minecraft:chalkboard",{"direction":15}]`} {
		if _, ok := current[key]; !ok {
			t.Fatalf("missing anchor %s", key)
		}
	}
}

func TestPacketCaptureRoundTripsWithoutMutation(t *testing.T) {
	factories := map[string]func() packet.Packet{
		"item_registry": func() packet.Packet { return &packet.ItemRegistry{} }, "creative_content": func() packet.Packet { return &packet.CreativeContent{} },
		"crafting_data": func() packet.Packet { return &packet.CraftingData{} }, "biome_definitions": func() packet.Packet { return &packet.BiomeDefinitionList{} },
		"entity_identifiers": func() packet.Packet { return &packet.AvailableActorIdentifiers{} }, "start_game": func() packet.Packet { return &packet.StartGame{} },
	}
	decode := func(b []byte, f func() packet.Packet) packet.Packet {
		p := f()
		buf := bytes.NewBuffer(b)
		p.Marshal(protocol.NewReader(buf, 358, false))
		if buf.Len() != 0 {
			t.Fatalf("packet %d has %d unread bytes", p.ID(), buf.Len())
		}
		return p
	}
	for name, f := range factories {
		t.Run(name, func(t *testing.T) {
			raw := read(t, "raw/"+name+".bin")
			p, original := decode(raw, f), decode(raw, f)
			var b bytes.Buffer
			p.Marshal(protocol.NewWriter(&b, 358))
			if !reflect.DeepEqual(p, original) {
				t.Fatal("serialization mutated input")
			}
			if !reflect.DeepEqual(p, decode(b.Bytes(), f)) {
				t.Fatal("packet type/value changed")
			}
		})
	}
}

func TestItemSnapshotMatchesCapturedRegistry(t *testing.T) {
	var m map[string]struct {
		RuntimeID      int32          `nbt:"runtime_id"`
		ComponentBased bool           `nbt:"component_based"`
		Version        int32          `nbt:"version"`
		Data           map[string]any `nbt:"data"`
	}
	if e := nbt.Unmarshal(read(t, "vanilla_items.nbt"), &m); e != nil {
		t.Fatal(e)
	}
	p := new(packet.ItemRegistry)
	p.Marshal(protocol.NewReader(bytes.NewBuffer(read(t, "raw/item_registry.bin")), 0, false))
	if len(m) != 2080 || len(p.Items) != len(m) {
		t.Fatal("item registry size mismatch")
	}
	ids := map[int32]bool{}
	for _, i := range p.Items {
		v, ok := m[i.Name]
		if !ok || v.RuntimeID != int32(i.RuntimeID) || v.ComponentBased != i.ComponentBased || v.Version != i.Version || !reflect.DeepEqual(v.Data, i.Data) {
			t.Fatalf("item metadata changed for %s", i.Name)
		}
		if ids[v.RuntimeID] {
			t.Fatal("duplicate item ID")
		}
		ids[v.RuntimeID] = true
	}
	if m["minecraft:air"].RuntimeID != -158 || m["minecraft:stone"].RuntimeID != 1 || m["minecraft:shield"].RuntimeID != 358 || m["minecraft:poplar_log"].RuntimeID != -1132 {
		t.Fatal("item anchor mismatch")
	}
}

func TestStandaloneAndEmbeddedNBT(t *testing.T) {
	for _, path := range []string{"data_driven_blocks.nbt", "raw/entity_identifiers.bin"} {
		t.Run(path, func(t *testing.T) {
			buf := bytes.NewBuffer(read(t, path))
			var value map[string]any
			if err := nbt.NewDecoder(buf).Decode(&value); err != nil {
				t.Fatal(err)
			}
			if buf.Len() != 0 {
				t.Fatal("unread NBT bytes")
			}
			encoded, err := nbt.Marshal(value)
			if err != nil {
				t.Fatal(err)
			}
			var decoded map[string]any
			if err := nbt.Unmarshal(encoded, &decoded); err != nil || !reflect.DeepEqual(value, decoded) {
				t.Fatal("NBT type/value changed")
			}
			if path == "data_driven_blocks.nbt" && len(value) != 98 {
				t.Fatal("data-driven block catalogue changed")
			}
		})
	}
}
