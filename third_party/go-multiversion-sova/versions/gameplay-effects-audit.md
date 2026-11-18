# Gameplay effect and block-reference regressions

The audit starts at `ce15505303d164c4e3f97197f1631ac1e21850a9` and uses
native protocol 2193, gophertunnel fork `bb499bd613064ce16264769b55f7578fc826a7dc`,
and Dragonfly fork `22545660b36a20404a31a318c7fc957fedb0ba33` in the consumer.
The stable native wire/data source lock is [1.26.5x.yaml](1.26.5x.yaml).
Historical source revisions, registry hashes, ordering, and patch aliases remain
locked in the corresponding `versions/*.yaml` and `data/v*/manifest.yaml` files.
No transport, packet layout, registry snapshot, or supported-version list changes.

| Boundary | Reproduced failure and correction |
| --- | --- |
| 924, 944, 975, 1001, 2168, 2169 block sounds | `ExtraData` kept the native block RID. Map the nine block-backed sound payloads directly in both directions. Preserve note payloads and negative sentinels. |
| 2168/2169 terrain particles | Legacy particle event 21 retained the native RID. Apply the same block mapping as destruction/crack particles. |
| All historical block interactions | RID zero was treated as absent even for block clicks. Map zero for block interactions, including nested PlayerAuthInput, retaining the zero sentinel on air clicks. |
| All historical packed item effects | Unsigned extraction corrupted negative int16 item IDs. Sign-extend the item ID while preserving the low metadata bits. Omit unrepresentable item particles. |
| 419/475/486 doors and unsupported numeric sounds | A 475/486 door sound could panic during encoding. Use the historical door-toggle LevelEvent 1003; omit other unknown numeric sounds instead of encoding an unrelated default or invalid enum. |

Dragonfly `5ac88dcd` and `677c8fa1`, `server/session/world.go`, send `sound.Door`
as LevelEvent 1003 with position and no block RID. The generic toggle fallback
intentionally loses modern material/open-close distinctions. Native Dragonfly
`22545660` is the source for sound block payloads, terrain particle 21, icon
particle 14, destruction RID and crack-face packing. Gophertunnel `283a5a9`
and `7a556a0` confirm that the string-sound payload layout itself did not change.
Unsupported modern sounds in string-based targets remain client-defined.

`TestGameplayEffectsAcrossProtocols` covers every historical adapter with actual
snapshot states upgraded to the native schema, deliberately reordered IDs,
lime wool at native zero, and a negative native item ID. It verifies wire block
IDs before reverse mapping, target packet decoding with no unread bytes,
input preservation, block correction, embedded interaction data, legacy particle
enum shifts, signed item payloads, unknown-effect drops, and sound sentinels.
This fixture exercises the conversion boundary; consumer tests additionally use
the real native Dragonfly registry and the public listener.

`Config.MinimumProtocol` owns generic minimum-ID validation and catalogue
selection. A consumer passes its configuration to this API instead of duplicating
selection rules. Zero preserves existing behaviour; native remains accepted;
unlisted protocol IDs never become enabled by choosing a floor.

Validation commands: `go test -mod=readonly ./...`, `go vet -mod=readonly ./...`,
and `git diff --check`. Automated evidence is not a Mojang-client visual test.
Real-client sound, particle, prediction rollback, and cache checks remain pending.
