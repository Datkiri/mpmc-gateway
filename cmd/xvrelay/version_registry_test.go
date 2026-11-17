package main

import (
	"bytes"
	"strconv"
	"strings"
	"testing"

	"bedrock-cross-version-relay/internal/blocktranslate"

	"github.com/sandertv/gophertunnel/minecraft/protocol/packet"
	"github.com/shawtymarco/go-multiversion/mapping"
	v1_26_50 "github.com/shawtymarco/go-multiversion/protocols/v1_26_50"
)

// TestProtocolForVersion is the table-driven oracle for PROTO-001. Every version the README
// advertises must resolve to the protocol the codec actually implements; unknown versions must
// return 0 so verifyHostProtocol declines to draw a conclusion instead of rejecting a good host.
func TestProtocolForVersion(t *testing.T) {
	cases := []struct {
		version string
		want    int32
	}{
		{"1.21.40", 748}, {"1.21.41", 748}, {"1.21.42", 0}, {"1.21.43", 748}, {"1.21.44", 748},
		{"1.21.50", 766}, {"1.21.51", 766},
		{"1.21.100", 827}, {"1.21.102", 827},
		{"1.21.110", 844}, {"1.21.114", 844},
		{"1.21.130", 898}, {"1.21.132", 898},
		{"1.26.0", 924}, {"1.26.3", 924},
		{"1.26.10", 944}, {"1.26.14", 944},
		{"1.26.20", 975}, {"1.26.23", 975},
		{"1.21.0", 0}, {"1.21.20", 0}, {"1.21.60", 0}, {"1.21.90", 0}, {"1.26.70", 0},
		{"1.26.30", 1001}, {"1.26.31", 1001}, {"1.26.32", 1001}, {"1.26.33", 1001},
		{"1.26.34", 1001}, {"1.26.35", 1001}, {"1.26.36", 1001},
		{"1.26.40", 2168}, {"1.26.41", 2168}, {"1.26.42", 2168}, {"1.26.43", 2168}, {"1.26.44", 2168},
		{"1.26.45", 2169},
		{"1.26.50", 2193}, {"1.26.51", 2193}, {"1.26.52", 2193},
		{"1.26.60", 2225},
		{"1.26.46", 0}, {"1.26.49", 0}, {"1.27.0", 0}, {"", 0}, {"*", 0}, {"not-a-version", 0},
	}
	for _, c := range cases {
		if got := protocolForVersion(c.version); got != c.want {
			t.Errorf("protocolForVersion(%q) = %d, want %d", c.version, got, c.want)
		}
	}

	// Every mapped protocol must have a codec; a table entry pointing at a protocol the relay
	// cannot serve would let verifyHostProtocol accept a session that cannot decode the wire.
	for _, id := range []int32{748, 766, 827, 844, 898, 924, 944, 975, 1001, 2168, 2169, 2193, 2225} {
		p, ok := resolveProto(id)
		if !ok {
			t.Errorf("resolveProto(%d) has no codec", id)
			continue
		}
		if p.ID() != id {
			t.Errorf("resolveProto(%d).ID() = %d", id, p.ID())
		}
	}
}

// TestAutoClientProtocolsCoverEveryCodec pins the -client-proto auto list. The native codec moved
// from 2193 to 2225: the mandatory native add in main() only covers 2225, so dropping 2193 from
// the auto list silently rejects every 1.26.50-52 client.
func TestAutoClientProtocolsCoverEveryCodec(t *testing.T) {
	got := map[int32]bool{}
	for _, s := range strings.Split(autoClientProtocols, ",") {
		id, err := strconv.Atoi(strings.TrimSpace(s))
		if err != nil {
			t.Fatalf("bad protocol in autoClientProtocols: %q", s)
		}
		if _, ok := resolveProto(int32(id)); !ok {
			t.Errorf("autoClientProtocols lists %d but resolveProto has no codec", id)
		}
		got[int32(id)] = true
	}
	for _, id := range []int32{748, 766, 827, 844, 898, 924, 944, 975, 1001, 2168, 2169, 2193, 2225} {
		if !got[id] {
			t.Errorf("-client-proto auto is missing protocol %d", id)
		}
	}
	if !got[blocktranslate.NativeProtocol] {
		t.Errorf("-client-proto auto is missing the native protocol %d", blocktranslate.NativeProtocol)
	}

	probed := map[int32]bool{}
	for _, id := range hostProtoProbeOrder {
		if _, ok := resolveProto(id); !ok {
			t.Errorf("hostProtoProbeOrder lists %d but resolveProto has no codec", id)
		}
		probed[id] = true
	}
	for _, id := range []int32{748, 766, 827, 844, 898, 924, 944, 975, 1001, 2168, 2169, 2193, 2225} {
		if !probed[id] {
			t.Errorf("hostProtoProbeOrder never tries protocol %d", id)
		}
	}
}

// TestVerifyHostProtocol checks the "mismatched host" case: a host that accepted a foreign
// protocol still declares its real version in StartGame, and the relay must notice.
func TestVerifyHostProtocol(t *testing.T) {
	codec, _ := resolveProto(2193) // the 2193 wire codec (adapter over the 2225 native)

	encode := func(version string) *spawnCapture {
		t.Helper()
		sg := &packet.StartGame{
			EntityUniqueID: 1, EntityRuntimeID: 1, WorldName: "version-probe",
			GameVersion: version, BaseGameVersion: "*",
		}
		// Encode with the frozen 2193 wire layout directly (WrapWirePacket), NOT through
		// ConvertFromLatest: the adapter normalises GameVersion to its own Version constant,
		// while a real host's wire keeps the exact string. This fixture models the host.
		var buf bytes.Buffer
		v1_26_50.WrapWirePacket(sg).Marshal(codec.NewWriter(&buf, 0))
		raw := buf.Bytes()
		decoded, err := decodeRawStartGameWith(codec, raw)
		if err != nil {
			t.Fatalf("decode StartGame(%q): %v", version, err)
		}
		if decoded.GameVersion != version {
			t.Fatalf("StartGame version changed on the wire: %q -> %q", version, decoded.GameVersion)
		}
		cap := &spawnCapture{}
		cap.startGame.Store(&raw)
		return cap
	}

	// A 1.26.60 host writes the native 2225 StartGame layout (no wirePacket wrapper).
	encode2225 := func(version string) *spawnCapture {
		t.Helper()
		native, _ := resolveProto(blocktranslate.NativeProtocol)
		sg := &packet.StartGame{
			EntityUniqueID: 1, EntityRuntimeID: 1, WorldName: "version-probe",
			GameVersion: version, BaseGameVersion: "*",
		}
		var buf bytes.Buffer
		sg.Marshal(native.NewWriter(&buf, 0))
		raw := buf.Bytes()
		decoded, err := decodeRawStartGameWith(native, raw)
		if err != nil {
			t.Fatalf("decode native StartGame(%q): %v", version, err)
		}
		if decoded.GameVersion != version {
			t.Fatalf("native StartGame version changed on the wire: %q -> %q", version, decoded.GameVersion)
		}
		cap := &spawnCapture{}
		cap.startGame.Store(&raw)
		return cap
	}

	cases := []struct {
		name    string
		cap     *spawnCapture
		pid     int32
		wantVer string
		wantOK  bool
	}{
		{"matching 2193", encode("1.26.51"), 2193, "1.26.51", true},
		{"matching 1.26.52", encode("1.26.52"), 2193, "1.26.52", true},
		{"matching 2225", encode2225("1.26.60"), 2225, "1.26.60", true},
		{"2225 host, no StartGame captured", &spawnCapture{}, 2225, "", true},
		{"host is really 1001", encode("1.26.33"), 2193, "1.26.33", false},
		{"host is really 2168", encode("1.26.44"), 2193, "1.26.44", false},
		{"host is really 2169", encode("1.26.45"), 2193, "1.26.45", false},
		{"unknown version", encode("1.26.46"), 2193, "1.26.46", true},
		{"wildcard version", encode("*"), 2193, "*", true},
		{"empty version", encode(""), 2193, "", true},
		{"no StartGame captured", &spawnCapture{}, 2193, "", true},
		{"no codec for pid", encode("1.26.51"), 1234, "", false},
	}
	for _, c := range cases {
		gotVer, gotOK := verifyHostProtocol(c.cap, c.pid)
		if gotVer != c.wantVer || gotOK != c.wantOK {
			t.Errorf("%s: verifyHostProtocol = (%q, %v), want (%q, %v)",
				c.name, gotVer, gotOK, c.wantVer, c.wantOK)
		} else {
			t.Logf("%s: (%q, %v)", c.name, gotVer, gotOK)
		}
	}
}

// TestHistoricalRegistryWiring checks the actual production constructors against
// the chunk mapper and client item catalog. A codec accepting login is not enough:
// every native block must use the same target ordering in updates and chunks.
func TestHistoricalRegistryWiring(t *testing.T) {
	for _, id := range []int32{748, 766, 827, 844, 898, 924, 944, 975} {
		t.Run(strconv.Itoa(int(id)), func(t *testing.T) {
			codec, ok := resolveProto(id)
			if !ok || !adapterHasRegistry(id) {
				t.Fatal("production codec is missing registry mapping")
			}
			items, _, err := clientItemRegistry(id)
			if err != nil {
				t.Fatal(err)
			}
			out := codec.ConvertFromLatest(&packet.ItemRegistry{Items: items}, nil)
			if len(out) != 1 {
				t.Fatalf("ItemRegistry dropped: %d packets", len(out))
			}
			reg, ok := unwrapWirePacket(out[0]).(*packet.ItemRegistry)
			if !ok {
				t.Fatalf("unexpected registry packet %T", out[0])
			}
			want, err := blocktranslate.Items(id)
			if err != nil {
				t.Fatal(err)
			}
			if !sameItemIDs(reg.Items, want) {
				t.Fatalf("codec item registry differs from client catalog: %d vs %d", len(reg.Items), len(want))
			}
			blocks, err := blocktranslate.NativeRegistry(id)
			if err != nil {
				t.Fatal(err)
			}
			mapper, ok := codec.(interface{ MapBlockRuntimeID(uint32) (uint32, bool) })
			if !ok {
				t.Fatal("codec has no block mapping hook")
			}
			native, err := blocktranslate.NativeRegistry(blocktranslate.NativeProtocol)
			if err != nil {
				t.Fatal(err)
			}
			chunkMapper, err := blocktranslate.New(blocktranslate.NativeProtocol, id, false, nil)
			if err != nil {
				t.Fatal(err)
			}
			checked := 0
			for n := 0; n < native.BlockCount(); n++ {
				target, valid := mapper.MapBlockRuntimeID(uint32(n))
				if !valid || target >= uint32(blocks.BlockCount()) {
					t.Fatalf("invalid target block %d -> %d", n, target)
				}
				name, props, _ := native.RuntimeIDToState(uint32(n))
				targetName, targetProps, _ := blocks.RuntimeIDToState(target)
				key, _ := mapping.StateKey(name, props)
				targetKey, _ := mapping.StateKey(targetName, targetProps)
				// The relay visibly substitutes info_update for absent states,
				// whereas upstream substitutes air. Compare only exact mappings.
				if key != targetKey {
					continue
				}
				chunkTarget, err := chunkMapper.Map(uint32(n))
				if err != nil || chunkTarget != target {
					t.Fatalf("chunk/update block mismatch %d: chunk=%d codec=%d err=%v", n, chunkTarget, target, err)
				}
				checked++
			}
			if checked == 0 {
				t.Fatal("no exact block mappings checked")
			}
			t.Logf("items=%d blocks=%d exact chunk/update mappings=%d", len(want), blocks.BlockCount(), checked)
		})
	}
}

// TestResolveHostProto pins the auto/forced/invalid behaviour of -host-proto.
func TestResolveHostProto(t *testing.T) {
	cases := []struct {
		flag      string
		announced int32
		wantID    int32
		wantKnown bool
	}{
		{"auto", 2169, 2169, true},
		{"auto", 0, 2193, false},
		{"auto", 99999, 2193, false},
		{"auto", -1, 2193, false},
		{"1001", 2169, 1001, true},
		{"2168", 0, 2168, true},
		{"garbage", 2169, 2193, false},
		{"7777", 0, 2193, false},
	}
	for _, c := range cases {
		id, known := resolveHostProto(c.flag, c.announced)
		if id != c.wantID || known != c.wantKnown {
			t.Errorf("resolveHostProto(%q, %d) = (%d, %v), want (%d, %v)",
				c.flag, c.announced, id, known, c.wantID, c.wantKnown)
		}
	}
}
