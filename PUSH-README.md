# mpmc-gateway — snapshot công việc 2026-11-12

Repo **private**, tạo mới để lưu toàn bộ trạng thái công việc Java → Bedrock gateway
sau khi repo công khai cũ `Datkiri/bedrock-cross-version-relay` không còn tồn tại trên GitHub.

## Nội dung

| Đường dẫn | Ý nghĩa |
|---|---|
| `agent-core/`, `agent-cli/`, `coordinator/`, `protocol/`, `relay/`, `transport-iroh/`, `it/`, `packaging/`, `plugins/`, `m0-spike/`, `gradle/` | Source Kotlin/Java của agent M0–M6 |
| `docs/` | `M6-BEDROCK.md` (§0 + 1005 dòng), `M6-PACKET-INVENTORY.md` (bản .18), và docs M1–M5, RUNBOOK, MEASUREMENTS |
| `_m5/`, `_m6/` | Probe CLI, script audit, script inventory, build log text, `build-push-snapshot.py` |
| `gateway-patches/viabedrock-src/` | Toàn bộ source Java đã patch của ViaBedrock mpmc.18 |
| `gateway-patches/*.patch` | Diff so với base upstream, `git apply` được |

Không đưa vào repo: fat jar, build output, BDS binary, `node_modules`, log runtime,
`session.json`, `saves.json`, file token. Repo chỉ chứa source/text.

## Pin phát hành

- `PATCHED_VERSION = 3.4.14-mpmc.18`
- `PATCHED_SHA256 = bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8`
- `gateway verify`: `VERIFY_EXIT=0`; zip `mpmc-agent-0.1.0.zip` không chứa `ViaProxy-*.jar`.

## Regression động .18 (đã đo, `vp-audit18-reg.log`)

44.164 dòng audit; PRE 21.988 = POST 19.737 + CANCEL 2.211 + DEFERRED 40;
`toHost=4189`; 0 ERROR / 0 unmapped / 0 unhandled / 0 NPE / 0 PROXY KICK;
creative 50 accepted / 0 rejected; `INTERACT toHost=11`; `USE_ITEM_ON seq=1..80`
(không còn `seq=0`). GUI thật 1.21.1: 26 accepted / 0 rejected, 0 exception.

## Chưa xác minh

Lỗi GUI-only `ItemStackNetResult status 6 / status 60` (log `vp-live-25569.clean.log`)
vẫn **[Chưa xác minh]**: CLI probe không tái hiện được (trade-repro 6/6 accept,
longrun 150 s 47/47 accept). Không đánh dấu PASS.

## Ranh giới GPL / §0

ViaBedrock + ViaProxy đã patch là **tiến trình GPL-3.0 riêng**, KHÔNG nhúng vào
`mpmc-agent-*.zip`. Patch ở đây chỉ để tái dựng bản build; không đưa vào sản phẩm MIT.
Giữ nguyên §0: không sửa byte sau HELLO, không spoof/tắt online-mode, không parse/inject
sau Encryption Response, proxy chỉ bind loopback, không log secret/token, chỉ iroh+iroh-relay,
không TUN/TAP/admin, không copy AGPL/GPL/all-rights-reserved code, không bỏ qua M0.

## Repo liên quan

- `Datkiri/bedrock-cross-version-relay` (private, tạo lại): Go relay xvrelay,
  commit `e3bb416` = native protocol 2225 / Bedrock 1.26.60.
- Base fork: ViaBedrock `3756cc0`, ViaProxy `1cc5364`.
- Commit local: ViaBedrock `8e5162e`, ViaProxy `bd4af0a` (branch `mpmc.18`).
