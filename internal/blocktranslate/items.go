package blocktranslate

import (
	"fmt"
	"sort"
	"sync"

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
