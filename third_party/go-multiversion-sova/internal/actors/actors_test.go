package actors_test

import (
	"testing"

	v419 "github.com/shawtymarco/go-multiversion/data/v419"
	v475 "github.com/shawtymarco/go-multiversion/data/v475"
	"github.com/shawtymarco/go-multiversion/internal/actors"
)

func TestTranslate(t *testing.T) {
	l116 := actors.MustLoad(v419.EntityIdentifiers())
	l118 := actors.MustLoad(v475.EntityIdentifiers())
	actors.Map("custom:shuriken", "minecraft:snowball")
	cases := []struct {
		list     *actors.List
		in, want string
	}{
		{l116, "minecraft:zombie", "minecraft:zombie"},
		{l116, "minecraft:ender_pearl", "minecraft:ender_pearl"},
		{l116, "minecraft:goat", "minecraft:sheep"},
		{l118, "minecraft:goat", "minecraft:goat"},
		{l116, "minecraft:wind_charge_projectile", "minecraft:snowball"},
		{l116, "custom:shuriken", "minecraft:snowball"},
		{l116, "custom:unknown", actors.Fallback},
		{l118, "minecraft:breeze", actors.Fallback},
	}
	for _, c := range cases {
		if got := c.list.Translate(c.in); got != c.want {
			t.Errorf("Translate(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}
