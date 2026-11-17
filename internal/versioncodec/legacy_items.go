// Package versioncodec contains client-side compatibility readers for pinned codecs.
package versioncodec

import (
	"bytes"
	"fmt"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

// LegacyStartGameItems reads the embedded item registry carried by protocols
// 748/766. Their pinned adapters decode this table into a local variable and
// discard it, while the subsequent ItemComponent packet has no vanilla IDs.
// This reader uses received bytes only; it never substitutes a pinned table.
func LegacyStartGameItems(payload []byte) (items []protocol.ItemEntry, err error) {
	defer func() {
		if p := recover(); p != nil {
			items = nil
			err = fmt.Errorf("legacy StartGame item table: %v", p)
		}
	}()
	r := protocol.NewReader(bytes.NewBuffer(payload), 0, true)
	p := &packet.StartGame{}
	r.Varint64(&p.EntityUniqueID)
	r.Varuint64(&p.EntityRuntimeID)
	r.Varint32(&p.PlayerGameMode)
	r.Vec3(&p.PlayerPosition)
	r.Float32(&p.Pitch)
	r.Float32(&p.Yaw)
	r.Int64(&p.WorldSeed)
	r.Int16(&p.SpawnBiomeType)
	r.String(&p.UserDefinedBiomeName)
	r.Varint32(&p.Dimension)
	r.Varint32(&p.Generator)
	r.Varint32(&p.WorldGameMode)
	r.Bool(&p.Hardcore)
	r.Varint32(&p.Difficulty)
	r.Varint32(&p.WorldSpawn[0])
	var height uint32
	r.Varuint32(&height)
	p.WorldSpawn[1] = int32(height)
	r.Varint32(&p.WorldSpawn[2])
	r.Bool(&p.AchievementsDisabled)
	r.Varint32(&p.EditorWorldType)
	r.Bool(&p.CreatedInEditor)
	r.Bool(&p.ExportedFromEditor)
	r.Varint32(&p.DayCycleLockTime)
	var offer int32
	r.Varint32(&offer)
	r.Bool(&p.EducationFeaturesEnabled)
	r.String(&p.EducationProductID)
	r.Float32(&p.RainLevel)
	r.Float32(&p.LightningLevel)
	r.Bool(&p.ConfirmedPlatformLockedContent)
	r.Bool(&p.MultiPlayerGame)
	r.Bool(&p.LANBroadcastEnabled)
	r.Varint32(&p.XBLBroadcastMode)
	r.Varint32(&p.PlatformBroadcastMode)
	r.Bool(&p.CommandsEnabled)
	r.Bool(&p.TexturePackRequired)
	var rules uint32
	r.Varuint32(&rules)
	if rules > 4096 {
		return nil, fmt.Errorf("too many game rules: %d", rules)
	}
	for i := uint32(0); i < rules; i++ {
		var name string
		var editable bool
		var kind uint32
		r.String(&name)
		r.Bool(&editable)
		r.Varuint32(&kind)
		switch kind {
		case 1:
			var value bool
			r.Bool(&value)
		case 2:
			var value uint32
			r.Varuint32(&value)
		case 3:
			var value float32
			r.Float32(&value)
		default:
			return nil, fmt.Errorf("unknown game rule kind %d", kind)
		}
	}
	protocol.SliceUint32Length(r, &p.Experiments)
	r.Bool(&p.ExperimentsPreviouslyToggled)
	r.Bool(&p.BonusChestEnabled)
	r.Bool(&p.StartWithMapEnabled)
	var permissions int32
	r.Varint32(&permissions)
	r.Int32(&p.ServerChunkTickRadius)
	r.Bool(&p.HasLockedBehaviourPack)
	r.Bool(&p.HasLockedTexturePack)
	r.Bool(&p.FromLockedWorldTemplate)
	r.Bool(&p.MSAGamerTagsOnly)
	r.Bool(&p.FromWorldTemplate)
	r.Bool(&p.WorldTemplateSettingsLocked)
	r.Bool(&p.OnlySpawnV1Villagers)
	r.Bool(&p.PersonaDisabled)
	r.Bool(&p.CustomSkinsDisabled)
	r.Bool(&p.EmoteChatMuted)
	r.String(&p.BaseGameVersion)
	r.Int32(&p.LimitedWorldWidth)
	r.Int32(&p.LimitedWorldDepth)
	r.Bool(&p.NewNether)
	protocol.Single(r, &p.EducationSharedResourceURI)
	var experimental bool
	r.Bool(&experimental)
	r.Uint8(&p.ChatRestrictionLevel)
	r.Bool(&p.DisablePlayerInteractions)
	r.String(&p.ServerID)
	r.String(&p.WorldID)
	r.String(&p.ScenarioID)
	r.String(&p.LevelID)
	r.String(&p.WorldName)
	r.String(&p.TemplateContentIdentity)
	r.Bool(&p.Trial)
	var movement int32
	r.Varint32(&movement)
	r.Varint32(&p.PlayerMovementSettings.RewindHistorySize)
	r.Bool(&p.PlayerMovementSettings.ServerAuthoritativeBlockBreaking)
	r.Int64(&p.Time)
	r.Varint32(&p.EnchantmentSeed)
	protocol.Slice(r, &p.Blocks)
	var count uint32
	r.Varuint32(&count)
	if count > 65536 {
		return nil, fmt.Errorf("too many legacy items: %d", count)
	}
	items = make([]protocol.ItemEntry, count)
	for i := range items {
		r.String(&items[i].Name)
		r.Int16(&items[i].RuntimeID)
		r.Bool(&items[i].ComponentBased)
	}
	return items, nil
}
