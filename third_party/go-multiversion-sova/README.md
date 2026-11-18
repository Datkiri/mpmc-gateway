<p align="center">
  <img src="assets/go-multiversion-gopher.png" alt="go-multiversion gopher" width="180">
</p>

<h1 align="center">go-multiversion</h1>

<p align="center">Minecraft Bedrock multiversion adapters for gophertunnel and Dragonfly.</p>

## Protocols

The native wire model is stable **Minecraft 1.26.60 / protocol 2225**.
Historical adapters map directly to it, including the outgoing 1.26.50 family.
[Stable source locks](versions/1.26.60-native.md) distinguish the final data from
retained Preview acquisitions. Automated checks do not establish Mojang-client
gameplay compatibility.

| Protocol ID | Minecraft version | Adapter | Snapshot coverage | Wire tests |
|------------:|-------------------|---------|:-------:|:------:|
| 2225 | 1.26.60 | Native gophertunnel + Dragonfly hash bridge | ✅ | ✅ |
| 2193 | 1.26.50–1.26.52 | `v1_26_50` | ✅ | ✅ |
| 2169 | 1.26.45 | `v1_26_45` | ✅ | ✅ |
| 2168 | 1.26.40-1.26.44 | `v1_26_44` | ✅ | ✅ |
| 1001 | 1.26.30-1.26.34, 1.26.36 | `v1_26_30` | ✅ | ✅ |
| 975 | 1.26.20, 1.26.21, 1.26.23 | `v1_26_20` | ✅ | ✅ |
| 944 | 1.26.10-1.26.14 | `v1_26_10` | ✅ | ✅ |
| 924 | 1.26.0-1.26.3 | `v1_26_0` | ✅ | ✅ |
| 898 | 1.21.130-1.21.132 | `v1_21_130` | ✅ | ✅ |
| 860 | 1.21.124 | `v1_21_120` | ✅ | — |
| 859 | 1.21.120-1.21.123 | `v1_21_120` | ✅ | — |
| 844 | 1.21.110-1.21.114 | `v1_21_110` | ✅ | ✅ |
| 827 | 1.21.100-1.21.102 | `v1_21_100` | ✅ | ✅ |
| 819 | 1.21.93-1.21.94 | `v1_21_90` | ✅ | — |
| 818 | 1.21.90-1.21.92 | `v1_21_90` | ✅ | — |
| 800 | 1.21.80-1.21.84 | `v1_21_80` | ✅ | — |
| 786 | 1.21.70-1.21.73 | `v1_21_70` | ✅ | — |
| 776 | 1.21.60-1.21.62 | `v1_21_60` | ✅ | — |
| 766 | 1.21.50-1.21.51 | `v1_21_50` | ✅ | ✅ |
| 748 | 1.21.40, 1.21.41, 1.21.43, 1.21.44 | `v1_21_40` | ✅ | ✅ |
| 729 | 1.21.30-1.21.31 | `v1_21_30` | ✅ | — |
| 712 | 1.21.20-1.21.23 | `v1_21_20` | ✅ | — |
| 686 | 1.21.2-1.21.3 | `v1_21_0` | ✅ | — |
| 685 | 1.21.0-1.21.1 | `v1_21_0` | ✅ | — |
| 671 | 1.20.80-1.20.81 | `v1_20_80` | ✅ | — |
| 662 | 1.20.70-1.20.73 | `v1_20_70` | ✅ | — |
| 649 | 1.20.60-1.20.62 | `v1_20_60` | ✅ | — |
| 486 | 1.18.10-1.18.12 | `v1_18_10` | ✅ | ✅ |
| 475 | 1.18.0-1.18.2 | `v1_18_0` | ✅ | ✅ |
| 419 | 1.16.100 | `v1_16_100` | ✅ | ✅ |

Unlisted releases and preview builds are not implied.

[CI coverage and local commands](docs/ci.md) describe the automated checks.
[Dual public transport admission](docs/dual-transport.md) records the shared
RakNet/NetherNet session owner, implementation history and integration gates.

## Usage

Registry-aware adapters must be created after Dragonfly finalises its native
block registry:

```go
conf.AcceptedProtocolsProvider = func(blocks world.BlockRegistry) ([]minecraft.Protocol, error) {
	return (multiversion.Config{MinimumProtocol: 748}).ProtocolsWithRegistries(blocks, dragonfly.VanillaItemEntries())
}
```

`ProtocolsWithRegistries` enables the registry-aware adapters. The parameterless
`Protocols()` intentionally omits adapters that need native block and item registries.

`Config.MinimumProtocol` is an inclusive protocol-ID floor: `748` permits the
supported 1.21.40+ families, `0` (the default) keeps the full catalogue, and
`2225` permits only native 1.26.60.30 Preview. This does not enable unlisted versions.
The registry-aware catalogue includes the direct 2193 adapter and its frozen data.
Negative floors and floors newer than native are rejected. Pass the returned
adapters to the public listener. Matching Dragonfly installs its native hash bridge;
standalone proxies must append `dragonfly.NativeProtocol(blocks)` explicitly.
The package-level `ProtocolsWithRegistries` keeps its existing unrestricted
behaviour. See [gameplay regression evidence](versions/gameplay-effects-audit.md).

### Required integration

- Consumers need the matching [`shawtymarco/dragonfly`](https://github.com/shawtymarco/dragonfly)
  revision. Stock upstream Dragonfly does not provide the registry, protocol,
  or pre-hash palette hooks required by the historical adapters.
- Protocols `486`, `475`, and `419` also require the matching
  [`shawtymarco/gophertunnel`](https://github.com/shawtymarco/gophertunnel)
  revision for RakNet v10, Login-first negotiation, legacy flate batches, and
  protocol-specific encryption where required.
- Keep the consumer's immutable Dragonfly and gophertunnel fork revisions
  compatible with the native protocol pinned in [go.mod](go.mod).

## Credits

- [Sandertv/gophertunnel](https://github.com/Sandertv/gophertunnel)
- [df-mc/dragonfly](https://github.com/df-mc/dragonfly)
- [df-mc/worldupgrader](https://github.com/df-mc/worldupgrader)
- [Mojang/bedrock-protocol-docs](https://github.com/Mojang/bedrock-protocol-docs)
- [EndstoneMC/bedrock-server-data](https://github.com/EndstoneMC/bedrock-server-data)
- [TedacMC/tedac](https://github.com/TedacMC/tedac) for protocol-419 registry cross-checks
- Mascot adapted with OpenAI ImageGen from the [Go gopher](https://go.dev/blog/gopher) by Renee French, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
