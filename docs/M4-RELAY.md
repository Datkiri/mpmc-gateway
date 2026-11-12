# M4 — Relay tự vận hành + fallback + doctor

## Phạm vi

M2 đã chạy tunnel qua iroh nhưng chỉ kiểm chứng được **đường direct** trên LAN. M4 bổ sung:

1. **iroh-relay tự dựng** (binary `iroh-relay` 1.2.0) đặt sau Caddy để kết thúc TLS, kèm file cấu hình
   và `docker-compose.yml` mẫu.
2. **Chế độ relay cấu hình được** phía agent (`transport.relay_mode` = `custom|n0|disabled`,
   `transport.relay_urls`).
3. **Fallback rõ ràng**: khi dial thất bại mà không có địa chỉ direct và relay không mở cổng TCP,
   join thoát bằng `RELAY_UNAVAILABLE` thay vì retry vô hạn.
4. **`mpmc doctor`** kiểm tra thêm DNS relay, TCP relay, route callback relay của coordinator và gửi
   thử UDP 7842.

Nguyên tắc M0–M3 không đổi: relay chỉ chuyển **byte stream đã mã hoá của iroh**; agent không đọc/sửa
byte Minecraft và không tự viết STUN/TURN/ICE.

## Cách relay được dùng

- Hai agent bind iroh endpoint với `transport.relay_mode = "custom"` và ít nhất một URL trong
  `transport.relay_urls`. Host và join phải trỏ tới **cùng** relay.
- iroh ưu tiên đường direct (LAN/hole punching). Relay chỉ được dùng khi direct không thiết lập được,
  nên `session.path` có thể là `direct`, `relay` hoặc `mixed` (đổi path giữa phiên).
- Relay chỉ thấy endpoint id và luồng QUIC đã mã hoá; nội dung Minecraft vẫn nằm trong TLS/QUIC đầu-cuối
  giữa hai agent.
- `relay_mode = "disabled"` dùng cho test LAN; `"n0"` dùng relay công cộng của Number 0 (không kiểm soát
  được, chỉ phù hợp thử nghiệm).

Khi join không có địa chỉ direct nào cho host và URL relay không mở TCP, `JoinService.retryAfter()` gọi
`relayUnavailable()` (TCP connect 1,5 s). Nếu thất bại, session ghi `ERROR` với
`RELAY_UNAVAILABLE` ngay, không chờ hết backoff 5 lần.

## Thay đổi trong M4

| Khu vực | Thay đổi |
| --- | --- |
| `JoinCommand` | `relayUrlHint = --relay-url ?: config.transport.relay_urls.firstOrNull()` |
| `JoinService` | `relayUnavailable()` + map lỗi dial thành `RELAY_UNAVAILABLE` khi không có direct address |
| `JoinService` | log `connect_retry delay_ms=… reason=…` (không chứa secret) |
| `DoctorCommand` | thêm `dns-coordinator`, `dns-relay`, `relay` (TCP), `relay-udp` (chỉ gửi thử), `relay-callback` |
| `AgentConfig` | validate `relay_urls` phải `https://` (chỉ cho phép `http://` với loopback) |
| `relay/` | `iroh-relay.toml`, `Caddyfile`, `docker-compose.yml` mẫu |

## Triển khai relay

### 1. Tải binary

Tải `iroh-relay` 1.2.0 cho Linux (cùng phiên bản với thư viện `computer.iroh:iroh:1.2.0`) và đặt tại
`relay/iroh-relay-linux/iroh-relay` (file phải có quyền thực thi). Compose mount binary này vào container
Debian, không dùng image tag chưa xác minh.

### 2. Cấu hình

`relay/iroh-relay.toml` — các khoá là **top-level** theo định dạng 1.2.0 (đã kiểm chứng: đặt dưới bảng
`[relay]` bị bỏ qua và relay quay về cổng 80):

```toml
http_bind_addr = "0.0.0.0:3340"
metrics_bind_addr = "0.0.0.0:9090"
```

`relay/Caddyfile` kết thúc TLS và proxy về relay:

```caddyfile
{$MPMC_RELAY_DOMAIN:relay.example.com} {
	encode zstd gzip
	reverse_proxy {$MPMC_RELAY_UPSTREAM:relay:3340}
	log {
		output stdout
		format console
	}
}
```

### 3. Chạy

```bash
cd relay
MPMC_RELAY_DOMAIN=relay.example.com docker compose up -d
```

Mở 80/tcp, 443/tcp, 443/udp trên firewall. Sau đó cấu hình agent:

```toml
[coordinator]
url = "https://coordinator.example.com"

[transport]
relay_mode = "custom"
relay_urls = ["https://relay.example.com"]
```

URL relay cũng phải được khai báo cho coordinator (`MPMC_RELAY_URLS`) nếu muốn `POST /v1/rooms` trả về
danh sách relay trong `CreateRoomResponse`; hiện join dùng URL từ config/CLI của chính nó.

### 4. Kiểm tra bằng doctor

```
PASS  dns-coordinator: coordinator.example.com → 203.0.113.10
PASS  relay-callback: route tồn tại (HTTP 401 khi thiếu token)
PASS  dns-relay: relay.example.com → 203.0.113.20
PASS  relay: https://relay.example.com tcp/443
WARN  relay-udp: relay.example.com:7842 đã gửi gói thử (UDP không bắt tay, không xác minh được)
```

`WARN` ở `relay-udp` là cố ý: gói UDP không có phản hồi nên doctor **không** khẳng định UDP hoạt động.
Khi chạy sau Caddy, relay chỉ nghe HTTP nội bộ; 443/udp chỉ cần nếu triển khai thêm đường QUIC.

## Acceptance → test

`it/src/test/kotlin/org/mpmc/it/RelayIT.kt` khởi động `iroh-relay.exe` cục bộ với TCP + metrics port
ngẫu nhiên và chạy agent thật:

| Yêu cầu | Test |
| --- | --- |
| Tunnel hoạt động chỉ qua relay, không có địa chỉ direct | `relay only tunnel works without direct addresses` (64 KiB echo, `session.path` ∈ `relay`, `mixed`) |
| Relay chết + không có direct → báo `RELAY_UNAVAILABLE` nhanh | `unreachable relay maps to RELAY_UNAVAILABLE quickly` |
| Log không lộ secret | `AuthIT.logs never contain room secrets` |
| Toàn bộ luồng auth + tunnel vẫn xanh | `AuthIT` 7 test |

Lệnh chạy:

```bash
./gradlew.bat :it:test
```

## Khác biệt / giới hạn đã biết

- **Chưa kiểm chứng trên VPS thật**: RelayIT chạy relay cục bộ (không TLS, không Caddy). Phần Caddy/TLS
  trong tài liệu là cấu hình mẫu, chưa đo trên miền thật.
- **HTTP relay + Caddy**: iroh-relay trong compose chỉ phục vụ HTTP nội bộ; TLS do Caddy kết thúc. Chưa
  bật đường QUIC/UDP riêng của relay.
- **Đường direct có thể thắng relay trên cùng LAN**, nên test relay-only phải cố tình bỏ danh sách địa
  chỉ direct (`passDirect = false`).
- **`updateRelay` chưa hỗ trợ**: đổi relay khi endpoint đang chạy chỉ log cảnh báo; phải khởi động lại
  agent.
