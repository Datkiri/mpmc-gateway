package main

import (
	"strings"
	"testing"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/sandertv/gophertunnel/minecraft/protocol/login"
)

// LAN-255 regression: a Bedrock client joining a NetherNet LAN world puts the
// world's network ID (a bare decimal uint64) into ClientData.ServerAddress.
// ClientData.Validate() used to insist on a resolvable host:port and closed the
// connection right after Login, so discovery/ICE/DTLS/SCTP all succeeded but
// the player never spawned.
func validLANClientData(serverAddress string) login.ClientData {
	return login.ClientData{
		DeviceOS:          1,
		GameVersion:       "1.26.60",
		LanguageCode:      "en_US",
		ServerAddress:     serverAddress,
		SkinResourcePatch: "e30=", // base64("{}")
		SkinID:            "lan-test-skin",
		UIProfile:         1,
	}
}

func TestLANClientDataAcceptsNumericNetworkID(t *testing.T) {
	if err := validLANClientData("1794498584672418500").Validate(); err != nil {
		t.Fatalf("numeric LAN network ID rejected: %v", err)
	}
}

func TestLANClientDataStillRejectsGarbage(t *testing.T) {
	err := validLANClientData("not-an-address").Validate()
	if err == nil {
		t.Fatal("a malformed ServerAddress must still be rejected")
	}
	if !strings.Contains(err.Error(), "ServerAddress") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestLANClientDataStillAcceptsHostPort(t *testing.T) {
	if err := validLANClientData("192.168.1.219:8555").Validate(); err != nil {
		t.Fatalf("legacy RakNet host:port must remain valid: %v", err)
	}
}

// LAN-255 regression: Geyser declares custom blocks in StartGame while keeping
// runtime IDs unhashed. blocktranslate.New used to hard-fail with "custom block
// runtime-ID ordering cannot be inferred", killing the session right after a
// successful login + host dial. Vanilla states must still translate; custom
// states must be counted as skipped instead of aborting.
func TestGeyserCustomBlocksDoNotKillCrossVersionSession(t *testing.T) {
	custom := []protocol.BlockEntry{
		{Name: "geyser:custom", Properties: map[string]any{"direction": int32(2)}},
	}
	s := &session{hostProto: 2225}
	if err := configureBlockTranslation(s, 2193, minecraft.GameData{
		UseBlockNetworkIDHashes: false,
		CustomBlocks:            custom,
	}); err != nil {
		t.Fatalf("cross-version session with Geyser custom blocks was killed: %v", err)
	}
	if s.blocks == nil || s.reverseBlocks == nil {
		t.Fatal("both directions must have a mapper")
	}
	if s.blocks.SkippedCustom() != len(custom) {
		t.Errorf("SkippedCustom()=%d, want %d", s.blocks.SkippedCustom(), len(custom))
	}
}
