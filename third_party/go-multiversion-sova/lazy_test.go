package multiversion

import (
	"sync/atomic"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

type dummyProto struct {
	minecraft.Protocol
	mapped uint32
}

func (d *dummyProto) ID() int32   { return 999 }
func (d *dummyProto) Ver() string { return "1.99.9" }
func (d *dummyProto) MapBlockRuntimeID(id uint32) (uint32, bool) {
	return d.mapped + id, true
}
func (d *dummyProto) MapBiomeRuntimeID(id uint32) (uint32, bool) {
	return id, true
}
func (d *dummyProto) Packets(listener bool) packet.Pool {
	return packet.NewServerPool()
}

func TestLazyProtocolLoadOnDemand(t *testing.T) {
	var built atomic.Int32
	lazy := newLazy(999, "1.99.9", func() (minecraft.Protocol, error) {
		built.Add(1)
		return &dummyProto{mapped: 100}, nil
	})

	if lazy.ID() != 999 {
		t.Fatalf("expected ID 999, got %d", lazy.ID())
	}
	if lazy.Ver() != "1.99.9" {
		t.Fatalf("expected Ver 1.99.9, got %s", lazy.Ver())
	}
	if built.Load() != 0 {
		t.Fatal("protocol should not be built on ID() or Ver() access")
	}

	mapped, ok := lazy.MapBlockRuntimeID(5)
	if !ok || mapped != 105 {
		t.Fatalf("expected mapped 105, got %d, %v", mapped, ok)
	}
	if built.Load() != 1 {
		t.Fatalf("expected built once, got %d", built.Load())
	}

	// Repeated calls must reuse the loaded instance
	mapped, _ = lazy.MapBlockRuntimeID(10)
	if mapped != 110 || built.Load() != 1 {
		t.Fatalf("expected memoized protocol, got count %d", built.Load())
	}
}

func TestLazyProtocolsWithNilRegistry(t *testing.T) {
	if _, err := ProtocolsWithRegistries(nil, nil); err == nil {
		t.Fatal("expected error with nil native registry")
	}
	if _, err := ProtocolsWithBlockRegistry(nil); err == nil {
		t.Fatal("expected error with nil native registry")
	}
}
