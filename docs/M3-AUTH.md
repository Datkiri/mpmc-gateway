# M3 — Auth thật + vé offline

## Phạm vi

Join/host xác thực với Coordinator thật (Ed25519 challenge → JWT), xin vé vào phòng, và host
**xác minh vé offline** bằng JWKS đã cache. Không còn `devMode` trong luồng production; dev ticket
chỉ tồn tại khi chạy CLI với `--insecure-dev-ticket`.

Luồng đầy đủ:

1. `POST /v1/auth/challenge` → ký `mpmc-auth-v1<id><nonce>` → `POST /v1/auth/token` (JWT 15 phút).
2. Host `POST /v1/rooms` → nhận `roomId`, `code`, `inviteToken`, `hostToken`.
3. Join `POST /v1/rooms/{code}/join`:
   - invite đúng (chế độ `invite`) → `APPROVED` + vé;
   - ngược lại → `PENDING`, host nhận `join.requested` qua WebSocket và approve/reject;
   - `joinStatus` cấp lại vé mới khi vé cũ sắp/đã hết hạn.
4. Mỗi stream gửi `HELLO` kèm vé; host verify chữ ký + `roomId` + `sub` + `exp` **hoàn toàn offline**
   bằng `JwksCache` (TTL 10 phút, lưu đĩa để khởi động lại vẫn verify được).

## Thay đổi trong M3

| Khu vực | Thay đổi |
| --- | --- |
| `JoinService` | `prepare()` map `JOIN_REJECTED → NOT_A_MEMBER`, `JOIN_EXPIRED → TICKET_EXPIRED`; `roomInfo` 409 `ROOM_CLOSED` được nhận diện là phòng đóng |
| `JoinService` | `currentTicket()` bọc `Mutex` để nhiều kết nối TCP không refresh vé song song |
| `JoinService` | giới hạn 64 kết nối TCP cục bộ; phát hiện phòng bị đóng sau khi mất kết nối (`GET /v1/rooms/{code}` trả 409) |
| `HostService` | `fail()` gửi `HELLO_ERR` rồi `finish()` để frame không bị mất khi đóng stream; đóng luôn kết nối iroh nếu bắt tay thất bại |
| `HostService` | log debug `hello_rejected reason=…`; `member.left` chặn endpoint bị kick 10 phút và đóng stream đang mở |
| `JwksCache` | đã có từ M2, dùng thật trong M3 (preload + refresh theo TTL) |
| `it` | thêm PostgreSQL nhúng (Zonky) + Netty coordinator để chạy auth end-to-end |

## Acceptance → test

`it/src/test/kotlin/org/mpmc/it/AuthIT.kt` chạy coordinator Netty thật trên PostgreSQL nhúng, host/join
là agent thật (không devMode), iroh ở chế độ `disabled` relay.

| Yêu cầu | Test |
| --- | --- |
| Không invite + host từ chối → `NOT_A_MEMBER` | `join request rejected without invite maps to NOT_A_MEMBER` |
| Invite sai vẫn `PENDING`; invite đúng cấp vé verify offline được | `wrong invite stays pending and correct invite issues offline verifiable ticket` |
| Vé hết hạn → host trả `TICKET_EXPIRED` → join refresh qua `joinStatus` → kết nối lại thành công | `expired ticket is rejected then refreshed through join status` |
| Stream thứ 5 trên cùng một vé (maxStreams=4) bị `TOO_MANY_STREAMS` | `fifth concurrent stream on one ticket is rejected` |
| Kick đóng tunnel hiện tại và chặn endpoint | `kick blocks endpoint and closes existing tunnel` |
| Đóng phòng → join nhận `ROOM_CLOSED` | `room close surfaces ROOM_CLOSED to joiner` |

Lưu ý về test hết hạn vé: coordinator và host dùng chung `MutableClock`, join dùng đồng hồ hệ thống.
Sau khi tua +300 s, join vẫn gửi vé cũ (theo đồng hồ của nó), host từ chối bằng `TICKET_EXPIRED`;
join gọi `joinStatus` và nhận vé mới. Test assert cả log `reason=EXPIRED` của host lẫn việc echo
hoạt động trở lại.

## Bảo mật

- Không log `Authorization`, `hostToken`, `inviteToken`, vé hay challenge.
- `hostToken` chỉ đi trong header `X-Mpmc-Host-Token`, không xuất hiện trong URL.
- Join không chọn đích TCP; host chỉ dial `host.target` đã cấu hình.
- Vé chứa `roomId`, `sub` (endpoint id), `jti`, `exp`, `maxStreams`; host kiểm tra cả bốn trường.
- `jti` bị đếm để giới hạn số stream/ vé; endpoint bị kick bị chặn 10 phút ngay cả khi còn vé hợp lệ.

## Khác biệt / giới hạn đã biết

- Dev mode (`--insecure-dev-ticket`) bỏ kiểm tra `jti`/`maxStreams` — chỉ dùng cho test M2.
- iroh 1.2.0 JVM chưa trả endpoint id của phía gửi (`Incoming.remoteId = "unknown"`), nên chống
  spam kết nối dựa vào rate limit phía Coordinator và giới hạn stream của host.
- Khi host chết hẳn, mỗi lần dial có thể chờ tối đa 20 s trước khi backoff (cải thiện ở M4).
- Chưa có TLS cho Coordinator trong test; production bắt buộc `https://` (trừ loopback).

## Chạy test

```bash
./gradlew.bat :it:test --tests 'org.mpmc.it.AuthIT'
```

Test tự khởi động PostgreSQL nhúng (Zonky), không cần Docker/WSL.
