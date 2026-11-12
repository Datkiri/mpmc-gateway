package main

import (
	"testing"

	"bedrock-cross-version-relay/internal/blocktranslate"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

// These five IDs were measured on a live 2193 client through a BDS 1.26.33.1
// (proto 1001) host: CreativeContent carried host IDs that the client's own
// 2076-entry table does not contain. The same names DO exist natively, with
// different IDs, so the relay must map them by name.
func TestMapHostCreative1001UsesNativeIDs(t *testing.T) {
	host, err := blocktranslate.Items(1001)
	if err != nil {
		t.Fatal(err)
	}
	stack := func(id int32) protocol.ItemStack {
		return protocol.ItemStack{ItemType: protocol.ItemType{NetworkID: id}}
	}
	pk := &packet.CreativeContent{
		Groups: []protocol.CreativeGroup{{Icon: stack(716)}},
		Items: []protocol.CreativeItem{
			{Item: stack(257)},   // copper_spear
			{Item: stack(427)},   // black_dye
			{Item: stack(716)},   // prize_pottery_sherd
			{Item: stack(717)},   // scrape_pottery_sherd
			{Item: stack(719)},   // shelter_pottery_sherd
			{Item: stack(0)},     // legitimate empty entry
			{Item: stack(99999)}, // ID absent from the host registry
		},
	}
	out, st, err := mapHostCreative1001(pk, host)
	if err != nil {
		t.Fatal(err)
	}
	// The expected IDs come from the NATIVE table (2225), not a pinned 2193 snapshot: the
	// relay resolves host IDs by name through blocktranslate.NativeItems().
	natives, err := blocktranslate.NativeItems()
	if err != nil {
		t.Fatal(err)
	}
	nativeID := make(map[string]int32, len(natives))
	for _, e := range natives {
		nativeID[e.Name] = int32(e.RuntimeID)
	}
	wantNames := []string{
		"minecraft:copper_spear", "minecraft:black_dye", "minecraft:prize_pottery_sherd",
		"minecraft:scrape_pottery_sherd", "minecraft:shelter_pottery_sherd", "",
	}
	want := make([]int32, len(wantNames))
	for i, name := range wantNames {
		if name == "" {
			continue
		}
		id, ok := nativeID[name]
		if !ok {
			t.Fatalf("%s missing from the native 2225 item table", name)
		}
		want[i] = id
	}
	if len(out.Items) != len(want) {
		t.Fatalf("got %d creative items, want %d (one unresolvable ID must be dropped)", len(out.Items), len(want))
	}
	for i, id := range want {
		if out.Items[i].Item.NetworkID != id {
			t.Errorf("item %d: got native id %d, want %d", i, out.Items[i].Item.NetworkID, id)
		}
		if id != 0 && out.Items[i].Item.NetworkID == []int32{257, 427, 716, 717, 719, 0}[i] {
			t.Errorf("item %d still carries the host ID", i)
		}
	}
	if wantIcon := nativeID["minecraft:prize_pottery_sherd"]; out.Groups[0].Icon.NetworkID != wantIcon {
		t.Errorf("group icon native id=%d, want %d", out.Groups[0].Icon.NetworkID, wantIcon)
	}
	if st.mapped != 6 || st.dropped != 0 || st.unknown != 1 {
		t.Errorf("stats=%+v, want mapped=6 dropped=0 unknown=1", st)
	}
	// The original packet must not be mutated (the relay may still hold it).
	if pk.Items[0].Item.NetworkID != 257 {
		t.Fatal("mapHostCreative1001 mutated its input")
	}
}

func TestMapHostCreative1001DropsAddonOnlyItems(t *testing.T) {
	host := []protocol.ItemEntry{{Name: "dl:copper_lantern", RuntimeID: 900}}
	pk := &packet.CreativeContent{Items: []protocol.CreativeItem{
		{Item: protocol.ItemStack{ItemType: protocol.ItemType{NetworkID: 900}}},
	}}
	out, st, err := mapHostCreative1001(pk, host)
	if err != nil {
		t.Fatal(err)
	}
	if len(out.Items) != 0 || st.dropped != 1 {
		t.Fatalf("addon item must be dropped: items=%d stats=%+v", len(out.Items), st)
	}
}
