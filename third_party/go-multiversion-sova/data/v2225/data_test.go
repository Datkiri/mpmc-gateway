package v2225

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

func TestFrozenNativeSnapshotIntegrity(t *testing.T) {
	bytes, err := os.ReadFile("manifest.json")
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		SourceCommit string `json:"source_commit"`
		Snapshots    []struct {
			File, SHA256 string
			Bytes        int
		}
	}
	if err := json.Unmarshal(bytes, &manifest); err != nil {
		t.Fatal(err)
	}
	if manifest.SourceCommit != "d86aefd9ff9b6a357d6dfadab2093af811545256" || len(manifest.Snapshots) != 7 {
		t.Fatal("incomplete stable native source lock")
	}
	for _, file := range manifest.Snapshots {
		data, ok := RawSnapshot(file.File)
		if !ok {
			t.Fatal(file.File)
		}
		hash := sha256.Sum256(data)
		if len(data) != file.Bytes || hex.EncodeToString(hash[:]) != file.SHA256 {
			t.Fatalf("snapshot changed: %s", file.File)
		}
	}
	states, err := BlockStates()
	if err != nil || len(states) != 23960 {
		t.Fatalf("block count=%d err=%v", len(states), err)
	}
	items, err := Items()
	if err != nil || len(items) != 2080 {
		t.Fatalf("item count=%d err=%v", len(items), err)
	}
	if _, err := Creative(); err != nil {
		t.Fatal(err)
	}
	if _, err := Crafting(); err != nil {
		t.Fatal(err)
	}
	if _, err := Potions(); err != nil {
		t.Fatal(err)
	}
	if _, err := BlockDefinitions(); err != nil {
		t.Fatal(err)
	}
}
