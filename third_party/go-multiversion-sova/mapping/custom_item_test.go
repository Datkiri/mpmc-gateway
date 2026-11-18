package mapping

import (
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"reflect"
	"testing"
)

func TestCustomItemsDoNotOverwriteVanillaOrAcceptChangedBackendIDs(t *testing.T) {
	data := map[string]any{"components": map[string]any{"item_properties": map[string]any{"minecraft:icon": map[string]any{"textures": map[string]any{"default": "example:menu"}}}}}
	custom := protocol.ItemEntry{Name: "example:menu", RuntimeID: 2, ComponentBased: true, Version: 1, Data: data}
	native := []protocol.ItemEntry{{Name: "minecraft:stone", RuntimeID: 1}, custom}
	target := map[string]TargetItem{"minecraft:stone": {RuntimeID: 2}}
	mapper, err := NewItemMapper(native, target)
	if err != nil {
		t.Fatal(err)
	}
	if id, ok := mapper.NativeToTarget(1); !ok || id != 2 {
		t.Fatal("vanilla mapping changed")
	}
	customID, ok := mapper.NativeToTarget(2)
	if !ok || customID == 2 {
		t.Fatal("custom item replaced vanilla")
	}
	if id, ok := mapper.TargetToNative(customID); !ok || id != 2 {
		t.Fatal("custom reverse mapping absent")
	}
	if len(target) != 1 {
		t.Fatal("historical snapshot mutated")
	}
	entries := mapper.RegistryEntries([]protocol.ItemEntry{custom}, 419)
	if len(entries) != 1 || entries[0].Name != custom.Name {
		t.Fatal("component-only update became a full registry")
	}
	if err := mapper.ValidateNativeEntries([]protocol.ItemEntry{custom}); err != nil {
		t.Fatal(err)
	}
	changed := custom
	changed.RuntimeID++
	if err := mapper.ValidateNativeEntries([]protocol.ItemEntry{changed}); err == nil {
		t.Fatal("another backend remapped shared state")
	}
	before := mapper.TargetEntries()
	entries[0].Data["components"].(map[string]any)["item_properties"].(map[string]any)["minecraft:icon"] = "modified"
	if !reflect.DeepEqual(before, mapper.TargetEntries()) {
		t.Fatal("target components were not isolated")
	}
	if _, ok := data["components"].(map[string]any)["item_properties"].(map[string]any)["minecraft:icon"].(map[string]any)["textures"]; !ok {
		t.Fatal("source data was mutated")
	}
}
