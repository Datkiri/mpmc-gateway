package multiversion_test

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"reflect"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	multiversion "github.com/shawtymarco/go-multiversion"
)

// Independently emitted by each target's locked historical gophertunnel source:
// 268adeb5, ecff04b7, 49e707e, bf05a1a, c839e607, 9c440d5f, 165bd86b,
// de090ae, 0a2ecd5, 8a2b1f7 and 7f058e5. The camera preset delta is not inferred
// from a round trip through the current native writer.
func TestEveryHistoricalCameraPresetWire(t *testing.T) {
	const modernBody = "AQR0ZXN0Fm1pbmVjcmFmdDpmaXJzdF9wZXJzb24BAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAA="
	targets := []minecraft.Protocol{multiversion.V1_21_40(), multiversion.V1_21_50(), multiversion.V1_21_100(), multiversion.V1_21_110(), multiversion.V1_21_130(), multiversion.V1_26_0(), multiversion.V1_26_10(), multiversion.V1_26_20(), multiversion.V1_26_30(), multiversion.V1_26_44(), multiversion.V1_26_45()}
	fixture := func() *packet.CameraPresets {
		return &packet.CameraPresets{Presets: []protocol.CameraPreset{{Name: "test", Parent: "minecraft:first_person", PosX: protocol.Option(float32(2))}}}
	}
	for _, p := range targets {
		t.Run(fmt.Sprint(p.ID()), func(t *testing.T) {
			body := modernBody
			if p.ID() == 748 {
				body = "AQR0ZXN0Fm1pbmVjcmFmdDpmaXJzdF9wZXJzb24BAAAAQAAAAAAAAAAAAAAAAAAAAA=="
			}
			if p.ID() == 766 {
				body = "AQR0ZXN0Fm1pbmVjcmFmdDpmaXJzdF9wZXJzb24BAAAAQAAAAAAAAAAAAAAAAAAAAAAA"
			}
			want, err := base64.StdEncoding.DecodeString(body)
			if err != nil {
				t.Fatal(err)
			}
			source := fixture()
			mapped := p.ConvertFromLatest(source, nil)
			if len(mapped) != 1 {
				t.Fatal("camera presets were dropped")
			}
			var encoded bytes.Buffer
			mapped[0].Marshal(p.NewWriter(&encoded, 0))
			if !bytes.Equal(encoded.Bytes(), want) {
				t.Fatalf("body mismatch: got %x want %x", encoded.Bytes(), want)
			}
			if !reflect.DeepEqual(source, fixture()) {
				t.Fatal("encoding mutated the source")
			}
			decoded := p.Packets(false)[packet.IDCameraPresets]()
			reader := bytes.NewBuffer(want)
			decoded.Marshal(p.NewReader(reader, 0, true))
			if reader.Len() != 0 {
				t.Fatalf("unread bytes = %d", reader.Len())
			}
			latest := p.ConvertToLatest(decoded, nil)
			if len(latest) != 1 {
				t.Fatal("decoded camera was dropped")
			}
			presets := latest[0].(*packet.CameraPresets).Presets
			if len(presets) != 1 || presets[0].Name != "test" {
				t.Fatal("camera identity changed")
			}
			if x, ok := presets[0].PosX.Value(); !ok || x != 2 {
				t.Fatal("camera position changed")
			}
		})
	}
}
