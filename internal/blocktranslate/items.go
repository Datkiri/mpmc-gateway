package blocktranslate

import (
	"fmt"
	"sort"
	"strings"
	"sync"

	"github.com/df-mc/worldupgrader/itemupgrader"
	v685 "github.com/shawtymarco/go-multiversion/data/v685"
	v712 "github.com/shawtymarco/go-multiversion/data/v712"
	v729 "github.com/shawtymarco/go-multiversion/data/v729"
	v748 "github.com/shawtymarco/go-multiversion/data/v748"
	v766 "github.com/shawtymarco/go-multiversion/data/v766"
	v776 "github.com/shawtymarco/go-multiversion/data/v776"
	v786 "github.com/shawtymarco/go-multiversion/data/v786"
	v800 "github.com/shawtymarco/go-multiversion/data/v800"
	v818 "github.com/shawtymarco/go-multiversion/data/v818"
	v827 "github.com/shawtymarco/go-multiversion/data/v827"
	v844 "github.com/shawtymarco/go-multiversion/data/v844"
	v859 "github.com/shawtymarco/go-multiversion/data/v859"
	v898 "github.com/shawtymarco/go-multiversion/data/v898"
	v924 "github.com/shawtymarco/go-multiversion/data/v924"
	v944 "github.com/shawtymarco/go-multiversion/data/v944"
	v975 "github.com/shawtymarco/go-multiversion/data/v975"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
	v1001 "github.com/shawtymarco/go-multiversion/data/v1001"
	v2169 "github.com/shawtymarco/go-multiversion/data/v2169"
	v2193 "github.com/shawtymarco/go-multiversion/data/v2193"
	v2225 "github.com/shawtymarco/go-multiversion/data/v2225"
	"github.com/shawtymarco/go-multiversion/mapping"
)

// ItemRegistry is per-protocol — same principle as the block palette.
//
// ★ Why this is needed: the listener sends the client an `ItemRegistry` taken from the HOST's
// `GameData.Items`. A client on a different protocol then receives the wrong item table, so item
// network IDs drift (2225 has 2080 items, 2193 has 2076, 1001 has 1933, 2169 has 1976) — holding,
// placing or dropping items renders wrong or vanishes.
// Amber (`ItemRegistryPacket::create($typeConverter->getItemTypeDictionary()->getEntries())`) and
// Ouranos (`ItemTypeDictionary.getInstance(downstreamProtocolId)`) both REPLACE the client-side
// registry with the client protocol's own table.
//
// Provenance and — the important part — consistency:
//   - 2225 is the native table (go-multiversion data/v2225 vanilla_items.nbt, the snapshot the
//     v1_26_50+ adapters use). It is also the NATIVE table the go-multiversion adapters expect
//     as input.
//   - 1001, 2169 and 2193 are built from the very same `go-multiversion/data/v*` `vanilla_items.nbt`
//     snapshots that `protocols/v1_26_30`, `/v1_26_45` and `/v1_26_50` feed into
//     `mapping.NewItemMapper(...).TargetEntries()`. Using the exact same constructor here means
//     `Items(id)` is the same table the registry-aware codec emits for that protocol, instead of
//     a second, independently pinned copy that could drift.
//   - 2168 shares 2169's table. This is not a guess: `protocols/v1_26_44.NewWithRegistries`
//     delegates to `v1_26_45.NewWithRegistries`, documented as "the shared 1.26.40–1.26.45
//     registry baseline". There is no `data/v2168` package because the family is one snapshot.
//
// Caller contract (see `cmd/xvrelay/main.go`): a registry-AWARE client codec translates
// `ItemRegistry` itself and expects the NATIVE (2225) table in GameData.Items; a registry-LESS
// codec passes GameData.Items straight through and must be handed the client's OWN table.
var itemCatalog struct {
	sync.Once
	items map[int32][]protocol.ItemEntry
	err   error
}

// targetItems converts one dragonfly item snapshot into the mapping.TargetItem shape expected by
// mapping.NewItemMapper. All data packages alias v1001.ItemEntry, so one concrete type is enough.
func targetItems(src map[string]v1001.ItemEntry) map[string]mapping.TargetItem {
	out := make(map[string]mapping.TargetItem, len(src))
	for name, item := range src {
		out[name] = mapping.TargetItem{
			RuntimeID:      item.RuntimeID,
			ComponentBased: item.ComponentBased,
			Version:        item.Version,
			Data:           item.Data,
		}
	}
	return out
}

// nativeEntries turns the identifier-keyed snapshot into the sorted []protocol.ItemEntry the
// codecs expect as their input. Sorting by RuntimeID keeps the result deterministic.
func nativeEntries(src map[string]v1001.ItemEntry) []protocol.ItemEntry {
	out := make([]protocol.ItemEntry, 0, len(src))
	for name, item := range src {
		out = append(out, protocol.ItemEntry{
			Name:           name,
			RuntimeID:      int16(item.RuntimeID),
			ComponentBased: item.ComponentBased,
			Version:        item.Version,
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].RuntimeID < out[j].RuntimeID })
	return out
}

func historicalTargetItems(id int32) (target map[string]mapping.TargetItem, err error) {
	target = map[string]mapping.TargetItem{}
	switch id {
	case 685, 712, 729:
		var src map[string]int32
		switch id {
		case 685:
			src, err = v685.Items()
		case 712:
			src, err = v712.Items()
		default:
			src, err = v729.Items()
		}
		for name, runtimeID := range src {
			target[name] = mapping.TargetItem{RuntimeID: runtimeID}
		}
	case 776:
		var src map[string]v776.ItemEntry
		src, err = v776.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 786:
		var src map[string]v786.ItemEntry
		src, err = v786.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 800:
		var src map[string]v800.ItemEntry
		src, err = v800.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 818:
		var src map[string]v818.ItemEntry
		src, err = v818.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 859:
		var src map[string]v859.ItemEntry
		src, err = v859.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 748, 766:
		var src map[string]int32
		if id == 748 {
			src, err = v748.Items()
		} else {
			src, err = v766.Items()
		}
		for name, runtimeID := range src {
			target[name] = mapping.TargetItem{RuntimeID: runtimeID}
		}
	case 827:
		var src map[string]v827.ItemEntry
		src, err = v827.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 844:
		var src map[string]v844.ItemEntry
		src, err = v844.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 898:
		var src map[string]v898.ItemEntry
		src, err = v898.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 924:
		var src map[string]v924.ItemEntry
		src, err = v924.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 944:
		var src map[string]v944.ItemEntry
		src, err = v944.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	case 975:
		var src map[string]v975.ItemEntry
		src, err = v975.Items()
		for name, s := range src {
			target[name] = mapping.TargetItem{RuntimeID: s.RuntimeID, ComponentBased: s.ComponentBased, Version: s.Version, Data: s.Data}
		}
	default:
		return nil, fmt.Errorf("unsupported historical item table %d", id)
	}
	return target, err
}

// Match each pinned codec's resolver and target-only policy exactly.
func historicalItemMapper(id int32, native []protocol.ItemEntry, target map[string]mapping.TargetItem) (*mapping.ItemMapper, error) {
	resolve := func(name string) string {
		name = itemupgrader.Upgrade(itemupgrader.ItemMeta{Name: name}).Name
		if strings.HasPrefix(name, "minecraft:item.") {
			name = "minecraft:" + strings.TrimPrefix(name, "minecraft:item.")
		}
		if name == "minecraft:reeds" {
			name = "minecraft:sugar_cane"
		}
		if (id == 685 || id == 712 || id == 729 || id == 748 || id == 766 || id == 776 || id == 786 || id == 800 || id == 818 || id == 827 || id == 859) && name == "minecraft:chain" {
			name = "minecraft:iron_chain"
		}
		return name
	}
	switch id {
	case 685, 712, 729, 748, 766, 776, 786, 800, 818, 859, 898, 924, 944:
		return mapping.NewItemMapperAllowingTargetOnly(native, target, resolve)
	default:
		return mapping.NewItemMapperWithResolver(native, target, resolve)
	}
}

func loadItems() {
	itemCatalog.items = map[int32][]protocol.ItemEntry{}

	// 1. Native (2225) table — also the input the adapters translate FROM.
	nativeMap, err := v2225.Items()
	if err != nil {
		itemCatalog.err = fmt.Errorf("v2225 item snapshot: %w", err)
		return
	}
	native := nativeEntries(nativeMap)
	itemCatalog.items[NativeProtocol] = native

	// 2. Historical tables, built by the same mapper the codecs use.
	build := func(id int32, src map[string]v1001.ItemEntry) ([]protocol.ItemEntry, error) {
		m, err := mapping.NewItemMapper(native, targetItems(src))
		if err != nil {
			return nil, fmt.Errorf("build %d item registry: %w", id, err)
		}
		return m.TargetEntries(), nil
	}

	items1001, err := v1001.Items()
	if err != nil {
		itemCatalog.err = fmt.Errorf("v1001 item snapshot: %w", err)
		return
	}
	entries1001, err := build(1001, items1001)
	if err != nil {
		itemCatalog.err = err
		return
	}
	itemCatalog.items[1001] = entries1001

	items2169, err := v2169.Items()
	if err != nil {
		itemCatalog.err = fmt.Errorf("v2169 item snapshot: %w", err)
		return
	}
	entries2169, err := build(2169, items2169)
	if err != nil {
		itemCatalog.err = err
		return
	}
	itemCatalog.items[2169] = entries2169
	itemCatalog.items[2168] = entries2169

	items2193, err := v2193.Items()
	if err != nil {
		itemCatalog.err = fmt.Errorf("v2193 item snapshot: %w", err)
		return
	}
	entries2193, err := build(2193, items2193)
	if err != nil {
		itemCatalog.err = err
		return
	}
	itemCatalog.items[2193] = entries2193
	for _, id := range []int32{685, 712, 729, 748, 766, 776, 786, 800, 818, 827, 844, 859, 898, 924, 944, 975} {
		target, err := historicalTargetItems(id)
		if err != nil {
			itemCatalog.err = fmt.Errorf("protocol%d item snapshot: %w", id, err)
			return
		}
		m, err := historicalItemMapper(id, native, target)
		if err != nil {
			itemCatalog.err = fmt.Errorf("build %d item registry: %w", id, err)
			return
		}
		itemCatalog.items[id] = m.TargetEntries()
	}
}

// Items returns the item registry advertised to a client of protocol id. It errors when no table
// exists for that protocol, so the caller keeps the host's registry instead of inventing one.
func Items(id int32) ([]protocol.ItemEntry, error) {
	itemCatalog.Do(loadItems)
	if itemCatalog.err != nil {
		return nil, itemCatalog.err
	}
	items, ok := itemCatalog.items[id]
	if !ok || len(items) == 0 {
		return nil, fmt.Errorf("no item registry for protocol %d", id)
	}
	return items, nil
}

// NativeItems is the 2225 table the registry-aware codecs expect as their INPUT. Keeping it as a
// named helper makes the caller's intent explicit and avoids a magic number at the call site.
func NativeItems() ([]protocol.ItemEntry, error) { return Items(NativeProtocol) }
