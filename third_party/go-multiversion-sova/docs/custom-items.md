# Custom item registry conversion

The current native model is protocol 2225 (1.26.60.30 Preview); the 2193 and 2169
adapters retain the custom-registry contract below. The original 2169 investigation used Dragonfly
`bbbbc3c682ae1fb75894ee59092bc9ffdac3cd65` plus its public item-registry snapshot API.
That investigation used the wire baseline gophertunnel `7f058e5ddc39`; BRBW's published generic transport fork is
`1a193af9c670`. The stable native migration is recorded separately in `versions/1.26.5x.yaml`.

The existing configured item mapper froze vanilla-only registries. Additional component-based
non-Minecraft items had no target identity and were consequently omitted from inventories. Custom
definitions now extend, rather than overwrite, the pinned historical tables. ID allocation follows the
native custom registration order above the largest target ID and stays within int16. Unknown vanilla
items retain the existing historical fallback policy. Supplemental components never replace the
frozen identity mapping. Nested NBT must be independently cloned; the former block-state shallow
copy is not suitable for item components.

Protocol 419/475/486 uses ItemComponent (packet 0xa2): an unsigned varint count followed by each
identifier string and NetworkLittleEndian NBT. The identity/ID/component-based triples are already
in StartGame. This is verified against the historical gophertunnel sources for protocol 475
(`c40bf8288fb9`) and 486 (`2cb1e399`), rather than treating native ItemRegistry's fields as wire-compatible.
Protocols 748/766 already have the equivalent component-only packet codec. Their custom NBT must
carry the target ID and name; 419/475/486 also use the older icon `texture` property.
Later adapters use their established ItemRegistry codecs. The data-driven-items experiment is emitted
for legacy clients only when the frozen registry contains custom definitions.

Microsoft's [item icon reference](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/itemreference/examples/itemcomponents/minecraft_icon)
documents texture atlas keys and the current `textures.default` property versus the deprecated
single `texture` field. The server's target-era packet and component formats remain authoritative.

Automated evidence:
- mapping/custom_item_test.go verifies vanilla isolation, custom round trips, immutable component
  snapshots, supplemental lists and rejection of a changed backend runtime ID;
- BRBW shared/multiversion/menuitem_test.go exercises every enabled adapter and native protocol with
  the complete menu catalogue, component wire decoding, inventory and UseItem transaction round trips;
- the full library test/vet suites cover existing vanilla registry, creative, block and protocol oracles.

These are source and automated packet checks. Mojang-client visual/input, cold/warm pack-cache and
cross-backend gameplay checks remain required before claiming complete client validation.
