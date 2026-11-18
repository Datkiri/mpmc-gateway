package v1_18_0

import (
	"testing"

	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func TestLegacyParticleRegistryShiftRoundTrip(t *testing.T) {
	base := int32(packet.LevelEventParticleLegacyEvent)
	native := &packet.LevelEvent{EventType: base | 19, EventData: 7}

	target := *native
	if !mapLevelEventData(&target, nil, nil, toTarget) || target.EventType != base|18 || target.EventData != 7 {
		t.Fatalf("target particle = %#v", target)
	}
	if native.EventType != base|19 || native.EventData != 7 {
		t.Fatalf("native input mutated = %#v", native)
	}

	latest := target
	if !mapLevelEventData(&latest, nil, nil, toNative) || latest.EventType != base|19 || latest.EventData != 7 {
		t.Fatalf("round-trip particle = %#v", latest)
	}
	if target.EventType != base|18 || target.EventData != 7 {
		t.Fatalf("target input mutated = %#v", &target)
	}
}

func TestNativeOnlyBreezeParticleIsDropped(t *testing.T) {
	pk := &packet.LevelEvent{EventType: packet.LevelEventParticleLegacyEvent | 18}
	cloned := *pk
	if mapLevelEventData(&cloned, nil, nil, toTarget) {
		t.Fatalf("native-only particle converted to %#v", cloned)
	}
	if pk.EventType != packet.LevelEventParticleLegacyEvent|18 {
		t.Fatalf("native input mutated = %#v", pk)
	}
}
