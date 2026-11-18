package mapping

import (
	"fmt"
	"hash/fnv"
	"reflect"
	"slices"
	"sort"
	"strings"
	"sync"

	"github.com/df-mc/worldupgrader/blockupgrader"
)

// legacyBlockNames maps native blocks that were split out of a single legacy
// block whose variant lived in block entity data rather than in the block
// state, so no historical state upgrades into them.
var legacyBlockNames = map[string]legacyBlock{
	"minecraft:skeleton_skull":        legacySkull,
	"minecraft:wither_skeleton_skull": legacySkull,
	"minecraft:zombie_head":           legacySkull,
	"minecraft:player_head":           legacySkull,
	"minecraft:creeper_head":          legacySkull,
	"minecraft:dragon_head":           legacySkull,
	"minecraft:piglin_head":           legacySkull,
}

// legacyBlock is the legacy identifier of a split block together with the
// states only the legacy block carried, tried when the native states alone
// do not match.
type legacyBlock struct {
	name     string
	defaults map[string]any
}

var legacySkull = legacyBlock{name: "minecraft:skull", defaults: map[string]any{"no_drop_bit": uint8(0)}}

func (b legacyBlock) variants(properties map[string]any) []map[string]any {
	extended := cloneProperties(properties)
	for key, value := range b.defaults {
		if _, ok := extended[key]; !ok {
			extended[key] = value
		}
	}
	return []map[string]any{properties, extended}
}

// BlockRegistry is the native registry surface required to build a direct
// protocol mapping. Dragonfly's world.BlockRegistry satisfies this interface.
type BlockRegistry interface {
	BlockCount() int
	AirRuntimeID() uint32
	RuntimeIDToState(runtimeID uint32) (name string, properties map[string]any, found bool)
}

// BlockState is one historical runtime block state.
type BlockState struct {
	Name       string
	Properties map[string]any
	Version    int32
}

// BlockFallback records one native state that protocol 1001 cannot represent.
type BlockFallback struct {
	NativeRuntimeID uint32
	Name            string
	Properties      map[string]any
	TargetRuntimeID uint32
}

// BlockMapper maps current native runtime IDs directly to historical target
// runtime IDs and back using identifier plus complete typed properties.
type BlockMapper struct {
	nativeToTarget []uint32
	nativeExact    []bool
	nativeStates   []BlockState
	targetToNative []uint32
	targetStates   []BlockState
	targetByKey    map[string]uint32
	fallbacks      []BlockFallback
	targetAir      uint32
}

// NewBlockMapper builds an immutable direct mapping. Historical states are
// ordered using the stable name-only FNV-1 sort used by recent Dragonfly block
// registries before their runtime IDs are assigned.
func NewBlockMapper(native BlockRegistry, historical []BlockState) (*BlockMapper, error) {
	return newBlockMapper(native, historical, true)
}

// NewBlockMapperWithTargetOrder builds an immutable direct mapping while
// preserving the input historical state order as the target runtime-ID order.
// It is used by releases whose Dragonfly registry registered the embedded NBT
// stream directly without a final sorting pass.
func NewBlockMapperWithTargetOrder(native BlockRegistry, historical []BlockState) (*BlockMapper, error) {
	return newBlockMapper(native, historical, false)
}

// nativeTable is the immutable lookup data of one native registry. It is
// shared by every block mapper built against that registry, as all supported
// protocols map to the same native states.
type nativeTable struct {
	states  []BlockState
	byKey   map[string]uint32
	aliases map[string]uint32
	// keys, aliasKeys and compatibleKeys hold the state key, the compatibility
	// alias key and the key without native-only visual properties of every
	// runtime ID. stripped reports whether that last key differs.
	keys           []string
	aliasKeys      []string
	compatibleKeys []string
	stripped       []bool
}

var (
	nativeTablesMu sync.Mutex
	nativeTables   = map[BlockRegistry]*nativeTable{}

	internedMu     sync.Mutex
	internedStates = map[string]internedState{}
)

type internedState struct {
	key   string
	state BlockState
}

// nativeTableOf returns the shared lookup data of native, building it on the
// first call. Registries that cannot be used as map keys are not cached.
func nativeTableOf(native BlockRegistry) (*nativeTable, error) {
	cacheable := reflect.TypeOf(native).Comparable()
	if cacheable {
		nativeTablesMu.Lock()
		defer nativeTablesMu.Unlock()
		if table, ok := nativeTables[native]; ok && len(table.states) == native.BlockCount() {
			return table, nil
		}
	}
	table, err := buildNativeTable(native)
	if err != nil {
		return nil, err
	}
	if cacheable {
		nativeTables[native] = table
	}
	return table, nil
}

func buildNativeTable(native BlockRegistry) (*nativeTable, error) {
	count := native.BlockCount()
	table := &nativeTable{
		states:         make([]BlockState, count),
		byKey:          make(map[string]uint32, count),
		aliases:        make(map[string]uint32),
		keys:           make([]string, count),
		aliasKeys:      make([]string, count),
		compatibleKeys: make([]string, count),
		stripped:       make([]bool, count),
	}
	for index := range native.BlockCount() {
		runtimeID := uint32(index)
		name, properties, ok := native.RuntimeIDToState(runtimeID)
		if !ok {
			return nil, fmt.Errorf("native runtime ID %d has no state", runtimeID)
		}
		key, err := StateKey(name, properties)
		if err != nil {
			return nil, fmt.Errorf("native runtime ID %d: %w", runtimeID, err)
		}
		if _, exists := table.byKey[key]; exists {
			return nil, fmt.Errorf("duplicate native block state %s at runtime ID %d", name, runtimeID)
		}
		table.byKey[key] = runtimeID
		aliasKey, err := StateKey(name, nativeCompatibilityProperties(name, properties))
		if err != nil {
			return nil, fmt.Errorf("native runtime ID %d compatibility alias: %w", runtimeID, err)
		}
		if aliasKey == key {
			aliasKey = key
		} else {
			if existing, exists := table.aliases[aliasKey]; exists && existing != runtimeID {
				return nil, fmt.Errorf("duplicate native compatibility state %s at runtime IDs %d and %d", name, existing, runtimeID)
			}
			table.aliases[aliasKey] = runtimeID
		}
		table.states[index] = BlockState{Name: name, Properties: properties}
		table.keys[index], table.aliasKeys[index] = key, aliasKey
		stripped := stripNativeCompatibilityProperties(properties)
		table.compatibleKeys[index], table.stripped[index] = key, len(stripped) != len(properties)
		if table.stripped[index] {
			if table.compatibleKeys[index], err = StateKey(name, stripped); err != nil {
				return nil, fmt.Errorf("native runtime ID %d compatible state: %w", runtimeID, err)
			}
		}
	}
	return table, nil
}

// internState returns a canonical key and state sharing their memory with
// every equal state of other protocols. Mapper states are never mutated, so
// historical palettes of different protocols can share them.
func internState(key string, state BlockState) (string, BlockState) {
	internedMu.Lock()
	defer internedMu.Unlock()
	canonical, ok := internedStates[key]
	if !ok {
		canonical = internedState{key: key, state: BlockState{Name: state.Name, Properties: state.Properties}}
		internedStates[key] = canonical
	}
	return canonical.key, BlockState{Name: canonical.state.Name, Properties: canonical.state.Properties, Version: state.Version}
}

type upgradeKey struct {
	key     string
	version int32
}

var (
	upgradedMu   sync.Mutex
	upgradedKeys = map[upgradeKey]string{}
)

// upgradedStateKey returns the state key of a historical state after
// upgrading it to the current version. Results are shared between protocols,
// most of which carry the same states.
func upgradedStateKey(key string, state BlockState) (string, bool) {
	id := upgradeKey{key: key, version: state.Version}
	upgradedMu.Lock()
	upgraded, ok := upgradedKeys[id]
	upgradedMu.Unlock()
	if ok {
		return upgraded, upgraded != ""
	}
	result := blockupgrader.Upgrade(blockupgrader.BlockState{
		Name:       state.Name,
		Properties: cloneProperties(state.Properties),
		Version:    state.Version,
	})
	upgraded, err := StateKey(result.Name, result.Properties)
	if err != nil {
		upgraded = ""
	}
	upgradedMu.Lock()
	upgradedKeys[id] = upgraded
	upgradedMu.Unlock()
	return upgraded, upgraded != ""
}

func newBlockMapper(native BlockRegistry, historical []BlockState, sortTarget bool) (*BlockMapper, error) {
	if native == nil {
		return nil, fmt.Errorf("native block registry is nil")
	}
	targetStates := append([]BlockState(nil), historical...)
	if sortTarget {
		sort.SliceStable(targetStates, func(i, j int) bool {
			return fnv1String(targetStates[i].Name) < fnv1String(targetStates[j].Name)
		})
	}

	targetByKey := make(map[string]uint32, len(targetStates))
	targetKeys := make([]string, len(targetStates))
	targetAir, foundAir := uint32(0), false
	for index, state := range targetStates {
		key, err := StateKey(state.Name, state.Properties)
		if err != nil {
			return nil, fmt.Errorf("target runtime ID %d: %w", index, err)
		}
		if _, exists := targetByKey[key]; exists {
			return nil, fmt.Errorf("duplicate target block state %s at runtime ID %d", state.Name, index)
		}
		key, targetStates[index] = internState(key, state)
		targetKeys[index] = key
		targetByKey[key] = uint32(index)
		if state.Name == "minecraft:air" {
			targetAir, foundAir = uint32(index), true
		}
	}
	if !foundAir {
		return nil, fmt.Errorf("target block registry has no minecraft:air state")
	}

	table, err := nativeTableOf(native)
	if err != nil {
		return nil, err
	}
	nativeByKey, nativeAliases, nativeStates := table.byKey, table.aliases, table.states

	targetToNative := make([]uint32, len(targetStates))
	targetCompatibleByNativeKey := make(map[string]uint32, len(targetStates))
	unresolved := make([]string, 0)
	for targetRuntimeID, state := range targetStates {
		nativeRuntimeID, ok := nativeByKey[targetKeys[targetRuntimeID]]
		if !ok {
			// New native releases may move client-derived visual state into the
			// server registry. Older targets omit these properties entirely, so
			// resolve them to the neutral native state while preserving every
			// property the target did carry.
			for _, properties := range nativeCompatibilityVariants(state.Properties) {
				compatibleKey, err := StateKey(state.Name, properties)
				if err != nil {
					return nil, fmt.Errorf("compatible target runtime ID %d: %w", targetRuntimeID, err)
				}
				if nativeRuntimeID, ok = nativeByKey[compatibleKey]; ok {
					break
				}
			}
		}
		if !ok {
			upgraded := blockupgrader.Upgrade(blockupgrader.BlockState{
				Name:       state.Name,
				Properties: cloneProperties(state.Properties),
				Version:    state.Version,
			})
			upgradedKey, err := StateKey(upgraded.Name, upgraded.Properties)
			if err != nil {
				return nil, fmt.Errorf("upgraded target runtime ID %d: %w", targetRuntimeID, err)
			}
			nativeRuntimeID, ok = nativeByKey[upgradedKey]
			if !ok {
				nativeRuntimeID, ok = nativeAliases[upgradedKey]
			}
			if !ok {
				for _, properties := range nativeCompatibilityVariants(upgraded.Properties) {
					compatibleKey, err := StateKey(upgraded.Name, properties)
					if err != nil {
						return nil, fmt.Errorf("compatible upgraded target runtime ID %d: %w", targetRuntimeID, err)
					}
					if nativeRuntimeID, ok = nativeByKey[compatibleKey]; ok {
						break
					}
				}
			}
			if !ok {
				if len(unresolved) < 32 {
					unresolved = append(unresolved, fmt.Sprintf("%s%v (RID %d) -> %s%v", state.Name, state.Properties, targetRuntimeID, upgraded.Name, upgraded.Properties))
				}
				continue
			}
		}
		targetToNative[targetRuntimeID] = nativeRuntimeID
		compatibleKey := table.compatibleKeys[nativeRuntimeID]
		if _, exists := targetCompatibleByNativeKey[compatibleKey]; !exists {
			targetCompatibleByNativeKey[compatibleKey] = uint32(targetRuntimeID)
		}
	}
	if len(unresolved) != 0 {
		return nil, fmt.Errorf("target registry has unresolved native states:\n%s", strings.Join(unresolved, "\n"))
	}

	nativeToTarget := make([]uint32, native.BlockCount())
	nativeExact := make([]bool, native.BlockCount())
	for runtimeID := range nativeToTarget {
		nativeToTarget[runtimeID] = targetAir
		if targetRuntimeID, ok := targetByKey[table.keys[runtimeID]]; ok {
			nativeToTarget[runtimeID], nativeExact[runtimeID] = targetRuntimeID, true
			continue
		}
		if targetRuntimeID, ok := targetByKey[table.aliasKeys[runtimeID]]; ok {
			nativeToTarget[runtimeID], nativeExact[runtimeID] = targetRuntimeID, true
			continue
		}
		if table.stripped[runtimeID] {
			compatibleKey := table.compatibleKeys[runtimeID]
			if targetRuntimeID, ok := targetByKey[compatibleKey]; ok {
				nativeToTarget[runtimeID], nativeExact[runtimeID] = targetRuntimeID, true
				continue
			}
			if targetRuntimeID, ok := targetCompatibleByNativeKey[compatibleKey]; ok {
				nativeToTarget[runtimeID], nativeExact[runtimeID] = targetRuntimeID, true
			}
		}
	}
	for targetRuntimeID, nativeRuntimeID := range targetToNative {
		if !nativeExact[nativeRuntimeID] {
			nativeToTarget[nativeRuntimeID], nativeExact[nativeRuntimeID] = uint32(targetRuntimeID), true
		}
	}
	if slices.Contains(nativeExact, false) {
		// A target state that still exists natively resolves to itself above, so
		// a native state it was later flattened into (brown_mushroom_block with
		// huge_mushroom_bits 15 into mushroom_stem) has no reverse mapping yet.
		for targetRuntimeID, state := range targetStates {
			upgradedKey, ok := upgradedStateKey(targetKeys[targetRuntimeID], state)
			if !ok {
				continue
			}
			nativeRuntimeID, ok := nativeByKey[upgradedKey]
			if !ok {
				nativeRuntimeID, ok = nativeAliases[upgradedKey]
			}
			if ok && !nativeExact[nativeRuntimeID] {
				nativeToTarget[nativeRuntimeID], nativeExact[nativeRuntimeID] = uint32(targetRuntimeID), true
			}
		}
		for runtimeID, exact := range nativeExact {
			if exact {
				continue
			}
			state := nativeStates[runtimeID]
			legacy, ok := legacyBlockNames[state.Name]
			if !ok {
				continue
			}
			for _, properties := range legacy.variants(state.Properties) {
				legacyKey, err := StateKey(legacy.name, properties)
				if err != nil {
					continue
				}
				if targetRuntimeID, ok := targetByKey[legacyKey]; ok {
					nativeToTarget[runtimeID], nativeExact[runtimeID] = targetRuntimeID, true
					break
				}
			}
		}
	}
	fallbacks := make([]BlockFallback, 0)
	for runtimeID, exact := range nativeExact {
		if exact {
			continue
		}
		state := nativeStates[runtimeID]
		fallbacks = append(fallbacks, BlockFallback{
			NativeRuntimeID: uint32(runtimeID),
			Name:            state.Name,
			Properties:      state.Properties,
			TargetRuntimeID: targetAir,
		})
	}

	return &BlockMapper{
		nativeToTarget: nativeToTarget,
		nativeExact:    nativeExact,
		nativeStates:   nativeStates,
		targetToNative: targetToNative,
		targetStates:   targetStates,
		targetByKey:    targetByKey,
		fallbacks:      fallbacks,
		targetAir:      targetAir,
	}, nil
}

// NativeState returns the current block state represented by runtimeID.
func (m *BlockMapper) NativeState(runtimeID uint32) (BlockState, bool) {
	if m == nil || runtimeID >= uint32(len(m.nativeStates)) {
		return BlockState{}, false
	}
	state := m.nativeStates[runtimeID]
	state.Properties = cloneProperties(state.Properties)
	return state, true
}

// TargetState returns the historical block state represented by runtimeID.
func (m *BlockMapper) TargetState(runtimeID uint32) (BlockState, bool) {
	if m == nil || runtimeID >= uint32(len(m.targetStates)) {
		return BlockState{}, false
	}
	state := m.targetStates[runtimeID]
	state.Properties = cloneProperties(state.Properties)
	return state, true
}

// NativeToTarget maps a current runtime ID to protocol 1001. exact is false
// when the explicit minecraft:air fallback was used.
func (m *BlockMapper) NativeToTarget(runtimeID uint32) (target uint32, exact bool) {
	if m == nil || runtimeID >= uint32(len(m.nativeToTarget)) {
		if m == nil {
			return 0, false
		}
		return m.targetAir, false
	}
	return m.nativeToTarget[runtimeID], m.nativeExact[runtimeID]
}

// MapNative maps a current runtime ID and distinguishes an explicit fallback
// from an out-of-range ID.
func (m *BlockMapper) MapNative(runtimeID uint32) (target uint32, valid bool, exact bool) {
	if m == nil || runtimeID >= uint32(len(m.nativeToTarget)) {
		return 0, false, false
	}
	return m.nativeToTarget[runtimeID], true, m.nativeExact[runtimeID]
}

// TargetToNative maps a protocol-1001 runtime ID to the exact current state.
func (m *BlockMapper) TargetToNative(runtimeID uint32) (uint32, bool) {
	if m == nil || runtimeID >= uint32(len(m.targetToNative)) {
		return 0, false
	}
	return m.targetToNative[runtimeID], true
}

// TargetRuntimeID resolves a historical state by identifier and properties.
func (m *BlockMapper) TargetRuntimeID(name string, properties map[string]any) (uint32, bool) {
	if m == nil {
		return 0, false
	}
	key, err := StateKey(name, properties)
	if err != nil {
		return 0, false
	}
	runtimeID, ok := m.targetByKey[key]
	return runtimeID, ok
}

// TargetStates returns the historical states in runtime-ID order.
func (m *BlockMapper) TargetStates() []BlockState {
	if m == nil {
		return nil
	}
	states := make([]BlockState, len(m.targetStates))
	for index, state := range m.targetStates {
		states[index] = BlockState{Name: state.Name, Properties: cloneProperties(state.Properties), Version: state.Version}
	}
	return states
}

// Fallbacks returns every native state substituted with historical air.
func (m *BlockMapper) Fallbacks() []BlockFallback {
	if m == nil {
		return nil
	}
	fallbacks := make([]BlockFallback, len(m.fallbacks))
	for index, fallback := range m.fallbacks {
		fallbacks[index] = fallback
		fallbacks[index].Properties = cloneProperties(fallback.Properties)
	}
	return fallbacks
}

// TargetAir returns the protocol-1001 air runtime ID.
func (m *BlockMapper) TargetAir() uint32 {
	if m == nil {
		return 0
	}
	return m.targetAir
}

// NativeCount returns the current registry size used to build the mapping.
func (m *BlockMapper) NativeCount() int {
	if m == nil {
		return 0
	}
	return len(m.nativeToTarget)
}

// TargetCount returns the historical registry size.
func (m *BlockMapper) TargetCount() int {
	if m == nil {
		return 0
	}
	return len(m.targetToNative)
}

func cloneProperties(properties map[string]any) map[string]any {
	if properties == nil {
		return nil
	}
	cloned := make(map[string]any, len(properties))
	for key, value := range properties {
		cloned[key] = value
	}
	return cloned
}

// nativeCompatibilityProperties normalises known third-party Dragonfly block
// implementations that still expose an old property name while representing a
// current vanilla state. bedrock-gophers/inv registers its dropper container
// with toggle_bit, whereas current and historical registries use
// triggered_bit. Treating the names as semantic aliases keeps the consumer's
// synthetic implementation mappable without weakening unresolved target-state
// validation for unrelated blocks.
func nativeCompatibilityProperties(name string, properties map[string]any) map[string]any {
	cloned := cloneProperties(properties)
	if name != "minecraft:dropper" && name != "minecraft:dispenser" {
		return cloned
	}
	if _, exists := cloned["triggered_bit"]; exists {
		return cloned
	}
	if value, exists := cloned["toggle_bit"]; exists {
		delete(cloned, "toggle_bit")
		cloned["triggered_bit"] = value
	}
	return cloned
}

var nativeCompatibilityDefaults = map[string]any{
	"minecraft:corner":           "none",
	"minecraft:connection_north": false,
	"minecraft:connection_east":  false,
	"minecraft:connection_south": false,
	"minecraft:connection_west":  false,
}

func nativeCompatibilityVariants(properties map[string]any) []map[string]any {
	variants := make([]map[string]any, 0, 2)
	if _, exists := properties["minecraft:corner"]; !exists {
		withCorner := cloneProperties(properties)
		if withCorner == nil {
			withCorner = map[string]any{}
		}
		withCorner["minecraft:corner"] = "none"
		variants = append(variants, withCorner)
	}
	missingConnection := false
	for name := range nativeCompatibilityDefaults {
		if name == "minecraft:corner" {
			continue
		}
		if _, exists := properties[name]; !exists {
			missingConnection = true
		}
	}
	if missingConnection {
		withConnections := cloneProperties(properties)
		if withConnections == nil {
			withConnections = map[string]any{}
		}
		for name, value := range nativeCompatibilityDefaults {
			if name != "minecraft:corner" {
				withConnections[name] = value
			}
		}
		variants = append(variants, withConnections)
		// Native 26.60 fire adds an upper flame face as well. Keep the
		// four-face candidate for panes/fences and try the five-face state
		// separately; adding an upper face to every block would be invalid.
		withUpper := cloneProperties(withConnections)
		withUpper["minecraft:connection_up"] = false
		variants = append(variants, withUpper)
		withVertical := cloneProperties(withUpper)
		withVertical["minecraft:connection_down"] = false
		variants = append(variants, withVertical)
	}
	if _, wire := properties["redstone_signal"]; wire {
		withWire := cloneProperties(properties)
		for _, key := range []string{"redstone_north", "redstone_east", "redstone_south", "redstone_west"} {
			if _, exists := withWire[key]; !exists {
				withWire[key] = "none"
			}
		}
		variants = append(variants, withWire)
	}
	return variants
}

func stripNativeCompatibilityProperties(properties map[string]any) map[string]any {
	stripped := cloneProperties(properties)
	for name := range nativeCompatibilityDefaults {
		delete(stripped, name)
	}
	delete(stripped, "minecraft:connection_up")
	delete(stripped, "minecraft:connection_down")
	for _, key := range []string{"redstone_north", "redstone_east", "redstone_south", "redstone_west"} {
		delete(stripped, key)
	}
	return stripped
}

func fnv1String(value string) uint64 {
	hash := fnv.New64()
	_, _ = hash.Write([]byte(value))
	return hash.Sum64()
}
