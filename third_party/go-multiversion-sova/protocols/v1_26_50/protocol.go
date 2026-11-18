// Package v1_26_50 freezes the outgoing native protocol 2193 wire against the
// stable 1.26.60 native wire model (2225), with direct registry mapping.
package v1_26_50

import (
	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	v2193 "github.com/shawtymarco/go-multiversion/data/v2193"
	"github.com/shawtymarco/go-multiversion/internal/packetio"
	"github.com/shawtymarco/go-multiversion/mapping"
	"strings"
)

const (
	ID      int32 = 2193
	Version       = "1.26.50"
)

type Protocol struct{ runtime *runtimeData }

type MappingReport struct {
	NativeBlocks, TargetBlocks int
	NativeItems, TargetItems   int
	BlockFallbacks             []mapping.BlockFallback
	ItemFallbacks              []mapping.ItemFallback
}

func New() minecraft.Protocol { return &Protocol{} }

func NewWithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return NewWithRegistries(native, nil)
}

func NewWithRegistries(native mapping.BlockRegistry, items []protocol.ItemEntry) (minecraft.Protocol, error) {
	runtime, err := newRuntimeData(native, items)
	if err != nil {
		return nil, err
	}
	return &Protocol{runtime: runtime}, nil
}

func (p Protocol) MapBlockRuntimeID(id uint32) (uint32, bool) {
	if p.runtime == nil {
		return 0, false
	}
	mapped, valid, _ := p.runtime.blocks.MapNative(id)
	return mapped, valid
}

// UnmapBlockRuntimeID maps a runtime ID written by MapBlockRuntimeID back to
// the exact current native runtime ID.
func (p Protocol) UnmapBlockRuntimeID(runtimeID uint32) (uint32, bool) {
	if p.runtime == nil || p.runtime.blocks == nil {
		return 0, false
	}
	return p.runtime.blocks.TargetToNative(runtimeID)
}

func (p Protocol) MappingReport() MappingReport {
	if p.runtime == nil {
		return MappingReport{}
	}
	r := MappingReport{NativeBlocks: p.runtime.blocks.NativeCount(), TargetBlocks: p.runtime.blocks.TargetCount(), BlockFallbacks: p.runtime.blocks.Fallbacks()}
	if items := p.runtime.currentItemMapper(); items != nil {
		r.NativeItems, r.TargetItems, r.ItemFallbacks = items.NativeCount(), items.TargetCount(), items.Fallbacks()
	}
	return r
}

func (Protocol) MapBiomeRuntimeID(id uint32) (uint32, bool) { return id, true }
func (Protocol) ID() int32                                  { return ID }
func (Protocol) Ver() string                                { return Version }

func (Protocol) Packets(listener bool) packet.Pool {
	pool := packet.NewServerPool()
	if listener {
		pool = packet.NewClientPool()
	}
	for id, constructor := range pool {
		if id > packet.IDRecordStarted {
			delete(pool, id)
			continue
		}
		pool[id] = func() packet.Packet { return WrapWirePacket(constructor()) }
	}
	return pool
}

type reader struct{ protocol.IO }
type writer struct{ protocol.IO }

func (r *reader) SliceLength(value, maximum uint32) {
	if limits, ok := r.IO.(interface{ SliceLength(uint32, uint32) }); ok {
		limits.SliceLength(value, maximum)
	}
}
func (r *reader) StackRequestAction(value *protocol.StackRequestAction) {
	packetio.StackRequestAction2193(r, value, true)
}
func (w *writer) StackRequestAction(value *protocol.StackRequestAction) {
	packetio.StackRequestAction2193(w, value, false)
}
func (Protocol) NewReader(r minecraft.ByteReader, shieldID int32, limits bool) protocol.IO {
	return &reader{protocol.NewReader(r, shieldID, limits)}
}
func (Protocol) NewWriter(w minecraft.ByteWriter, shieldID int32) protocol.IO {
	return &writer{protocol.NewWriter(w, shieldID)}
}

func (p Protocol) ConvertToLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	return p.convertGameplayToLatest(UnwrapWirePacket(pk), conn)
}
func (p Protocol) ConvertFromLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	if pk.ID() > packet.IDRecordStarted {
		return nil
	}
	if start, ok := pk.(*packet.StartGame); ok {
		copy := *start
		copy.GameVersion, copy.BaseGameVersion = Version, Version
		copy.UseBlockNetworkIDHashes = false
		if p.runtime != nil {
			definitions, err := v2193.BlockDefinitions()
			if err != nil {
				return nil
			}
			copy.Blocks = definitions
			for _, block := range start.Blocks {
				if !strings.HasPrefix(block.Name, "minecraft:") {
					copy.Blocks = append(copy.Blocks, block)
				}
			}
		}
		pk = &copy
	}
	mapped := p.convertGameplayFromLatest(pk, conn)
	for i := range mapped {
		mapped[i] = WrapWirePacket(mapped[i])
	}
	return mapped
}

type wireMarshal func(protocol.IO, packet.Packet, bool)
type wirePacket struct {
	inner   packet.Packet
	marshal wireMarshal
}

func (p *wirePacket) ID() uint32 { return p.inner.ID() }
func (p *wirePacket) Marshal(io protocol.IO) {
	_, reading := io.(interface{ SliceLength(uint32, uint32) })
	p.marshal(io, p.inner, reading)
}

// WrapWirePacket applies only the frozen 2193 wire differences. Concrete
// historical wrappers keep their own layout and direct registry conversion.
func WrapWirePacket(pk packet.Packet) packet.Packet {
	var marshal wireMarshal
	switch pk.(type) {
	case *packet.InventoryTransaction:
		marshal = marshalInventoryTransaction
	case *packet.StartGame:
		marshal = marshalStartGame
	case *packet.AddActor:
		marshal = marshalAddActor
	case *packet.AddPlayer:
		marshal = marshalAddPlayer
	case *packet.Animate:
		marshal = marshalAnimate
	case *packet.LevelChunk:
		marshal = marshalLevelChunk
	case *packet.PlayerList:
		marshal = marshalPlayerList
	case *packet.PlayerSkin:
		marshal = marshalPlayerSkin
	case *packet.DimensionData:
		marshal = marshalDimensionData
	case *packet.EducationSettings:
		marshal = marshalEducationSettings
	case *packet.ServerBoundDiagnostics:
		marshal = marshalServerBoundDiagnostics
	case *packet.ClientboundUpdateSoundData:
		marshal = marshalClientboundUpdateSoundData
	case *packet.ClientBoundAttributeLayerSync:
		marshal = marshalClientBoundAttributeLayerSync
	}
	if marshal == nil {
		return pk
	}
	return &wirePacket{pk, marshal}
}
func UnwrapWirePacket(pk packet.Packet) packet.Packet {
	if p, ok := pk.(*wirePacket); ok {
		return p.inner
	}
	return pk
}
