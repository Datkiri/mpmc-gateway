# Export the verified native 2225 data

Run from this repository with an existing matching Dragonfly checkout:

```sh
go run ./tools/export_native2225 -out /path/to/dragonfly
```

The command verifies the embedded source hashes, prepares all nine assets, and
writes Dragonfly's existing block/item/creative/recipe loader files plus
`server/world/native_snapshot.json`. Data-driven block definitions are converted
from the captured network NBT map to Dragonfly's little-endian `blocks` container.
The manifest records both source and installed hashes and the acquisition revision.

The block ordering is local and is not a vanilla numeric network palette. Use
Dragonfly's matching `NativeProtocol` hash adapter and a finalized local registry;
historical numeric clients need their own frozen palettes. Exact source and
acquisition limits are in [the snapshot documentation](../../data/native2225/README.md).

This tool only writes loader assets in the requested checkout. It does not publish
dependencies, change consumer pins or establish native-client gameplay support.
