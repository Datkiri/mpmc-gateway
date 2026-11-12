package blocktranslate

import (
	"testing"

	v2225 "github.com/shawtymarco/go-multiversion/data/v2225"
)

// TestNative2225RegistryPinsData pins the 1.26.60 tables that every translated
// packet is built from. The numbers are MEASURED from the pinned upstream data
// package; a failure here means the data moved and all cached palettes, item
// tables and mapper fallbacks must be reviewed before shipping.
func TestNative2225RegistryPinsData(t *testing.T) {
	const (
		wantStates = 23960
		wantItems  = 2080
		wantAir    = 18894
	)

	states, err := v2225.BlockStates()
	if err != nil {
		t.Fatal(err)
	}
	if len(states) != wantStates {
		t.Fatalf("v2225.BlockStates()=%d, want %d", len(states), wantStates)
	}

	// 2225 puts HASHED block IDs on the wire: the dense index of BlockStates()
	// is only an internal enumeration, never the wire runtime ID. The catalog
	// therefore keeps hashes[] and translates by canonical index. If this
	// property ever changes, loadRegistry2225() must be revisited.
	seen := make(map[uint32]int, len(states))
	hashOrdered := 0
	for i, s := range states {
		h, err := StateHash(s.Name, s.Properties)
		if err != nil {
			t.Fatalf("state %d %s: %v", i, s.Name, err)
		}
		if h == uint32(i) {
			hashOrdered++
		}
		if prev, ok := seen[h]; ok {
			t.Fatalf("StateHash collision %#x at indices %d and %d", h, prev, i)
		}
		seen[h] = i
	}
	if hashOrdered != 0 {
		t.Fatalf("2225 palette became index/hash-ordered (%d/%d states); loadRegistry2225() needs review", hashOrdered, len(states))
	}

	items, err := v2225.Items()
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != wantItems {
		t.Fatalf("v2225.Items()=%d, want %d", len(items), wantItems)
	}

	reg, err := NativeRegistry(NativeProtocol)
	if err != nil {
		t.Fatalf("NativeRegistry(%d): %v", NativeProtocol, err)
	}
	if reg.BlockCount() != wantStates {
		t.Errorf("native block count=%d, want %d", reg.BlockCount(), wantStates)
	}
	if reg.AirRuntimeID() != wantAir {
		t.Errorf("native air runtime ID=%d, want %d", reg.AirRuntimeID(), wantAir)
	}

	// 2168 and 2169 share one target snapshot; 2225 is the native table itself.
	wantItemCounts := map[int32]int{
		1001:           1933,
		2168:           1976,
		2169:           1976,
		2193:           2076,
		NativeProtocol: wantItems,
	}
	for id, want := range wantItemCounts {
		it, err := Items(id)
		if err != nil {
			t.Fatalf("Items(%d): %v", id, err)
		}
		if len(it) != want {
			t.Errorf("Items(%d)=%d, want %d", id, len(it), want)
		}
	}

	// The production translation direction: native 2225 -> oldest client.
	// m.fallbacks is the mapper's per-entry fallback array (one slot per source
	// state), NOT the codec's reported AIR fallbacks, so it is not pinned here.
	if _, err := New(NativeProtocol, 1001, true, nil); err != nil {
		t.Fatalf("New(%d->1001): %v", NativeProtocol, err)
	}
}
