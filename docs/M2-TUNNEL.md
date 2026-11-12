# M2 — Tunnel P2P

## Phạm vi

Host và join chạy như hai tiến trình riêng, bắt tay bằng frame `MPMP`, sau đó chuyển tiếp byte thô
qua một `BiStream` của iroh cho mỗi kết nối TCP. M2 dùng **dev ticket tự ký** (bật bằng
`--insecure-dev-ticket`), chưa cần Coordinator; auth thật thuộc M3.

## Frame protocol (`protocol/Frame.kt`)

| Trường | Giá trị |
| --- | --- |
| Magic | `MPMP` (4 byte) |
| Version | `1` (1 byte) |
| Type | `1 HELLO`, `2 HELLO_OK`, `3 HELLO_ERR`, `4 GOAWAY` |
| Length | u16 big-endian, payload tối đa 8192 byte |

Payload JSON:

- `HELLO`: `roomId`, `endpointId`, `ticket`, `streamNonce`, `clientVersion`
- `HELLO_OK`: `streamLimit`, `serverVersion`
- `HELLO_ERR`: `code` (xem `FrameErrorCode`), `message`, `retryable`
- `GOAWAY`: `code`, `message`

Luồng điều khiển: join mở stream đầu tiên, gửi `HELLO`, chờ `HELLO_OK`/`HELLO_ERR`.
Mỗi stream dữ liệu gửi `HELLO` riêng và nhận `HELLO_OK` trước khi splice byte thô.

## Transport facade (`transport-iroh`)

`Transport`/`Peer`/`PeerConnection`/`DuplexStream` tách agent khỏi `computer.iroh`.
ALPN mặc định `mpmc/1`. Ba chế độ relay: `N0`, `Disabled`, `Custom(urls)`.

Khác so với prompt (đã ghi nhận):

- `dial(remoteId, directAddresses, relayUrl)` — cần cho test offline và cho host có địa chỉ trực tiếp.
- `boundAddresses()` — in địa chỉ bind để test/doctor.
- `closedReason()` — phân biệt đóng chủ động với lỗi mạng khi reconnect.

`IrohTransport.dial` có timeout 20 s và map `TimeoutCancellationException` thành `TransportException`
để vòng reconnect không bị chết lặng.

## agent-core

| Thành phần | Vai trò |
| --- | --- |
| `HostService` | Accept connection, xác thực HELLO, mở TCP tới `host.target`, splice; heartbeat 20 s; xử lý event kick/room-close |
| `JoinService` | Bind proxy `127.0.0.1`, giữ một QUIC connection, mở stream cho từng TCP, refresh ticket, reconnect backoff 0.5→8 s (tối đa 5 lần) |
| `TunnelBridge` | Copy hai chiều 16 KiB; EOF → `finish()`, lỗi → `reset(1)`/đóng socket |
| `LocalProxy` | Chỉ bind `127.0.0.1`; cổng 0 sẽ quét 25566..25600 |
| `SessionStore` | Ghi `session.json` nguyên tử, có retry cho Windows file-lock |
| `KeyStore` | Ed25519, file 80 byte (PKCS#8 48 + public 32); seed 32 byte dùng cho iroh |
| `DevTicket` | Ticket tự ký cho M2, room `00000000-…-0001`, mã `DEV2345` |

Chế độ dev bỏ qua `jti`/`maxStreams` để test 5+ stream và 100 kết nối; M3 bật lại với ticket thật.

## CLI (`agent-cli`)

```
mpmc host  --target 127.0.0.1:25565 [--bind IP:PORT] [--relay-mode n0|disabled|custom]
           [--relay-url URL] [--insecure-dev-ticket] [--code CODE] [--approve-all]
mpmc join  --code CODE [--invite-token T] [--insecure-dev-ticket --dev-host-endpoint ID
           [--direct IP:PORT]] [--listen 127.0.0.1:PORT] [--relay-mode ...]
mpmc status [--json]
mpmc disconnect
mpmc config [--init|--path]
mpmc doctor
mpmc version
```

`host`/`join` chạy foreground và mở cổng điều khiển `127.0.0.1:45123` (đổi bằng `--control-port`)
để `disconnect` dừng phiên. Không in host token/invite token/ticket ra log.

## Test & acceptance

`./gradlew.bat :it:test`

| Acceptance | Test |
| --- | --- |
| Host + join 2 process, echo 1 MiB | `CliProcessIT.two cli processes tunnel one mebibyte` (spawn 2 JVM thật) |
| `status` DIRECT/RELAY đúng `paths()` | `TunnelIT.status reports direct path in disabled relay mode` (DIRECT) |
| Half-close | `TunnelIT.half close propagates fin and returns tail` |
| 5 TCP đồng thời / 1 QUIC connection | `TunnelIT.five concurrent tcp connections share one quic connection` |
| Ngắt QUIC → Reconnecting, listener vẫn bind | `TunnelIT.host loss moves join to reconnecting and recovers after restart` |
| 100 connection không leak FD | `TunnelIT.one hundred sequential connections do not leak sockets` (đếm `openTcp` về 0 + kết nối thứ 101) |
| Frame codec | `protocol: FrameCodecTest` (6 test) |

Đo nhanh trên máy dev: echo 1 MiB một chiều ~100–150 ms qua loopback, 100 kết nối ~2.5 s.
Đây là số loopback, **không** đại diện mạng thật.

## Ghi chú / giới hạn

- `Incoming.remoteId` của binding iroh 1.2.0 trả `"unknown"`; host dùng `endpointId` trong HELLO.
- Ticket dev không có giá trị bảo mật; không dùng ngoài test.
- Khi host tắt hẳn, một lần dial có thể chờ tới 20 s trước khi backoff — sẽ rút ngắn ở M4.
