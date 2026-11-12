package lansignal

import (
	"bytes"
	"io"
	"log/slog"
	"net"
	"testing"
	"time"

	"github.com/df-mc/go-nethernet/discovery"
)

// LAN-255: a host must BROADCAST a ResponsePacket, not merely answer a
// RequestPacket. An offline/no-Xbox Bedrock client listens on 7551 and never
// gets a world card if only replyRequest exists.
func TestAnnounceBroadcastsResponsePacket(t *testing.T) {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))

	// Observer stands in for a Bedrock client listening on the discovery port.
	observer, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = observer.Close() })
	dst := observer.LocalAddr().(*net.UDPAddr)

	card := &discovery.ServerData{
		ServerName:            "mpmc Java",
		Protocol:              2225,
		Version:               "1.26.60",
		LevelName:             "Java 1.21.11 via Geyser",
		GameType:              discovery.GameTypeSurvival,
		PlayerCount:           0,
		MaxPlayerCount:        20,
		AcceptsOnlineAuth:     true,
		AcceptsSelfSignedAuth: true,
		ConnectionType:        4,
	}
	s, err := Listen(Options{
		Addr:        "127.0.0.1:0",
		NoBroadcast: true,
		Announce:    true,
		Broadcasts:  []*net.UDPAddr{dst},
		Advertise:   card,
		Log:         log,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })

	// A gophertunnel listener pushes its own pong every 4 s. A pinned
	// advertisement must survive it, otherwise the world card would flip back
	// to the listener's generic identity mid-session.
	s.PongData([]byte("MCPE;Generic;765;1.21.1;0;20;1;Other World;Survival;1;19132;19132;0,0;"))

	if err := observer.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 65535)
	n, _, err := observer.ReadFrom(buf)
	if err != nil {
		t.Fatalf("no LAN announcement received: %v", err)
	}
	pk, senderID, err := discovery.Unmarshal(buf[:n])
	if err != nil {
		t.Fatalf("announcement is not a valid discovery packet: %v", err)
	}
	if senderID != s.networkID {
		t.Fatalf("sender ID = %d, want our network ID %d", senderID, s.networkID)
	}
	resp, ok := pk.(*discovery.ResponsePacket)
	if !ok {
		t.Fatalf("packet is %T, want *discovery.ResponsePacket", pk)
	}
	got := &discovery.ServerData{}
	if err := got.UnmarshalBinary(resp.ApplicationData); err != nil {
		t.Fatalf("ResponsePacket carries undecodable ServerData: %v", err)
	}
	if got.ServerName != card.ServerName || got.LevelName != card.LevelName {
		t.Errorf("card identity = %q/%q, want %q/%q", got.ServerName, got.LevelName, card.ServerName, card.LevelName)
	}
	if got.Protocol != card.Protocol || got.Version != card.Version {
		t.Errorf("card protocol = %d/%q, want %d/%q", got.Protocol, got.Version, card.Protocol, card.Version)
	}
	if !got.AcceptsSelfSignedAuth {
		t.Error("AcceptsSelfSignedAuth=false: an offline/no-Xbox client cannot join")
	}
	if got.ConnectionType != 4 {
		t.Errorf("ConnectionType=%d, want 4 (LAN/NetherNet)", got.ConnectionType)
	}

	// The pinned bytes must be byte-identical to what SetAdvertisement stored.
	want, _ := card.MarshalBinary()
	if !bytes.Equal(resp.ApplicationData, want) {
		t.Error("announcement ServerData was overwritten by the listener's pong")
	}
}

// Announce is opt-in: a plain discovery client must not start advertising a
// world, otherwise every joiner would show up as a bogus LAN world.
func TestNoAnnounceByDefault(t *testing.T) {
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	observer, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = observer.Close() })

	s, err := Listen(Options{
		Addr:        "127.0.0.1:0",
		NoBroadcast: true,
		Broadcasts:  []*net.UDPAddr{observer.LocalAddr().(*net.UDPAddr)},
		Advertise:   &discovery.ServerData{ServerName: "Should not appear", ConnectionType: 4},
		Log:         log,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })

	if err := observer.SetReadDeadline(time.Now().Add(300 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 65535)
	if _, _, err := observer.ReadFrom(buf); err == nil {
		t.Fatal("a non-announcing Signaling emitted a discovery packet")
	}
}
