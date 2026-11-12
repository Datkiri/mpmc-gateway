# M1 — Coordinator

Trạng thái: xong, build xanh, 39 test xanh (protocol 18, coordinator 21).

## Phạm vi

- `:protocol` — DTO (`Dto.kt`), room code Crockford Base32 8 ký tự, ticket Ed25519 (TTL 120 s, `maxStreams = 4`), crypto helpers (B64Url, SHA-256, constant-time compare, raw Ed25519 public key), `FrameErrorCode`.
- `:coordinator` — Ktor 3.0.3 / Netty, HikariCP + PostgreSQL, Flyway migration `V1__init.sql` đúng schema §7, auth challenge/token (JWT EdDSA), rooms + join/approve/reject + ticket, relay authorize, WS events, rate limit, `problem+json`, metrics Prometheus, JSON log.

## API

| Method | Path | Auth | Ghi chú |
|---|---|---|---|
| POST | `/v1/auth/challenge` | — | TTL 60 s, tối đa 100k challenge trong RAM |
| POST | `/v1/auth/token` | challenge | trả access token JWT EdDSA, TTL 900 s |
| POST | `/v1/rooms` | Bearer | trả `code`, `hostToken`, `inviteToken` (plaintext chỉ 1 lần) |
| GET | `/v1/rooms/{code}` | Bearer | thông tin phòng, không lộ token |
| POST | `/v1/rooms/{code}/heartbeat` | host | timeout 60 s |
| DELETE | `/v1/rooms/{code}` | host | đóng phòng |
| POST | `/v1/rooms/{code}/leave` | Bearer | |
| POST | `/v1/rooms/{code}/kick` | host | |
| POST | `/v1/rooms/{code}/join` | Bearer | invite đúng → `approved` + ticket; sai → `pending` |
| GET | `/v1/join-requests/{id}` | Bearer | polling trạng thái, `approved` kèm ticket (reissue nếu hết hạn) |
| POST | `/v1/join-requests/{id}/approve` | host | |
| POST | `/v1/join-requests/{id}/reject` | host | |
| POST/GET | `/v1/relay/authorize` | callback Bearer | `X-Iroh-Endpoint-Id`; 200 text `true`, hoặc 403 |
| GET | `/v1/.well-known/mpmc-keys` | — | JWKS, `Cache-Control: public, max-age=600` |
| GET | `/healthz` | — | `ok` |
| GET | `/metrics` | — | Prometheus text |
| WS | `/v1/events` | Bearer | `room.created`, `room.closed`, `member.joined/left`, `join.requested/approved/rejected` |

Header do ta quy ước (prompt không chỉ định): host token gửi qua `X-Mpmc-Host-Token`; mọi thao tác join/approve/leave dùng `Authorization: Bearer <accessToken>`.

## Biến môi trường

Bắt buộc: `DATABASE_URL` (`jdbc:postgresql:...`), `DATABASE_PASSWORD`, `SIGNING_KEY_PATH`, `RELAY_CALLBACK_TOKEN`.
Tuỳ chọn: `MPMC_HOST` (mặc định `127.0.0.1`), `MPMC_PORT` (8080), `DATABASE_USER` (`mpmc`), `MPMC_RELAY_URLS`.
Khóa ký: nếu file chưa có thì tạo PKCS#8 + `.pub` (SPKI); `kid` = base64url(SHA-256(SPKI)[0..8)).

Chạy:

```bash
./gradlew.bat :coordinator:run
```

## Ticket

`base64url(payloadJson) + "." + base64url(ed25519_sig)`; chữ ký trên đúng chuỗi ASCII của phần body. Payload gồm `v/iss/exp/iat/maxStreams/role/sub/room`. Ticket không lưu plaintext trong DB; khi cần reissue, dựng lại payload từ row + khóa ký (Ed25519 deterministic → cùng ticket). `maxStreams` = 4, TTL = 120 s.

## Rate limit (§14.5)

| Khóa | Giới hạn |
|---|---|
| join theo IP | 5/phút và 30/giờ |
| join theo endpoint | 20/giờ |
| create theo IP / endpoint | 5/giờ |
| mọi request theo endpoint | 120/phút |
| sai mã phòng theo IP | 20/giờ → chặn join 1 giờ |
| body | 16 KiB (`413 PAYLOAD_TOO_LARGE`) |

Trả `429` + `Retry-After`.

## Relay authorize (§14.4)

1. So sánh constant-time callback token.
2. `X-Iroh-Endpoint-Id` phải là 64 hex thường.
3. Agent phải tồn tại và không bị ban.
4. Có `relay_grants` còn hạn **hoặc** membership gần đây: `members.last_seen >= now - 120 s` trong phòng `active`.

## Metrics

`mpmc_rooms_active`, `mpmc_join_requests_total{state}`, `mpmc_auth_failures_total`, `mpmc_relay_auth_total{result}`, `mpmc_http_requests_total{route,status}`, `mpmc_ticket_issued_total`.

## Test

- `:protocol:test` — 18 test: room code (normalize/pretty/collision), ticket encode/verify (expiry, room/role/maxStreams, tampered sig), crypto (hex, constant-time, raw public key).
- `:coordinator:test` — 21 test: SigningKeys (4), RateLimiter (6), DB migration (1), `CoordinatorIT` (10) phủ đủ 8 mục acceptance: token chỉ lưu hash; invite đúng auto-approve + verify ticket bằng JWKS; invite sai → pending → approve → ticket; sai host token → 403; join lần 6/phút → 429; ticket hết hạn 120 s và `join status` reissue; relay authorize đúng/sai (POST + GET); không lộ secret trong log; health/metrics/lifecycle; WS cần Bearer và nhận event.

Chạy: `./gradlew.bat build` (embedded PostgreSQL 17.11.0 của Zonky khi không có Docker).

## Sai lệch / quyết định

- **Testcontainers → Zonky fallback**: máy không có Docker/WSL, nên `PgSupport` dùng Testcontainers khi có Docker, ngược lại khởi động PostgreSQL 17.11.0 nhúng. Chỉ ảnh hưởng test, production vẫn dùng `DATABASE_URL`.
- **Ktor 3.0.3 thay vì bản mới nhất**: Ktor ≥ 3.1 kéo Kotlin stdlib 2.3 + coroutines 1.11, xung đột pin Kotlin 2.2.21 / coroutines 1.9.0.
- **kotlinx-serialization 1.9.0**: 1.12.0 có metadata Kotlin 2.4.0, compiler 2.2.21 không đọc được.
- **JWT tự viết bằng `java.security`**: tránh thêm thư viện JWT; chỉ dùng EdDSA + claims tối thiểu.

## Chưa xác minh

- `[Chưa xác minh]` cơ chế callback thật của `n0computer/iroh-relay` với `[access.http]` (§15) — hợp đồng HTTP đã test, cấu hình TOML phải thử khi deploy.
- `[Chưa xác minh]` hành vi đóng WS khi token hết hạn giữa phiên (hiện chỉ kiểm tra lúc handshake).
- `[Chưa xác minh]` hiệu năng/độ trễ coordinator dưới tải thật; chưa có số đo.
