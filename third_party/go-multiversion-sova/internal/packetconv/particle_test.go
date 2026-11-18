package packetconv

import (
	"testing"

	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func TestMapPre12060ParticleEventType(t *testing.T) {
	base := int32(packet.LevelEventParticleLegacyEvent)
	for _, test := range []struct {
		name       string
		input      int32
		toTarget   bool
		want       int32
		wantNative int32
		wantOK     bool
	}{
		{name: "ordinary event", input: packet.LevelEventSoundClick, toTarget: true, want: packet.LevelEventSoundClick, wantNative: packet.LevelEventSoundClick, wantOK: true},
		{name: "before insertion to target", input: base | 17, toTarget: true, want: base | 17, wantNative: base | 17, wantOK: true},
		{name: "inserted particle has no target", input: base | 18, toTarget: true, want: base | 18, wantNative: base | 18, wantOK: false},
		{name: "after insertion to target", input: base | 19, toTarget: true, want: base | 18, wantNative: base | 19, wantOK: true},
		{name: "before insertion to native", input: base | 17, want: base | 17, wantNative: base | 17, wantOK: true},
		{name: "after insertion to native", input: base | 18, want: base | 19, wantNative: base | 19, wantOK: true},
		{name: "late particle round trip", input: base | 85, toTarget: true, want: base | 84, wantNative: base | 85, wantOK: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			pk := &packet.LevelEvent{EventType: test.input}
			native, ok := MapPre12060ParticleEventType(pk, test.toTarget)
			if ok != test.wantOK || pk.EventType != test.want || native != test.wantNative {
				t.Fatalf("mapped = %#x native %#x ok %v, want %#x %#x %v", pk.EventType, native, ok, test.want, test.wantNative, test.wantOK)
			}
		})
	}
}

func TestMapPre12060ParticleEventTypeRoundTrip(t *testing.T) {
	base := int32(packet.LevelEventParticleLegacyEvent)
	for id := int32(1); id <= 100; id++ {
		if id == breezeWindExplosionParticleID {
			continue
		}
		pk := &packet.LevelEvent{EventType: base | id}
		if _, ok := MapPre12060ParticleEventType(pk, true); !ok {
			t.Fatalf("native particle %d was rejected", id)
		}
		if _, ok := MapPre12060ParticleEventType(pk, false); !ok || pk.EventType != base|id {
			t.Fatalf("particle %d round trip = %#x ok %v", id, pk.EventType, ok)
		}
	}
}
