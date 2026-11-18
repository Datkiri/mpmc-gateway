# Minecraft 1.26.60.30 Preview acquisition

This is a locally extracted source bundle for protocol 2225 with validated loader
NBT assets. It does not activate a protocol, replace Dragonfly's live registries,
or establish Mojang-client support.
The .29 source bundle remains unchanged in `../native2223`.

## Exact source

Official Linux archive SHA256:
`bace5dae627a66020c6a6af3a25881ebda7f585b9fa3dadee23edfb566596574`.
Extracted `bedrock_server` SHA256:
`2c76ff3184906259f3964e5bf2ab9db414f1570b8179a3561637a134a7de72e7`.
The server reports version `1.26.60.30`, build `52213776`, branch `r/26_u6`,
commit `38a19575b5b57f01eb6ec27ccc646182e0cbe6f7`, ELF build ID
`1bc418cce3ada55b1e4e0a7e30235ab79b59b59a`.

See [source.json](source.json) for the acquisition settings, tool/source locks and
scope, [files.json](files.json) for output hashes, and
[validation.json](validation.json) for decoded counts and checks.
No executables, world database, credentials or runtime logs are committed.

The initial capture used an unmodified vanilla BDS and a local automated RakNet
client in a Linux network namespace containing only loopback. Although this build
prints a NetherNet-only warning, its RakNet endpoint accepted this probe; that is
an extraction observation, not a supported-client/platform claim. No external
service or Xbox account was contacted inside that namespace.

After explicit user approval, a script-only behavior pack enumerated the live BDS
registries and saved states through the engine's structure serializer. It adds no
blocks, items or recipes. It uses `@minecraft/server` 2.0.0; no injected hooks or
unverified native offsets are required. Sources and runtime work stayed under the
task's ignored `tmp/native2225/`; scripts are retained in
[the extraction tool](../../tools/extract_native2225/README.md).

## Assets and validation

- `block_states.nbt`: 23,960 typed compound roots, NetworkLittleEndian, across
  1,480 identifiers. Ordered deterministically by the JSON key `[name, states]`;
  file indexes are **not** vanilla runtime IDs. Hashed network IDs are calculated
  from canonical LittleEndian `{name, states}` using FNV-1a32, with the existing
  `minecraft:unknown` sentinel. All 1,020 nonzero creative block hashes resolve.
- `vanilla_items.nbt`: 2,080 entries in Dragonfly's identifier-keyed item schema;
  signed IDs, component flags, version and typed component NBT match ItemRegistry.
- `data_driven_blocks.nbt`: the 98 StartGame block definitions with typed NBT.
- `raw/*.bin`: six exact **packet bodies without packet headers**, from vanilla
  BDS: StartGame, ItemRegistry, CreativeContent, CraftingData,
  BiomeDefinitionList and AvailableActorIdentifiers. No login packets are retained.
- `raw/item_tags.json`: direct `ItemStack.getTags()` queries for all 2,080 native names.
  ItemTypes enumeration exposes only 1,623; querying captured names resolves the
  rest without missing entries. `block_to_item.json` records each default block
  permutation's item and tags; a null item is preserved as unavailable.
- `block_state_domains.json` and `script_validation.json`: extraction coverage and
  the native-read evidence for special state candidates.

Ordinary API enumeration serializes 23,940 states. Global state domains and API
aliases omit 12 chalkboard directions, six awake creaking-heart variants, active
sculk sensor, and structure void. Twenty .29 states were used **only as probe
inputs**: the .30 BDS loaded them, returned native BlockPermutations and saved a
second structure. The output uses that .30 serialization. Structure void cannot
be assigned by `setBlockPermutation`, which is recorded, rather than silently
dropped. This is script/serializer enumeration plus targeted native round-trips,
not a direct memory traversal of the internal BlockPalette container.

All 23,960 output records match .29 by identifier, complete property values, NBT
numeric types and version `18168865`. This establishes equality for these block
records; it does not establish equality of every registry, recipe or engine rule.
The separate acquisition is retained as .30 provenance, not wired into runtime
tables as duplicate gameplay data.

Captured creative content has 124 groups and 1,980 entries; biome data has 89
definitions. CraftingData has 1,219 shaped, 2,068 shapeless, 1,456 user-data
shapeless, 14 multi, 12 smithing-transform, one smithing-trim, 210 potion and two
potion-container recipes. Chemistry and material-reducer vectors are empty in
this ordinary world. All six packet bodies decode with zero trailing bytes and
preserve typed values through encode/decode without mutating their inputs.
The decoder is the published fork `4d6bffd4ed49ac9298d6abaa329415432a16f3e4`.
Asset normalization was repeated and every output checksum reproduced.

## Loader NBT conversion

[normalization.json](normalization.json) locks the inputs and generated hashes.
The converter is [tools/normalize_native2225](../../tools/normalize_native2225/README.md).
All generated files use NetworkLittleEndian NBT:

| File | Loader content |
| --- | --- |
| `creative_items.nbt` | 124 groups and 1,980 entries; exact named block properties and typed stack NBT |
| `crafting_data.nbt` | 1,219 shaped, 2,068 shapeless, 1,456 user-data shapeless and 14 multi recipes |
| `smithing_data.nbt` | 12 transform recipes, inputs ordered base/addition/template |
| `smithing_trim_data.nbt` | One trim recipe, same input order and no invented output |
| `potion_data.nbt` | 210 potion and two container-change recipes |
| `biome_definitions.nbt` | 89 modern typed definitions, dictionary and optional-tag presence |
| `entity_identifiers.nbt` | The original entity compound, already NBT on the wire |
| `packet_metadata.nbt` | Recipe identities, network IDs, unlock conditions, symmetry and creative selection/stack metadata |

Creative, crafting, smithing and potion files use the exact existing Dragonfly
NBT field schemas at `92ccc8ebb41e46c83114ac1c8d31dd0d6249f0cf`, also represented
by the historical `data/v1001` loaders. Those decoders reject unknown fields, so
extra native packet fields live in the separate metadata file. The new typed
accessors combine both parts. Tests read through the existing loader structs and
reconstruct complete native CreativeContent, CraftingData and BiomeDefinitionList
packets, checking all typed values against the original capture.

`item_tags.json` now has the existing loader's **tag → item names** direction:
77 tags, including all 17 referenced recipe tags. The original item → tags source
was moved unchanged to `raw/item_tags.json`; `files.json` retains its original hash.
Some native descriptor names contain a matching `:aux` suffix. The gameplay field
uses the verified item identifier; metadata preserves the original wire string.
All-false block properties are retained, and numeric IDs are resolved only against
this exact .30 registry/hash table.

The earlier statement that furnace data were absent was incorrect: shapeless
recipes contain 134 furnace, 62 blast-furnace, nine smoker, nine campfire and nine
soul-campfire entries. They are preserved in `crafting_data.nbt` by crafting station.
No new capture or older-version substitution was needed.

## Remaining boundary

This captures the specified vanilla world and script-visible registries. It does
not assert an unrestricted native memory walk, enabled education/experiment
profiles, or a complete replacement for all auxiliary BedrockData tables. Legacy
upgrade maps, block physics and other data absent from these packets/APIs are not
fabricated or copied from .29. Installing these assets into the live Dragonfly
registries, supporting their content in gameplay, historical semantic mapping and
real-client validation remain separate work. Decoding a file is not proof that
every new block/item already has a Dragonfly gameplay implementation.
