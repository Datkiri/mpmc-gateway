package multiversion

import (
	"reflect"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
)

func TestMinimumProtocolSelection(t *testing.T) {
	all := []minecraft.Protocol{V1_26_45(), V1_26_44(), V1_26_30(), V1_26_20(), V1_26_10(), V1_26_0(),
		V1_21_130(), V1_21_110(), V1_21_100(), V1_21_50(), V1_21_40(), V1_18_10(), V1_18_0(), V1_16_100()}
	before := append([]minecraft.Protocol(nil), all...)
	for _, test := range []struct {
		floor int32
		want  []int32
	}{
		{0, []int32{2169, 2168, 1001, 975, 944, 924, 898, 844, 827, 766, 748, 486, 475, 419}},
		{419, []int32{2169, 2168, 1001, 975, 944, 924, 898, 844, 827, 766, 748, 486, 475, 419}},
		{748, []int32{2169, 2168, 1001, 975, 944, 924, 898, 844, 827, 766, 748}},
		{900, []int32{2169, 2168, 1001, 975, 944, 924}},
		{2168, []int32{2169, 2168}},
		{protocol.CurrentProtocol, []int32{}},
	} {
		config := Config{MinimumProtocol: test.floor}
		if err := config.Validate(); err != nil {
			t.Fatal(err)
		}
		selected := config.selectProtocols(all)
		if cap(selected) != len(selected) {
			t.Fatal("selection exposes shared capacity to listener appends")
		}
		ids := make([]int32, len(selected))
		for i, p := range selected {
			ids[i] = p.ID()
		}
		if !reflect.DeepEqual(ids, test.want) {
			t.Errorf("floor %d: got %v, want %v", test.floor, ids, test.want)
		}
		if len(selected) != 0 {
			selected[0] = nil
		}
		if !reflect.DeepEqual(all, before) {
			t.Fatal("selection changed the source catalogue")
		}
	}
}

func TestMinimumProtocolRejectsInvalidFloorBeforeRegistryConstruction(t *testing.T) {
	for _, floor := range []int32{-1, protocol.CurrentProtocol + 1} {
		config := Config{MinimumProtocol: floor}
		if err := config.Validate(); err == nil {
			t.Fatalf("invalid floor %d accepted", floor)
		}
		if _, err := config.ProtocolsWithRegistries(nil, nil); err == nil {
			t.Fatalf("invalid floor %d built a catalogue", floor)
		}
	}
}
