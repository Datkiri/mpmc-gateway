package main

import (
	"bytes"
	"encoding/binary"
	"reflect"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
)

func TestLegacyRequestChunkPreservesEnvelope(t *testing.T) {
	codec, _ := resolveProto(1001)
	for _, limit := range []int32{-1, 0, 9, 65535} {
		for _, cache := range []bool{false, true} {
			src := &packet.LevelChunk{SubChunkLimit: protocol.Option(limit), CacheEnabled: cache, RawPayload: []byte{1, 2, 3}}
			if cache {
				src.BlobHashes = []uint64{123}
			}
			before := *src
			wire := legacy1001LevelChunkPacket(src).Payload
			// Independently inspect the legacy envelope's count and uint16 limit.
			buf := bytes.NewReader(wire[3:]) // zero x, z, dimension are one byte each.
			count, err := binary.ReadUvarint(buf)
			if err != nil {
				t.Fatal(err)
			}
			if limit < 0 {
				if count != uint64(^uint32(0)) {
					t.Fatalf("unlimited sentinel=%d", count)
				}
			} else {
				if count != uint64(^uint32(0)-1) {
					t.Errorf("finite limit%d encoded with sentinel%d", limit, count)
				}
			}
			got, err := matrixDecode(codec, wire, packet.IDLevelChunk, false)
			if err != nil {
				t.Fatal(err)
			}
			x := got.(*packet.LevelChunk)
			if v, ok := x.SubChunkLimit.Value(); !ok || v != limit {
				t.Errorf("limit=%v want%d", x.SubChunkLimit, limit)
			}
			if !bytes.Equal(x.RawPayload, src.RawPayload) || x.CacheEnabled != cache || !reflect.DeepEqual(x.BlobHashes, src.BlobHashes) {
				t.Error("legacy chunk lost payload/cache/hash")
			}
			if !reflect.DeepEqual(*src, before) {
				t.Error("source packet mutated")
			}
		}
	}
}

func TestSameWireLegacyStartGamePatchVersion(t *testing.T) {
	for _, id := range []int32{1001, 2168, 2169, 2193} {
		codec, _ := resolveProto(id)
		conn := negotiatedConn(t, id)
		version := codec.Ver()
		if id == 1001 {
			version = "1.26.33"
		}
		if id == 2193 {
			version = "1.26.50"
		}
		host := &packet.StartGame{WorldName: "preserved", LevelID: "level", ServerID: "server", GameVersion: version, BaseGameVersion: "*", ServerChunkTickRadius: 6, PropertyData: map[string]any{}}
		raw, err := matrixEncode(codec, host)
		if err != nil {
			t.Fatal(err)
		}
		cap := &spawnCapture{}
		cap.startGame.Store(&raw)
		sessions.Store(conn, &session{hostProto: id, capture: cap})
		p := clientVersionProtocol{Protocol: codec, enabled: true}
		out := p.ConvertFromLatest(&packet.StartGame{PropertyData: map[string]any{}}, conn)
		sessions.Delete(conn)
		if len(out) != 1 {
			t.Fatalf("protocol%d: relay emitted %d packets", id, len(out))
		}
		// Read the WIRE bytes. ConvertToLatest (used by matrixDecode) rewrites
		// StartGame.GameVersion to the adapter's own Version, which is not what the client sees.
		var buf bytes.Buffer
		out[0].Marshal(codec.NewWriter(&buf, 0))
		// Read the WIRE bytes through the codec's own packet wrapper. Unwrapping first would
		// decode with the native 2225 StartGame layout instead of the frozen 2193 wire layout.
		holder := codec.Packets(false)[packet.IDStartGame]()
		holder.Marshal(codec.NewReader(bytes.NewBuffer(buf.Bytes()), 0, true))
		x, ok := unwrapWirePacket(holder).(*packet.StartGame)
		if !ok {
			t.Fatalf("protocol%d: no StartGame constructor", id)
		}
		if x.LevelID != "level" || x.ServerID != "server" || x.ServerChunkTickRadius != 6 || x.GameVersion != codec.Ver() {
			t.Errorf("protocol%d lost metadata/version: level=%q server=%q ticks=%d version=%q", id, x.LevelID, x.ServerID, x.ServerChunkTickRadius, x.GameVersion)
		}
	}
}
