// Package actors restricts entity identifiers to those a historical client
// knows, replacing unknown ones the way the client-side identifier list
// requires. Unknown identifiers in AddActor crash pre-1.19 clients.
package actors

import (
	"bytes"
	"fmt"
	"sync"

	"github.com/sandertv/gophertunnel/minecraft/nbt"
)

// Fallback is used for identifiers that are unknown to the client and have no
// known replacement.
const Fallback = "minecraft:pig"

var (
	mu        sync.RWMutex
	overrides = map[string]string{
		"minecraft:goat":                          "minecraft:sheep",
		"minecraft:axolotl":                       "minecraft:tropicalfish",
		"minecraft:glow_squid":                    "minecraft:squid",
		"minecraft:wind_charge_projectile":        "minecraft:snowball",
		"minecraft:breeze_wind_charge_projectile": "minecraft:snowball",
	}
)

// Map registers a replacement for an entity identifier that historical
// clients do not know. The replacement is only used if the client knows it.
func Map(from, to string) {
	mu.Lock()
	defer mu.Unlock()
	overrides[from] = to
}

// List is the entity identifier list of a historical protocol.
type List struct {
	raw   []byte
	known map[string]struct{}
}

// Load decodes a network NBT entity identifier list.
func Load(raw []byte) (*List, error) {
	var root map[string]any
	if err := nbt.NewDecoderWithEncoding(bytes.NewReader(raw), nbt.NetworkLittleEndian).Decode(&root); err != nil {
		return nil, fmt.Errorf("decode entity identifiers: %w", err)
	}
	entries, _ := root["idlist"].([]any)
	known := make(map[string]struct{}, len(entries))
	for _, entry := range entries {
		if m, ok := entry.(map[string]any); ok {
			if id, ok := m["id"].(string); ok {
				known[id] = struct{}{}
			}
		}
	}
	if len(known) == 0 {
		return nil, fmt.Errorf("decode entity identifiers: empty list")
	}
	return &List{raw: raw, known: known}, nil
}

// MustLoad is Load for embedded snapshots.
func MustLoad(raw []byte) *List {
	l, err := Load(raw)
	if err != nil {
		panic(err)
	}
	return l
}

// Raw returns the serialised list to send in AvailableActorIdentifiers.
func (l *List) Raw() []byte {
	return l.raw
}

// Translate returns an identifier the client knows for id.
func (l *List) Translate(id string) string {
	if _, ok := l.known[id]; ok {
		return id
	}
	mu.RLock()
	to, ok := overrides[id]
	mu.RUnlock()
	if _, known := l.known[to]; ok && known {
		return to
	}
	return Fallback
}
