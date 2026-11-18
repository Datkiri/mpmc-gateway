package packetconv

import "github.com/sandertv/gophertunnel/minecraft/protocol/packet"

const breezeWindExplosionParticleID int32 = 18

// MapPre12060ParticleEventType maps the legacy particle enum across the Breeze
// Wind Explosion insertion made after 1.20.50. It returns the current-native
// event type so callers can map particle-specific data without depending on
// the direction of the conversion.
func MapPre12060ParticleEventType(pk *packet.LevelEvent, toTarget bool) (nativeEventType int32, ok bool) {
	if pk == nil || pk.EventType < packet.LevelEventParticleLegacyEvent {
		if pk == nil {
			return 0, false
		}
		return pk.EventType, true
	}
	particleID := pk.EventType - packet.LevelEventParticleLegacyEvent
	if particleID <= 0 || particleID >= packet.LevelEventParticleLegacyEvent {
		return pk.EventType, true
	}
	if toTarget {
		if particleID == breezeWindExplosionParticleID {
			// The target registry has no representation for this native-only
			// particle. Dropping it is safer than aliasing it to another effect.
			return pk.EventType, false
		}
		nativeEventType = pk.EventType
		if particleID > breezeWindExplosionParticleID {
			pk.EventType--
		}
		return nativeEventType, true
	}
	if particleID >= breezeWindExplosionParticleID {
		pk.EventType++
	}
	return pk.EventType, true
}
