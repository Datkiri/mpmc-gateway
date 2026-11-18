# Convert the acquired .30 data to loader NBT

This offline converter uses the checked-in acquisition; it never starts BDS or
changes a consumer's dependency pin. Run from the repository root:

```sh
go run ./tools/normalize_native2225 -source data/native2225 -out tmp/normalized2225-new
go test ./internal/native2225 ./data/native2225
```

The output directory must not exist. The command authenticates every acquisition
file using `files.json` and generates all assets in memory before writing scratch
output. It reads the generated NBT back, reattaches packet metadata, reconstructs
native packets and compares every field with the capture. Promote only successful
output. Repeated conversion must reproduce all hashes in `normalization.json`.

When starting from the original extraction output, first preserve its item-indexed
`item_tags.json` as `raw/item_tags.json` and update that path in the acquisition
`files.json` without changing its checksum. The checked-in bundle already does this.
The converter emits the tag-indexed `item_tags.json` expected by the recipe loader.

Gameplay NBT schemas come from Dragonfly
`92ccc8ebb41e46c83114ac1c8d31dd0d6249f0cf`:

- `server/item/creative/creative.go`: groups, item name/meta/NBT/block properties,
  and explicit group indexes.
- `server/item/recipe/vanilla.go` and `item.go`: shaped/shapeless/user-data/multi,
  potion and smithing structures, including base/addition/template order.
- `server/item/recipe/item_tag.go`: tag to identifier-list JSON.

The library's historical `data/v1001` structs independently test compatibility
with those fields. Native packet fields absent from the old schema are preserved
in `packet_metadata.nbt`, not inserted into a file whose loader rejects them.
Biome definitions use a new typed projection of the captured modern packet;
Dragonfly currently builds those packets from its world registry rather than
loading a vanilla biome NBT file. Entity identifiers already are network NBT.

The current root dependency is native 2223; its packet layouts are identical to
the captured 2225 layouts for these packets, as established by the .30 source
comparison. Validate with both that committed pin and the published .30 fork
`4d6bffd4ed49ac9298d6abaa329415432a16f3e4` through a disposable modfile. The extractor
module already pins the latter. No direct historical-to-native conversion or live
registry is changed here.

Unsupported descriptors, absent IDs/hashes, invalid group indexes, material
reducers or biome chunk-generation data fail conversion rather than disappearing.
The ordinary .30 capture has no chemistry/reducer/generation payload. Furnace
families are present as named shapeless recipe stations and remain in that vector.
Preserve JSON provenance and auxiliary API tables; renaming all JSON as NBT would
not make those tables runtime schemas.
