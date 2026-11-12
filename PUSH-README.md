# mpmc-gateway — snapshot công việc 2026-11-12

Repo **private**, tạo mới để lưu toàn bộ trạng thái công việc Java → Bedrock gateway
sau khi repo công khai cũ `Datkiri/bedrock-cross-version-relay` không còn tồn tại trên GitHub.

## Nội dung

| Đường dẫn | Ý nghĩa |
|---|---|
| `agent-core/`, `agent-cli/`, `coordinator/`, `protocol/`, `relay/`, `transport-iroh/`, `it/`, `packaging/`, `plugins/`, `m0-spike/`, `gradle/` | Source Kotlin/Java của agent M0–M6 |
| `docs/` | `M6-BEDROCK.md` (§0 + §10.7, 1266 dòng), `M6-PACKET-INVENTORY.md` (bản .18), và docs M1–M5, RUNBOOK, MEASUREMENTS |
| `_m5/`, `_m6/` | Probe CLI, script audit, script inventory, build log text, `build-push-snapshot.py` |
| `gateway-patches/viabedrock-src/` | Toàn bộ source Java đã patch của ViaBedrock mpmc.18 |
| `gateway-patches/*.patch` | Diff so với base upstream, `git apply` được |
| `lan255/` | Bằng chứng wire-level broadcast `.255` (`lan255-capture.txt`) + diff `xver-lan255.patch` (`e3bb416..59b350a`) |

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

## LAN-255: broadcast `.255` cho client Bedrock không Xbox (12/11, 23:36)

Chiều ngược `client Bedrock LAN (self-signed) → xvrelay -listen-nethernet → Geyser →
ViaProxy → Java 1.21.11`. Host phải **chủ động** phát `ResponsePacket`; trả lời
`RequestPacket` không đủ vì client không Xbox chỉ lắng nghe thẻ LAN ở Friends tab.

- Wire capture 8 s (`lan255/lan255-capture.txt`): **16** gói `→ 255.255.255.255:7551`,
  **16** gói `→ 192.168.1.255:7551` từ `192.168.1.219:8555`. Giải mã HMAC-SHA256 +
  AES-256-ECB đúng key ⇒ `ResponsePacket`, `ServerData v7`:
  `accepts_online_auth=False`, `accepts_self_signed_auth=True`, `connection_type=4`,
  `protocol=2225`, `1.26.60`, `level=mpmc-Java-1.21.11`.
- Join thật: `self_signed=true online=false announced=true answered=true` → spawn →
  chat 1/1. Java server ghi `LANJoinerFinal joined the game` +
  `[Not Secure] <LANJoinerFinal> hello-from-LAN-offline`. Relay 189 gói H→C,
  `skipped=0`, 0 ERROR. `CGO_ENABLED=0 go test -buildvcs=false` 4/4 package `ok`.
- `lan255/xver-lan255.patch` = diff `e3bb416..59b350a` của Go relay (Announce/
  Advertise/Broadcasts, `announceLoop`, `-lan-offline-auth`, fix `ClientData.Validate`
  nhận network ID thập phân, degrade custom block Geyser). Lịch sử đầy đủ nằm ở
  branch **`xver-relay`** của chính repo này, HEAD `59b350a`.
- Chi tiết: `docs/M6-BEDROCK.md §10.7`. Firewall mở 7551/8555/19164/19132/19134
  (LocalSubnet) + rule theo program cho mọi port ICE ephemeral.

## Chưa xác minh

- Lỗi GUI-only `ItemStackNetResult status 6 / status 60` (log `vp-live-25569.clean.log`)
  vẫn **[Chưa xác minh]**: CLI probe không tái hiện được (trade-repro 6/6 accept,
  longrun 150 s 47/47 accept). Không đánh dấu PASS.
- Client Bedrock **đồ hoạ gốc từ thiết bị LAN thứ hai**, protocol 2225 qua NetherNet
  (terminal test client thiếu codec 2225), TOFU prompt, WAN: **[Chưa xác minh]**.

## Ranh giới GPL / §0

ViaBedrock + ViaProxy đã patch là **tiến trình GPL-3.0 riêng**, KHÔNG nhúng vào
`mpmc-agent-*.zip`. Patch ở đây chỉ để tái dựng bản build; không đưa vào sản phẩm MIT.
Giữ nguyên §0: không sửa byte sau HELLO, không spoof/tắt online-mode, không parse/inject
sau Encryption Response, proxy chỉ bind loopback, không log secret/token, chỉ iroh+iroh-relay,
không TUN/TAP/admin, không copy AGPL/GPL/all-rights-reserved code, không bỏ qua M0.
Branch `xver-relay` chứa `third_party/` (gophertunnel/go-nethernet fork) và
`java/lib/*.jar` dependency có sẵn từ commit `e3bb416`; đã rà 25 commit, **0 token/secret**.

## Repo liên quan

- ~~`Datkiri/bedrock-cross-version-relay`~~: repo công khai cũ đã biến mất, repo private
  tạo lại nhầm **đã xóa 12/11** (`gh api` 404). **Không tạo lại.** Commit local giữ nguyên.
- `Datkiri/mpmc-gateway` là **repo duy nhất** được phép: `main` = snapshot này,
  `xver-relay` = lịch sử Go relay (HEAD `59b350a`).
- Base fork: ViaBedrock `3756cc0`, ViaProxy `1cc5364`.
- Commit local: ViaBedrock `8e5162e`, ViaProxy `bd4af0a` (branch `mpmc.18`).
