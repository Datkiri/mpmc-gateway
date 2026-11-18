package mapping

import (
	"fmt"
	"math"
	"sort"
	"strings"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
)

// appendCustomItemDefinitions extends a historical snapshot with non-vanilla,
// component-based items from the native server. Vanilla-only unknown items keep
// their existing fallback policy. Snapshots and native component data are immutable.
func appendCustomItemDefinitions(native []protocol.ItemEntry, target map[string]TargetItem) (map[string]TargetItem, map[string]struct{}, error) {
	extended := make(map[string]TargetItem, len(target))
	var next int32 = 1
	for name, item := range target {
		extended[name] = item
		next = max(next, item.RuntimeID+1)
	}
	var custom []protocol.ItemEntry
	names := make(map[string]struct{})
	for _, entry := range native {
		if !entry.ComponentBased || strings.HasPrefix(entry.Name, "minecraft:") || !strings.Contains(entry.Name, ":") {
			continue
		}
		if _, duplicate := names[entry.Name]; duplicate {
			return nil, nil, fmt.Errorf("duplicate custom item %s", entry.Name)
		}
		if _, conflict := target[entry.Name]; conflict {
			return nil, nil, fmt.Errorf("custom item %s conflicts with target registry", entry.Name)
		}
		names[entry.Name] = struct{}{}
		custom = append(custom, entry)
	}
	// Native custom IDs reflect server registration order. Preserve that order
	// while remaining independent of map iteration in the full native table.
	sort.Slice(custom, func(i, j int) bool {
		if custom[i].RuntimeID == custom[j].RuntimeID {
			return custom[i].Name < custom[j].Name
		}
		return custom[i].RuntimeID < custom[j].RuntimeID
	})
	for _, entry := range custom {
		if next > math.MaxInt16 {
			return nil, nil, fmt.Errorf("custom item %s exceeds target item ID space", entry.Name)
		}
		extended[entry.Name] = TargetItem{RuntimeID: next, ComponentBased: true, Version: entry.Version, Data: cloneItemProperties(entry.Data)}
		next++
	}
	return extended, names, nil
}

// ValidateNativeEntries accepts full registries and supplemental component lists
// only when they match this adapter's frozen registry. Never install a different
// backend's IDs into a mapper shared by other connections.
func (m *ItemMapper) ValidateNativeEntries(entries []protocol.ItemEntry) error {
	for _, entry := range entries {
		id, ok := m.nativeByName[entry.Name]
		if !ok || id != int32(entry.RuntimeID) {
			return fmt.Errorf("native item registry changed for %s", entry.Name)
		}
	}
	return nil
}

func (m *ItemMapper) HasCustomItems() bool { return m != nil && len(m.customNames) != 0 }

// RegistryEntries preserves the distinction between an initial full table and
// a subsequent custom-component update. Legacy codecs place identity in StartGame
// and components in ItemComponent; modern codecs carry both in ItemRegistry.
func (m *ItemMapper) RegistryEntries(source []protocol.ItemEntry, targetProtocol int32) []protocol.ItemEntry {
	if len(source) == 0 {
		return nil
	}
	requested := make(map[string]struct{}, len(source))
	partial := true
	for _, entry := range source {
		requested[entry.Name] = struct{}{}
		if _, ok := m.customNames[entry.Name]; !ok {
			partial = false
		}
	}
	entries := m.TargetEntries()
	out := make([]protocol.ItemEntry, 0, len(entries))
	for _, entry := range entries {
		if partial {
			if _, ok := requested[entry.Name]; !ok {
				continue
			}
		}
		if entry.ComponentBased && targetProtocol <= 766 {
			if entry.Data == nil {
				entry.Data = make(map[string]any)
			}
			entry.Data["id"] = int32(entry.RuntimeID)
			entry.Data["name"] = entry.Name
			if targetProtocol <= 486 {
				components, _ := entry.Data["components"].(map[string]any)
				properties, _ := components["item_properties"].(map[string]any)
				icon, _ := properties["minecraft:icon"].(map[string]any)
				textures, _ := icon["textures"].(map[string]any)
				if texture, ok := textures["default"].(string); ok {
					properties["minecraft:icon"] = map[string]any{"texture": texture}
				}
			}
		}
		out = append(out, entry)
	}
	return out
}

// CustomItemExperiments is the only data-driven experiment needed by the legacy
// item definitions. It does not enable unrelated current-native experiments.
func (m *ItemMapper) CustomItemExperiments() []protocol.ExperimentData {
	if !m.HasCustomItems() {
		return nil
	}
	return []protocol.ExperimentData{{Name: "data_driven_items", Enabled: true}}
}
