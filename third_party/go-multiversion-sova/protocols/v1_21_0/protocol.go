// Package v1_21_0 implements Minecraft 1.21.0-1.21.3 against the current native model.
// family against the current native gophertunnel model.
package v1_21_0

import (
	"encoding/json"
	"fmt"
	"github.com/shawtymarco/go-multiversion/internal/packetconv"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_45"
	"sort"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/mapping"
)

const (
	// ID is the protocol ID of Minecraft 1.21.30-1.21.31.
	ID int32 = 685
	// Version is the Minecraft version of protocol 685.
	Version = "1.21.0"
	// Hotfix is the protocol ID of Minecraft 1.21.2, which only changed the number.
	Hotfix int32 = 686
	// HotfixVersion is the Minecraft version of the Hotfix protocol.
	HotfixVersion = "1.21.2"
)

// Protocol implements minecraft.Protocol for stable Minecraft 1.21.0-1.21.3.
// Registry-aware consumers may advertise it after construction.
type Protocol struct {
	runtime *runtimeData
	hotfix  bool
}

// MappingReport summarises the exact registries and explicit fallbacks used by
// a configured protocol-685 adapter.
type MappingReport struct {
	NativeBlocks, TargetBlocks int
	NativeItems, TargetItems   int
	BlockFallbacks             []mapping.BlockFallback
	ItemFallbacks              []mapping.ItemFallback
	TargetItemFallbacks        []mapping.TargetItemFallback
	CreativeItems              int
	FurnaceRecipes             int
}

type fallbackProperty struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	Value string `json:"value"`
}

type fallbackBlock struct {
	NativeRuntimeID uint32             `json:"native_runtime_id"`
	Name            string             `json:"name"`
	Properties      []fallbackProperty `json:"properties"`
	TargetRuntimeID uint32             `json:"target_runtime_id"`
}

// New creates a protocol 685 wire adapter.
func New() minecraft.Protocol { return &Protocol{} }

// NewWithBlockRegistry creates a fully configured protocol-685 adapter using
// the current native block registry for direct semantic mappings.
func NewWithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return NewWithRegistries(native, nil)
}

// NewWithRegistries creates a fully configured adapter and validates both the
// current block and item registries before it may be advertised.
func NewWithRegistries(native mapping.BlockRegistry, nativeItems []protocol.ItemEntry) (minecraft.Protocol, error) {
	runtime, err := newRuntimeData(native, nativeItems)
	if err != nil {
		return nil, err
	}
	return &Protocol{runtime: runtime}, nil
}

// ID returns the protocol ID.
func (p Protocol) ID() int32 {
	if p.hotfix {
		return Hotfix
	}
	return ID
}

// Ver returns the Minecraft version.
func (p Protocol) Ver() string {
	if p.hotfix {
		return HotfixVersion
	}
	return Version
}

// NewHotfixWithBlockRegistry creates the adapter of the Hotfix protocol from a native block registry.
func NewHotfixWithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return NewHotfixWithRegistries(native, nil)
}

// NewHotfixWithRegistries creates the adapter of the Hotfix protocol, which has the same wire and registries.
func NewHotfixWithRegistries(native mapping.BlockRegistry, nativeItems []protocol.ItemEntry) (minecraft.Protocol, error) {
	runtime, err := newRuntimeData(native, nativeItems)
	if err != nil {
		return nil, err
	}
	return &Protocol{runtime: runtime, hotfix: true}, nil
}

// MapBlockRuntimeID maps a current native runtime ID to the protocol-685
// network registry. The method is a generic Dragonfly chunk-encoding hook.
func (p Protocol) MapBlockRuntimeID(runtimeID uint32) (uint32, bool) {
	if p.runtime == nil || p.runtime.blocks == nil {
		return 0, false
	}
	target, valid, _ := p.runtime.blocks.MapNative(runtimeID)
	return target, valid
}

// UnmapBlockRuntimeID maps a runtime ID written by MapBlockRuntimeID back to
// the exact current native runtime ID.
func (p Protocol) UnmapBlockRuntimeID(runtimeID uint32) (uint32, bool) {
	if p.runtime == nil || p.runtime.blocks == nil {
		return 0, false
	}
	return p.runtime.blocks.TargetToNative(runtimeID)
}

// MappingReport returns an immutable snapshot of configured mapping evidence.
func (p Protocol) MappingReport() MappingReport {
	if p.runtime == nil {
		return MappingReport{}
	}
	report := MappingReport{
		NativeBlocks:   p.runtime.blocks.NativeCount(),
		TargetBlocks:   p.runtime.blocks.TargetCount(),
		BlockFallbacks: p.runtime.blocks.Fallbacks(),
		CreativeItems:  len(p.runtime.creative),
	}
	if items := p.runtime.currentItemMapper(); items != nil {
		report.NativeItems = items.NativeCount()
		report.TargetItems = items.TargetCount()
		report.ItemFallbacks = items.Fallbacks()
		report.TargetItemFallbacks = items.TargetFallbacks()
		report.FurnaceRecipes = len(targetFurnaceRecipes(p.runtime))
	}
	return report
}

// FallbackReportJSON renders every fallback deterministically for audit and
// release manifests.
func (p Protocol) FallbackReportJSON(nativeDragonflyCommit, targetDragonflyCommit string) ([]byte, error) {
	report := p.MappingReport()
	blocks := make([]fallbackBlock, len(report.BlockFallbacks))
	for index, fallback := range report.BlockFallbacks {
		keys := make([]string, 0, len(fallback.Properties))
		for key := range fallback.Properties {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		properties := make([]fallbackProperty, len(keys))
		for propertyIndex, key := range keys {
			value := fallback.Properties[key]
			properties[propertyIndex] = fallbackProperty{Name: key, Type: fmt.Sprintf("%T", value), Value: fmt.Sprint(value)}
		}
		blocks[index] = fallbackBlock{
			NativeRuntimeID: fallback.NativeRuntimeID,
			Name:            fallback.Name,
			Properties:      properties,
			TargetRuntimeID: fallback.TargetRuntimeID,
		}
	}
	encoded, err := json.MarshalIndent(map[string]any{
		"schema_version":          1,
		"native_dragonfly_commit": nativeDragonflyCommit,
		"target_dragonfly_commit": targetDragonflyCommit,
		"block_policy":            "substitute minecraft:air",
		"item_policy":             "hide clientbound and reject serverbound",
		"block_fallbacks":         blocks,
		"item_fallbacks":          report.ItemFallbacks,
		"target_item_fallbacks":   report.TargetItemFallbacks,
	}, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(encoded, '\n'), nil
}

func (p Protocol) Packets(listener bool) packet.Pool {
	base := packet.NewServerPool()
	if listener {
		base = packet.NewClientPool()
	}
	pool := make(packet.Pool, len(base))
	for id, constructor := range base {
		if id > packet.IDPartyDestinationCookieResponse {
			continue
		}
		pool[id] = constructor
	}

	// Protocol 685 retains PassengerJump, TickSync, PlayerInput and the legacy
	// item-component slot, and ends at ClientBoundCloseForm on
	// 1.21.2 or AwardAchievement on 1.21.0.
	delete(pool, packet.IDServerPlayerPostMovePosition)
	delete(pool, packet.IDServerBoundLoadingScreen)
	delete(pool, packet.IDJigsawStructureData)
	delete(pool, packet.IDCurrentStructureFeature)
	delete(pool, packet.IDServerBoundDiagnostics)
	if !p.hotfix {
		delete(pool, packet.IDClientBoundCloseForm)
	}
	delete(pool, packet.IDCameraAimAssist)
	delete(pool, packet.IDContainerRegistryCleanup)
	delete(pool, packet.IDMovementEffect)
	if listener {
		delete(pool, packet.IDUpdateBlock)
		delete(pool, packet.IDSimpleEvent)
		delete(pool, packet.IDStructureTemplateDataRequest)
		for id := uint32(packet.IDClientCameraAimAssist); id <= uint32(packet.IDPartyDestinationCookieResponse); id++ {
			delete(pool, id)
		}
	} else {
		for id := uint32(packet.IDClientCameraAimAssist); id <= uint32(packet.IDPartyDestinationCookieResponse); id++ {
			delete(pool, id)
		}
	}
	pool[idTickSync685] = func() packet.Packet { return &tickSync685{} }
	pool[idPassengerJump685] = func() packet.Packet { return &passengerJump685{} }
	pool[idPlayerInput685] = func() packet.Packet { return &playerInput685{} }
	if listener {
	} else {
		pool[idCompressedBiomeDefinitionList685] = func() packet.Packet { return &compressedBiomeDefinitionList685{} }
	}
	delete(pool, packet.IDCameraAimAssistPresets)
	for id, marshal := range packetMarshals {
		constructor, ok := pool[id]
		if !ok {
			continue
		}
		pool[id] = translatedConstructor(constructor, marshal)
	}
	for id, constructor := range pool {
		if _, overridden := packetMarshals[id]; !overridden {
			pool[id] = func() packet.Packet { return v1_26_45.WrapWirePacket(constructor()) }
		}
	}
	return pool
}

func (p Protocol) NewReader(r minecraft.ByteReader, shieldID int32, enableLimits bool) protocol.IO {
	base := newWireIO(protocol.NewReader(r, shieldID, enableLimits), true, p.runtime)
	return &wireReader{wireIO: base}
}

func (p Protocol) NewWriter(w minecraft.ByteWriter, shieldID int32) protocol.IO {
	base := newWireIO(protocol.NewWriter(w, shieldID), false, p.runtime)
	return &wireWriter{wireIO: base}
}

func (p Protocol) ConvertToLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	if _, legacyOnly := pk.(legacyOnlyPacket685); legacyOnly {
		return nil
	}
	if translated, ok := pk.(*translatedPacket); ok {
		pk = translated.inner
	}
	return p.convertGameplayToLatest(v1_26_45.UnwrapWirePacket(pk), conn)
}

func (p Protocol) ConvertFromLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	pk = packetconv.LegacyStartGame(pk)
	if packetconv.UnsupportedNativePacket(pk) {
		return nil
	}
	if _, supported := p.Packets(false)[pk.ID()]; !supported {
		return nil
	}
	mapped := p.convertGameplayFromLatest(pk, conn)
	converted := make([]packet.Packet, 0, len(mapped))
	for _, current := range mapped {
		converted = append(converted, downgradePacket(current, conn)...)
	}
	return converted
}

// MapBiomeRuntimeID downgrades new native biomes before chunk cache hashing.
func (Protocol) MapBiomeRuntimeID(id uint32) (uint32, bool) { return mapping.Pre12650Biome(id) }
