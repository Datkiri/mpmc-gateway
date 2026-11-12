# mpmc — P2P cho Minecraft Java

Tunnel TCP P2P (iroh, direct hoặc relay) để một người chơi Minecraft Java kết nối vào server LAN của
bạn qua Internet **mà không cần mở port, không cần VPS host game, không cần TUN/TAP**.

MVP hoàn tất đến M5 (Java↔Java); M6 thêm **Java client → server Bedrock** qua ViaProxy chạy phía host.

## Cách hoạt động

```
Minecraft client → 127.0.0.1:<cổng join>  (agent join)
      → iroh QUIC stream (direct hoặc qua relay)
      → agent host → 127.0.0.1:25565 (server Minecraft của bạn)
```

Với server Bedrock, host thêm `--bedrock <host:port>`: mpmc tự chạy ViaProxy (tiến trình ngoài,
GPL-3.0, tải lúc chạy + pin SHA-256) ở `127.0.0.1:25568`, rồi tunnel trỏ vào cổng đó:

```
Java client → join → iroh → host → 127.0.0.1:25568 (ViaProxy) → RakNet UDP → BDS
```

- Chỉ chuyển **byte thô** của luồng TCP sau khi bắt tay HELLO của mpmc; không đọc/sửa gói Minecraft.
- Mã phòng không phải xác thực duy nhất: cần invite token 128-bit **hoặc** chủ phòng duyệt +
  join ticket 120 s gắn với endpoint id.
- Proxy chỉ bind `127.0.0.1`; phía join không chọn được đích TCP (host tự dial `target` cấu hình).
- Không log `Authorization`, `hostToken`, `inviteToken`, join ticket.

## Cấu trúc

| Module | Nội dung |
|---|---|
| `protocol` | DTO, mã phòng, ticket Ed25519, frame `MPMP` v1 |
| `coordinator` | Ktor + PostgreSQL: auth challenge/JWT, phòng, join/approve, ticket, JWKS, relay callback, metrics, events WS |
| `transport-iroh` | Facade iroh JVM (`computer.iroh:iroh:1.2.0`), relay mode N0/Custom/Disabled |
| `agent-core` | HostService, JoinService, LocalProxy, TunnelBridge, BedrockGateway |
| `agent-cli` | CLI `host`, `join`, `gateway`, `doctor` |
| `it` | TunnelIT, AuthIT, RelayIT, CliProcessIT |
| `relay` | Cấu hình iroh-relay + Caddyfile + docker compose |
| `packaging` | Script chạy, systemd unit, config/env mẫu |
| `m0-spike` | Spike đo gate M0 (giữ làm bằng chứng) |

## Chạy nhanh (một máy, không cần coordinator)

```bash
./gradlew.bat :agent-cli:installDist
agent-cli/build/install/mpmc/bin/mpmc doctor
# 1) Host — in ra "Endpoint host: <hex64>", giữ nguyên cửa sổ này
agent-cli/build/install/mpmc/bin/mpmc host --insecure-dev-ticket \
  --target 127.0.0.1:25565 --bind 127.0.0.1:41999
# 2) Join — dán endpoint id vừa in; mã phòng dev mặc định DEV2345
agent-cli/build/install/mpmc/bin/mpmc join --insecure-dev-ticket \
  --dev-host-endpoint <hex64> --code DEV2345 \
  --direct 127.0.0.1:41999 --listen 127.0.0.1:25566
# 3) Minecraft: connect 127.0.0.1:25566
```

Host server Bedrock (cần ViaProxy, tự tải lần đầu):

```bash
agent-cli/build/install/mpmc/bin/mpmc gateway install
agent-cli/build/install/mpmc/bin/mpmc host --insecure-dev-ticket \
  --bedrock 127.0.0.1:19132 --bind 127.0.0.1:42002
# join không đổi: client Java kết nối 127.0.0.1:25566
```

Để lấy đồ creative và đặt/đào block trên Bedrock 1.26.40+, cài bản gateway `mpmc-patched` (build
GPL cục bộ, giữ ngoài gói phát hành) thay cho bản chính thức:

```bash
agent-cli/build/install/mpmc/bin/mpmc gateway install --from <ViaProxy-3.4.14.jar>
agent-cli/build/install/mpmc/bin/mpmc gateway status   # phải ghi "mpmc-patched"
```

Chế độ production (coordinator + invite/approve + relay): xem [docs/RUNBOOK.md](docs/RUNBOOK.md).
Chi tiết Bedrock, phiên bản và ranh giới giấy phép: [docs/M6-BEDROCK.md](docs/M6-BEDROCK.md).

## Tài liệu

- [M1 — Coordinator](docs/M1-COORDINATOR.md)
- [M2 — Tunnel + CLI](docs/M2-TUNNEL.md)
- [M3 — Auth thật + ticket offline](docs/M3-AUTH.md)
- [M4 — Relay + doctor + fallback](docs/M4-RELAY.md)
- [M5 — Báo cáo kết thúc MVP](docs/M5-REPORT.md)
- [M6 — Java join Bedrock](docs/M6-BEDROCK.md)
- [RUNBOOK — triển khai/vận hành](docs/RUNBOOK.md)
- [MEASUREMENTS — số đo M0, M5 và M6](docs/MEASUREMENTS.md)

## Kiểm thử

```bash
./gradlew.bat test :it:test
```

70 test (protocol 24, coordinator 21, it 18, agent-core 6, transport 1). Kiểm thử Minecraft thật, chuỗi
Java→Bedrock và đo tải: xem `docs/MEASUREMENTS.md`, `docs/M6-BEDROCK.md`; kịch bản trong `_m5/`, `_m6/`.

## Đóng gói

```bash
./gradlew.bat packageAgent
# build/dist/mpmc-agent-0.1.0.zip
```

## Giới hạn

- Chưa xác minh trên hai máy khác mạng/NAT thật, relay TLS thật, client Microsoft auth + server
  `online-mode=true`; mọi số đo M5 là trên một máy.
- Tải bulk 10 stream còn phụ thuộc heap (`-Xmx512m` mặc định); đã ghi nhận một lần treo trước khi giới
  hạn heap.
- Bedrock: mới xác minh đích `Bedrock 1.26.51` + ViaProxy 3.4.14; chưa test client Microsoft auth,
  hai máy khác mạng, hay chiều ngược lại (Bedrock client → server Java).
- Không hỗ trợ Android/GUI/friends/host migration trong MVP.
