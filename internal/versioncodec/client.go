package versioncodec

import (
	"bytes"
	"io"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

// Client preserves wire IDs and recovers the embedded item table discarded by
// the pinned 685/712/729/748/766 readers. Construct one wrapper per client
// connection.
//
// Those five protocols all read StartGame's embedded item table into a local
// variable and drop it on the wire, leaving GameData.Items with only the small
// component-based ItemRegistry table (85-86 entries). The byte layout up to the
// items field is identical in every one, so LegacyStartGameItems recovers it.
func Client(base minecraft.Protocol) minecraft.Protocol {
	switch base.ID() {
	case 685, 712, 729, 748, 766:
		return &legacyClient{Protocol: base}
	default:
		return base
	}
}

type legacyClient struct {
	minecraft.Protocol
	capture *recordingReader
	items   []protocol.ItemEntry
}

type recordingReader struct {
	minecraft.ByteReader
	captured bytes.Buffer
}

// Len reports unread source bytes for payload-bound checks, rather than the
// number of bytes already captured by the embedded recording buffer.
func (r *recordingReader) Len() int {
	if source, ok := r.ByteReader.(interface{ Len() int }); ok {
		return source.Len()
	}
	return int(^uint(0) >> 1)
}

func (r *recordingReader) Read(b []byte) (int, error) {
	n, err := r.ByteReader.Read(b)
	_, _ = r.captured.Write(b[:n])
	return n, err
}
func (r *recordingReader) ReadByte() (byte, error) {
	b, err := r.ByteReader.ReadByte()
	if err == nil {
		_ = r.captured.WriteByte(b)
	}
	return b, err
}

func (p *legacyClient) NewReader(r minecraft.ByteReader, shieldID int32, limits bool) protocol.IO {
	p.capture = &recordingReader{ByteReader: r}
	return p.Protocol.NewReader(p.capture, shieldID, limits)
}
func (p *legacyClient) Packets(listener bool) packet.Pool {
	pool := p.Protocol.Packets(listener)
	if !listener {
		constructor := pool[packet.IDStartGame]
		pool[packet.IDStartGame] = func() packet.Packet { return &legacyStartGame{Packet: constructor(), owner: p} }
	}
	return pool
}

type legacyStartGame struct {
	packet.Packet
	owner *legacyClient
	items []protocol.ItemEntry
}

func (pk *legacyStartGame) Marshal(r protocol.IO) {
	// The legacy vanilla table exceeds the generic 1024-element slice cap.
	// Read this packet with the adapter cap disabled; the received table is
	// independently bounded by LegacyStartGameItems below.
	pk.Packet.Marshal(pk.owner.Protocol.NewReader(pk.owner.capture, 0, false))
	if pk.owner.capture == nil {
		return
	}
	items, err := LegacyStartGameItems(pk.owner.capture.captured.Bytes())
	if err != nil {
		panic(err)
	}
	pk.items = items
}
func (p *legacyClient) ConvertToLatest(pk packet.Packet, conn *minecraft.Conn) []packet.Packet {
	if sg, ok := pk.(*legacyStartGame); ok {
		p.items = sg.items
		pk = sg.Packet
	}
	out := p.Protocol.ConvertToLatest(pk, conn)
	for i, pk := range out {
		registry, ok := pk.(*packet.ItemRegistry)
		if !ok || p.items == nil {
			continue
		}
		// The historical packet contains component NBT keyed by name, rather than
		// the complete numeric ID table, which arrived earlier in StartGame.
		definitions := make(map[string]protocol.ItemEntry, len(registry.Items))
		for _, item := range registry.Items {
			definitions[item.Name] = item
		}
		items := append([]protocol.ItemEntry(nil), p.items...)
		for j, item := range items {
			if component, ok := definitions[item.Name]; ok {
				items[j].Data = component.Data
				items[j].ComponentBased = component.ComponentBased
			}
		}
		out[i] = &packet.ItemRegistry{Items: items}
	}
	return out
}

var _ io.ByteReader = (*recordingReader)(nil)
