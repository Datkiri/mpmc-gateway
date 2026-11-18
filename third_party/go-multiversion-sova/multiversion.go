// Package multiversion provides Minecraft Bedrock protocol adapters for
// gophertunnel-based servers.
package multiversion

import (
	"fmt"

	"github.com/sandertv/gophertunnel/minecraft"
	"github.com/sandertv/gophertunnel/minecraft/protocol"
	"github.com/shawtymarco/go-multiversion/internal/actors"
	"github.com/shawtymarco/go-multiversion/mapping"
	"github.com/shawtymarco/go-multiversion/protocols/v1_16_100"
	"github.com/shawtymarco/go-multiversion/protocols/v1_18_0"
	"github.com/shawtymarco/go-multiversion/protocols/v1_18_10"
	"github.com/shawtymarco/go-multiversion/protocols/v1_20_60"
	"github.com/shawtymarco/go-multiversion/protocols/v1_20_70"
	"github.com/shawtymarco/go-multiversion/protocols/v1_20_80"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_0"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_100"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_110"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_120"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_130"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_20"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_30"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_40"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_50"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_60"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_70"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_80"
	"github.com/shawtymarco/go-multiversion/protocols/v1_21_90"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_0"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_10"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_20"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_30"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_44"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_45"
	"github.com/shawtymarco/go-multiversion/protocols/v1_26_50"
)

// Protocols returns adapters that need no native registry configuration.
// Historical releases need mapping against the caller's 1.26.60 registries,
// so consumers must use ProtocolsWithRegistries to enable older clients.
func Protocols() []minecraft.Protocol {
	return nil
}

// V1_26_50 returns the wire-only protocol-2193 adapter. Gameplay consumers
// should use V1_26_50WithRegistries or the configured protocol catalogue.
func V1_26_50() minecraft.Protocol { return v1_26_50.New() }

// V1_26_50WithRegistries configures direct mapping from 1.26.50 to native.
func V1_26_50WithRegistries(native mapping.BlockRegistry, items []protocol.ItemEntry) (minecraft.Protocol, error) {
	return v1_26_50.NewWithRegistries(native, items)
}

// V1_26_45 returns the wire-only adapter for the outgoing native release.
func V1_26_45() minecraft.Protocol { return v1_26_45.New() }

// V1_26_45WithRegistries configures direct mapping from 1.26.45 to native.
func V1_26_45WithRegistries(native mapping.BlockRegistry, items []protocol.ItemEntry) (minecraft.Protocol, error) {
	return v1_26_45.NewWithRegistries(native, items)
}

// V1_26_30 returns the wire-only Minecraft protocol 1001 family adapter for
// Minecraft 1.26.30 through 1.26.36. The adapter is not included in Protocols
// until its registry and chunk conversions are complete.
func V1_26_30() minecraft.Protocol {
	return v1_26_30.New()
}

// V1_26_20 returns the wire-only Minecraft protocol 975 family adapter for
// stable Minecraft 1.26.20, 1.26.21, and 1.26.23. Registry-aware consumers
// should use ProtocolsWithRegistries before advertising it.
func V1_26_20() minecraft.Protocol {
	return v1_26_20.New()
}

// V1_26_10 returns the wire-only Minecraft protocol 944 family adapter for
// stable Minecraft 1.26.10 through 1.26.14. Registry-aware consumers should
// use ProtocolsWithRegistries before advertising it.
func V1_26_10() minecraft.Protocol {
	return v1_26_10.New()
}

// V1_26_0 returns the wire-only Minecraft protocol 924 family adapter for
// stable Minecraft 1.26.0 through 1.26.3. Registry-aware consumers should use
// ProtocolsWithRegistries before advertising it.
func V1_26_0() minecraft.Protocol {
	return v1_26_0.New()
}

// V1_21_130 returns the wire-only Minecraft protocol 898 family adapter for
// stable Minecraft 1.21.130 through 1.21.132. Registry-aware consumers should
// use ProtocolsWithRegistries before advertising it.
func V1_21_130() minecraft.Protocol {
	return v1_21_130.New()
}

// V1_21_110 returns the wire-only Minecraft protocol 844 family adapter for
// stable Minecraft 1.21.110 through 1.21.114. Registry-aware consumers should
// use ProtocolsWithRegistries before advertising it.
func V1_21_110() minecraft.Protocol {
	return v1_21_110.New()
}

// V1_21_100 returns the wire-only Minecraft protocol 827 family adapter for
// stable Minecraft 1.21.100 through 1.21.102. Registry-aware consumers should
// use ProtocolsWithRegistries before advertising it.
func V1_21_100() minecraft.Protocol {
	return v1_21_100.New()
}

// V1_21_50 returns the wire-only Minecraft protocol 766 family adapter for
// stable Minecraft 1.21.50 and 1.21.51.
func V1_21_50() minecraft.Protocol { return v1_21_50.New() }

// V1_21_40 returns the wire-only Minecraft protocol 748 family adapter for
// stable Minecraft 1.21.40, 1.21.41, 1.21.43, and 1.21.44.
func V1_21_40() minecraft.Protocol { return v1_21_40.New() }

// V1_18_10 returns the wire-only Minecraft protocol 486 family adapter for
// stable Minecraft 1.18.10 through 1.18.12. Registry-aware consumers should
// use ProtocolsWithRegistries before advertising it.
func V1_18_10() minecraft.Protocol {
	return v1_18_10.New()
}

// V1_18_0 returns the wire-only Minecraft protocol 475 family adapter for
// stable Minecraft 1.18.0 through 1.18.2.
func V1_18_0() minecraft.Protocol { return v1_18_0.New() }

// V1_16_100 returns the wire-only Minecraft protocol 419 adapter. Registry-aware consumers should use
// ProtocolsWithRegistries before advertising it.
func V1_16_100() minecraft.Protocol { return v1_16_100.New() }

// V1_21_110WithBlockRegistry returns a configured protocol-844 adapter using
// a current native block registry. Item mapping is initialised from ItemRegistry.
func V1_21_110WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_21_110.NewWithBlockRegistry(native)
}

// V1_21_100WithBlockRegistry returns a configured protocol-827 adapter using
// a current native block registry. Item mapping is initialised from ItemRegistry.
func V1_21_100WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_21_100.NewWithBlockRegistry(native)
}

// V1_18_10WithBlockRegistry returns a configured protocol-486 adapter using
// a current native block registry. Item mapping is initialised from ItemRegistry.
func V1_18_10WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_18_10.NewWithBlockRegistry(native)
}

func V1_21_50WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_21_50.NewWithBlockRegistry(native)
}

func V1_21_40WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_21_40.NewWithBlockRegistry(native)
}

func V1_18_0WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_18_0.NewWithBlockRegistry(native)
}

func V1_16_100WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_16_100.NewWithBlockRegistry(native)
}

// V1_26_30WithBlockRegistry returns a fully configured protocol-1001 adapter
// using a current native block registry.
func V1_26_30WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_26_30.NewWithBlockRegistry(native)
}

// V1_26_20WithBlockRegistry returns a configured protocol-975 adapter using a
// current native block registry. Item mapping is initialised from ItemRegistry.
func V1_26_20WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_26_20.NewWithBlockRegistry(native)
}

// V1_26_10WithBlockRegistry returns a configured protocol-944 adapter using a
// current native block registry. Item mapping is initialised from ItemRegistry.
func V1_26_10WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_26_10.NewWithBlockRegistry(native)
}

// V1_26_0WithBlockRegistry returns a configured protocol-924 adapter using a
// current native block registry. Item mapping is initialised from ItemRegistry.
func V1_26_0WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_26_0.NewWithBlockRegistry(native)
}

// V1_21_130WithBlockRegistry returns a configured protocol-898 adapter using
// a current native block registry. Item mapping is initialised from ItemRegistry.
func V1_21_130WithBlockRegistry(native mapping.BlockRegistry) (minecraft.Protocol, error) {
	return v1_21_130.NewWithBlockRegistry(native)
}

// ProtocolsWithBlockRegistry returns all verified non-native protocols,
// including protocol 1001 configured against the current block registry.
func ProtocolsWithBlockRegistry(native mapping.BlockRegistry) ([]minecraft.Protocol, error) {
	if native == nil {
		return nil, fmt.Errorf("native block registry is nil")
	}
	return []minecraft.Protocol{
		newLazy(v1_26_44.ID, v1_26_44.Version, func() (minecraft.Protocol, error) { return v1_26_44.NewWithBlockRegistry(native) }),
		newLazy(v1_26_30.ID, v1_26_30.Version, func() (minecraft.Protocol, error) { return V1_26_30WithBlockRegistry(native) }),
		newLazy(v1_26_20.ID, v1_26_20.Version, func() (minecraft.Protocol, error) { return V1_26_20WithBlockRegistry(native) }),
		newLazy(v1_26_10.ID, v1_26_10.Version, func() (minecraft.Protocol, error) { return V1_26_10WithBlockRegistry(native) }),
		newLazy(v1_26_0.ID, v1_26_0.Version, func() (minecraft.Protocol, error) { return V1_26_0WithBlockRegistry(native) }),
		newLazy(v1_21_130.ID, v1_21_130.Version, func() (minecraft.Protocol, error) { return V1_21_130WithBlockRegistry(native) }),
		newLazy(v1_21_120.Hotfix, v1_21_120.HotfixVersion, func() (minecraft.Protocol, error) { return v1_21_120.NewHotfixWithBlockRegistry(native) }),
		newLazy(v1_21_120.ID, v1_21_120.Version, func() (minecraft.Protocol, error) { return v1_21_120.NewWithBlockRegistry(native) }),
		newLazy(v1_21_110.ID, v1_21_110.Version, func() (minecraft.Protocol, error) { return V1_21_110WithBlockRegistry(native) }),
		newLazy(v1_21_100.ID, v1_21_100.Version, func() (minecraft.Protocol, error) { return V1_21_100WithBlockRegistry(native) }),
		newLazy(v1_21_90.Hotfix, v1_21_90.HotfixVersion, func() (minecraft.Protocol, error) { return v1_21_90.NewHotfixWithBlockRegistry(native) }),
		newLazy(v1_21_90.ID, v1_21_90.Version, func() (minecraft.Protocol, error) { return v1_21_90.NewWithBlockRegistry(native) }),
		newLazy(v1_21_80.ID, v1_21_80.Version, func() (minecraft.Protocol, error) { return v1_21_80.NewWithBlockRegistry(native) }),
		newLazy(v1_21_70.ID, v1_21_70.Version, func() (minecraft.Protocol, error) { return v1_21_70.NewWithBlockRegistry(native) }),
		newLazy(v1_21_60.ID, v1_21_60.Version, func() (minecraft.Protocol, error) { return v1_21_60.NewWithBlockRegistry(native) }),
		newLazy(v1_21_50.ID, v1_21_50.Version, func() (minecraft.Protocol, error) { return V1_21_50WithBlockRegistry(native) }),
		newLazy(v1_21_40.ID, v1_21_40.Version, func() (minecraft.Protocol, error) { return V1_21_40WithBlockRegistry(native) }),
		newLazy(v1_21_30.ID, v1_21_30.Version, func() (minecraft.Protocol, error) { return v1_21_30.NewWithBlockRegistry(native) }),
		newLazy(v1_21_20.ID, v1_21_20.Version, func() (minecraft.Protocol, error) { return v1_21_20.NewWithBlockRegistry(native) }),
		newLazy(v1_21_0.Hotfix, v1_21_0.HotfixVersion, func() (minecraft.Protocol, error) { return v1_21_0.NewHotfixWithBlockRegistry(native) }),
		newLazy(v1_21_0.ID, v1_21_0.Version, func() (minecraft.Protocol, error) { return v1_21_0.NewWithBlockRegistry(native) }),
		newLazy(v1_20_80.ID, v1_20_80.Version, func() (minecraft.Protocol, error) { return v1_20_80.NewWithBlockRegistry(native) }),
		newLazy(v1_20_70.ID, v1_20_70.Version, func() (minecraft.Protocol, error) { return v1_20_70.NewWithBlockRegistry(native) }),
		newLazy(v1_20_60.ID, v1_20_60.Version, func() (minecraft.Protocol, error) { return v1_20_60.NewWithBlockRegistry(native) }),
		newLazy118(v1_18_10.ID, v1_18_10.Version, func() (minecraft.Protocol, error) { return V1_18_10WithBlockRegistry(native) }),
		newLazy118(v1_18_0.ID, v1_18_0.Version, func() (minecraft.Protocol, error) { return V1_18_0WithBlockRegistry(native) }),
		newLazy116(v1_16_100.ID, v1_16_100.Version, func() (minecraft.Protocol, error) { return V1_16_100WithBlockRegistry(native) }),
		newLazy(v1_26_45.ID, v1_26_45.Version, func() (minecraft.Protocol, error) { return v1_26_45.NewWithBlockRegistry(native) }),
		newLazy(v1_26_50.ID, v1_26_50.Version, func() (minecraft.Protocol, error) { return v1_26_50.NewWithBlockRegistry(native) }),
	}, nil
}

// ProtocolsWithRegistries returns all verified adapters after eagerly
// validating the current block and item registries.
func ProtocolsWithRegistries(native mapping.BlockRegistry, nativeItems []protocol.ItemEntry) ([]minecraft.Protocol, error) {
	if native == nil {
		return nil, fmt.Errorf("native block registry is nil")
	}
	return []minecraft.Protocol{
		newLazy(v1_26_44.ID, v1_26_44.Version, func() (minecraft.Protocol, error) { return v1_26_44.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_26_30.ID, v1_26_30.Version, func() (minecraft.Protocol, error) { return v1_26_30.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_26_20.ID, v1_26_20.Version, func() (minecraft.Protocol, error) { return v1_26_20.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_26_10.ID, v1_26_10.Version, func() (minecraft.Protocol, error) { return v1_26_10.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_26_0.ID, v1_26_0.Version, func() (minecraft.Protocol, error) { return v1_26_0.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_130.ID, v1_21_130.Version, func() (minecraft.Protocol, error) { return v1_21_130.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_120.Hotfix, v1_21_120.HotfixVersion, func() (minecraft.Protocol, error) { return v1_21_120.NewHotfixWithRegistries(native, nativeItems) }),
		newLazy(v1_21_120.ID, v1_21_120.Version, func() (minecraft.Protocol, error) { return v1_21_120.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_110.ID, v1_21_110.Version, func() (minecraft.Protocol, error) { return v1_21_110.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_100.ID, v1_21_100.Version, func() (minecraft.Protocol, error) { return v1_21_100.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_90.Hotfix, v1_21_90.HotfixVersion, func() (minecraft.Protocol, error) { return v1_21_90.NewHotfixWithRegistries(native, nativeItems) }),
		newLazy(v1_21_90.ID, v1_21_90.Version, func() (minecraft.Protocol, error) { return v1_21_90.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_80.ID, v1_21_80.Version, func() (minecraft.Protocol, error) { return v1_21_80.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_70.ID, v1_21_70.Version, func() (minecraft.Protocol, error) { return v1_21_70.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_60.ID, v1_21_60.Version, func() (minecraft.Protocol, error) { return v1_21_60.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_50.ID, v1_21_50.Version, func() (minecraft.Protocol, error) { return v1_21_50.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_40.ID, v1_21_40.Version, func() (minecraft.Protocol, error) { return v1_21_40.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_30.ID, v1_21_30.Version, func() (minecraft.Protocol, error) { return v1_21_30.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_20.ID, v1_21_20.Version, func() (minecraft.Protocol, error) { return v1_21_20.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_21_0.Hotfix, v1_21_0.HotfixVersion, func() (minecraft.Protocol, error) { return v1_21_0.NewHotfixWithRegistries(native, nativeItems) }),
		newLazy(v1_21_0.ID, v1_21_0.Version, func() (minecraft.Protocol, error) { return v1_21_0.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_20_80.ID, v1_20_80.Version, func() (minecraft.Protocol, error) { return v1_20_80.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_20_70.ID, v1_20_70.Version, func() (minecraft.Protocol, error) { return v1_20_70.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_20_60.ID, v1_20_60.Version, func() (minecraft.Protocol, error) { return v1_20_60.NewWithRegistries(native, nativeItems) }),
		newLazy118(v1_18_10.ID, v1_18_10.Version, func() (minecraft.Protocol, error) { return v1_18_10.NewWithRegistries(native, nativeItems) }),
		newLazy118(v1_18_0.ID, v1_18_0.Version, func() (minecraft.Protocol, error) { return v1_18_0.NewWithRegistries(native, nativeItems) }),
		newLazy116(v1_16_100.ID, v1_16_100.Version, func() (minecraft.Protocol, error) { return v1_16_100.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_26_45.ID, v1_26_45.Version, func() (minecraft.Protocol, error) { return v1_26_45.NewWithRegistries(native, nativeItems) }),
		newLazy(v1_26_50.ID, v1_26_50.Version, func() (minecraft.Protocol, error) { return v1_26_50.NewWithRegistries(native, nativeItems) }),
	}, nil
}

// V1_26_44 returns the Minecraft protocol 2168 family adapter. The adapter
// supports Minecraft 1.26.40 through 1.26.44 and keeps this function name for
// API compatibility.
func V1_26_44() minecraft.Protocol {
	return v1_26_44.New()
}

// MapEntity registers a replacement shown to historical clients for an entity
// identifier they do not know, such as a custom projectile. Without one, such
// entities are shown as pigs.
func MapEntity(from, to string) {
	actors.Map(from, to)
}
