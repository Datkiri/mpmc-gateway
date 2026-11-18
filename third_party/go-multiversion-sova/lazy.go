package multiversion

import (
	"fmt"
	"log/slog"
	"sync"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

type lazyProtocol struct {
	id      int32
	ver     string
	builder func() (minecraft.Protocol, error)
	once    sync.Once
	proto   minecraft.Protocol
	err     error
}

func newLazy(id int32, ver string, builder func() (minecraft.Protocol, error)) *lazyProtocol {
	return &lazyProtocol{
		id:      id,
		ver:     ver,
		builder: builder,
	}
}

func (p *lazyProtocol) load() (minecraft.Protocol, error) {
	p.once.Do(func() {
		p.proto, p.err = p.builder()
		if p.err != nil {
			p.proto = nil
			slog.Error("protocol adapter failed to load", "protocol", p.id, "version", p.ver, "error", p.err)
		}
	})
	return p.proto, p.err
}

func (p *lazyProtocol) ID() int32   { return p.id }
func (p *lazyProtocol) Ver() string { return p.ver }

func (p *lazyProtocol) Packets(listener bool) packet.Pool {
	proto, err := p.load()
	if err != nil {
		return nil
	}
	return proto.Packets(listener)
}

func (p *lazyProtocol) NewReader(r minecraft.ByteReader, shieldID int32, limits bool) protocol.IO {
	proto, err := p.load()
	if err != nil {
		return nil
	}
	return proto.NewReader(r, shieldID, limits)
}

func (p *lazyProtocol) NewWriter(w minecraft.ByteWriter, shieldID int32) protocol.IO {
	proto, err := p.load()
	if err != nil {
		return nil
	}
	return proto.NewWriter(w, shieldID)
}

func (p *lazyProtocol) ConvertFromLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	proto, err := p.load()
	if err != nil {
		return nil
	}
	return proto.ConvertFromLatest(pk, conn)
}

func (p *lazyProtocol) ConvertToLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	proto, err := p.load()
	if err != nil {
		return nil
	}
	return proto.ConvertToLatest(pk, conn)
}

func (p *lazyProtocol) MapBlockRuntimeID(runtimeID uint32) (uint32, bool) {
	proto, err := p.load()
	if err != nil {
		return 0, false
	}
	if mapper, ok := proto.(interface{ MapBlockRuntimeID(uint32) (uint32, bool) }); ok {
		return mapper.MapBlockRuntimeID(runtimeID)
	}
	return 0, false
}

// UnmapBlockRuntimeID maps a runtime ID written by MapBlockRuntimeID back to
// the exact current native runtime ID.
func (p *lazyProtocol) UnmapBlockRuntimeID(runtimeID uint32) (uint32, bool) {
	proto, err := p.load()
	if err != nil {
		return 0, false
	}
	if mapper, ok := proto.(interface{ UnmapBlockRuntimeID(uint32) (uint32, bool) }); ok {
		return mapper.UnmapBlockRuntimeID(runtimeID)
	}
	return 0, false
}

func (p *lazyProtocol) MapBiomeRuntimeID(id uint32) (uint32, bool) {
	proto, err := p.load()
	if err != nil {
		return id, true
	}
	if mapper, ok := proto.(interface{ MapBiomeRuntimeID(uint32) (uint32, bool) }); ok {
		return mapper.MapBiomeRuntimeID(id)
	}
	return id, true
}

func (p *lazyProtocol) FallbackReportJSON() ([]byte, error) {
	proto, err := p.load()
	if err != nil {
		return nil, err
	}
	if reporter, ok := proto.(interface{ FallbackReportJSON() ([]byte, error) }); ok {
		return reporter.FallbackReportJSON()
	}
	return nil, fmt.Errorf("protocol does not support fallback report")
}

func (p *lazyProtocol) Unwrap() (minecraft.Protocol, error) {
	return p.load()
}

type lazyProtocol118 struct {
	*lazyProtocol
}

func newLazy118(id int32, ver string, builder func() (minecraft.Protocol, error)) *lazyProtocol118 {
	return &lazyProtocol118{lazyProtocol: newLazy(id, ver, builder)}
}

func (p *lazyProtocol118) LegacyNetworkSettings() packet.Compression {
	return packet.FlateCompression
}

func (p *lazyProtocol118) ReuseBiomePalettes() bool {
	return false
}

type lazyProtocol116 struct {
	*lazyProtocol118
}

func newLazy116(id int32, ver string, builder func() (minecraft.Protocol, error)) *lazyProtocol116 {
	return &lazyProtocol116{lazyProtocol118: newLazy118(id, ver, builder)}
}

func (p *lazyProtocol116) Encryption(key [32]byte) packet.Encryption {
	return packet.NewCFB8Encryption(key)
}

func (p *lazyProtocol116) PreSpawnPackets() []packet.Packet {
	return []packet.Packet{&packet.BiomeDefinitionList{}}
}

func (p *lazyProtocol116) NetworkChunkRange() (minY, maxY int16) {
	return 0, 255
}

func (p *lazyProtocol116) NetworkSubChunkVersion() byte {
	return 8
}

func (p *lazyProtocol116) NetworkBiomes2D() bool {
	return true
}

func (p *lazyProtocol116) NetworkChunkRadiusLimit() int {
	return 4
}

func (p *lazyProtocol116) ConvertBlockActorNBT(data map[string]any) map[string]any {
	proto, err := p.load()
	if err != nil {
		return data
	}
	if c, ok := proto.(interface {
		ConvertBlockActorNBT(map[string]any) map[string]any
	}); ok {
		return c.ConvertBlockActorNBT(data)
	}
	return data
}
