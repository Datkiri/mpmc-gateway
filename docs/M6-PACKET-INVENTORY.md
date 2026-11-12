# Kiểm kê packet Bedrock ↔ Java (mpmc-patched 3.4.14-mpmc.18)

> §1–§4 là bảng kiểm kê sinh trên jar `.14` (SHA `1d19a731…`, audit động 9788 dòng).
> `.15` (`71ea40ad…`) chỉ đổi một dòng clamp `count`; `.16` (`44bfbe82…`) gom clamp
> thành `clampStackCount()` cho 5 vị trí ghi số lượng; `.17` (`0a6f3c80…`) clamp theo
> `max_stack_size` riêng từng item; `.18` (`bdeab29f…`) sửa `USE_ITEM_ON` sequence,
> thay handler `INTERACT` bị ViaBackwards huỷ và null-guard `EntityTracker.getEntityByJid()`.
> **Không bản nào đổi ID, hướng, handler hay disposition nào** của bảng kiểm kê —
> `CLIENTBOUND_DISPOSITIONS` và 169/69 handler vẫn đúng, nên mọi dòng §1–§4 vẫn áp dụng.
> §5 (field-level 03:49), §6 (kết quả 03:49), §7 (field-level `ITEM_STACK_REQUEST` /
> `CREATIVE_CONTENT`), §8 (packet id trên dây + 9 `java-counterpart-unmapped`) và
> §9 (`.18` regression + GUI-only `status 6/60`) bổ sung, không ghi đè.

Tài liệu sinh tự động từ `_m6/packet-inventory.py`. Nguồn: enum `ClientboundBedrockPackets` /
`MinecraftPacketIds` (Bedrock 1.26.51, protocol 2193), các lệnh `registerClientbound*` /
`registerServerbound*` trong fork ViaBedrock, bảng `CLIENTBOUND_DISPOSITIONS`, và log audit động
`-Dmpmc.audit=true` của chính jar `.14` trên BDS thật.

## Quy ước mức xác minh

| Mức | Ý nghĩa |
| --- | --- |
| **Đã xác minh** | Có trong log runtime thật (audit động / `ITEM_STACK_RESPONSE` status 0 / BDS) và có handler rõ ràng |
| **Đã xác minh (tĩnh)** | Có handler/phân loại trong mã nguồn và audit tĩnh, nhưng chưa xuất hiện trong phiên đo |
| **Suy luận** | Có trong source / enum generated / bytecode nhưng **chưa có bằng chứng wire hoặc runtime trực tiếp** (vd giá trị packet id Bedrock tuyệt đối trên dây, byte `variant` của `StackRequestAction`) |
| **[Chưa xác minh]** / **[Chưa kiểm chứng]** | Biết có đối tác Java nhưng chưa kiểm chứng dịch ngữ nghĩa; hoặc chưa đọc field-level của packet đó |

Bốn mức trên áp dụng hồi tố cho mọi bảng trong tài liệu. Nhãn "Mới suy luận — không dùng"
từng ghi ở đầu bản 302 dòng là **mâu thuẫn** với §5 (vốn dùng `Suy luận`); đã sửa ở đây.

## Tổng quan đo được trên `.14`

- Audit tĩnh: **169/169** clientbound Bedrock (92 handler dịch, 21 cancel, 56 classified),
  **69/69** serverbound Java 26.3 (34 dịch sang Bedrock, 35 classify/cancel), `implicit FALLBACK = 0`,
  `duplicate handlers = none`.
- Audit động: **9788** dòng `[AUDIT]`, `0` dòng lỗi; `PRE 4827 = POST 2849 + CANCEL 1953 + DEFERRED 25`;
  `POST toHost=true: 1045`, `POST unmapped=true: 0`, `VERDICT: PASS - 100% accounted, zero errors`.
- Packet clientbound Bedrock quan sát thật: **58** tên; serverbound Java quan sát thật: **30** tên.
- Log: `_m6/gw-data/gateway-audit14/vp-audit14.log`, báo cáo `audit-report14.txt`.

## 1. Clientbound Bedrock → Java (169)

| ID (dec/hex) | Packet Bedrock | Xử lý | Đối tác Java / disposition | Mức | Bằng chứng |
| --- | --- | --- | --- | --- | --- |
| 2 / 0x02 | `PLAY_STATUS` | dịch (handler) | JoinPackets.java | Đã xác minh | audit động `.14` |
| 3 / 0x03 | `SERVER_TO_CLIENT_HANDSHAKE` | dịch (handler) | LoginPackets.java | Đã xác minh | audit động `.14` |
| 5 / 0x05 | `DISCONNECT` | dịch (transition) | DISCONNECT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 6 / 0x06 | `RESOURCE_PACKS_INFO` | dịch (transition) | RESOURCE_PACK_PUSH | Đã xác minh | audit động `.14` |
| 7 / 0x07 | `RESOURCE_PACK_STACK` | dịch (handler) | ResourcePackPackets.java | Đã xác minh | audit động `.14` |
| 9 / 0x09 | `TEXT` | dịch (transition) | SYSTEM_CHAT | Đã xác minh | audit động `.14` |
| 10 / 0x0a | `SET_TIME` | dịch (transition) | SET_TIME | Đã xác minh | audit động `.14` |
| 11 / 0x0b | `START_GAME` | dịch (handler) | JoinPackets.java | Đã xác minh | audit động `.14` |
| 12 / 0x0c | `ADD_PLAYER` | dịch (transition) | ADD_ENTITY | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 13 / 0x0d | `ADD_ENTITY` | dịch (transition) | ADD_ENTITY | Đã xác minh | audit động `.14` |
| 14 / 0x0e | `REMOVE_ENTITY` | dịch (transition) | REMOVE_ENTITIES | Đã xác minh | audit động `.14` |
| 15 / 0x0f | `ADD_ITEM_ENTITY` | dịch (transition) | ADD_ENTITY | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 16 / 0x10 | `SERVER_PLAYER_POST_MOVE_POSITION` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 17 / 0x11 | `TAKE_ITEM_ENTITY` | dịch (transition) | TAKE_ITEM_ENTITY | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 18 / 0x12 | `MOVE_ENTITY_ABSOLUTE` | dịch (transition) | ENTITY_POSITION_SYNC | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 19 / 0x13 | `MOVE_PLAYER` | dịch (transition) | ENTITY_POSITION_SYNC | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 21 / 0x15 | `UPDATE_BLOCK` | dịch (transition) | BLOCK_UPDATE | Đã xác minh | audit động `.14` |
| 22 / 0x16 | `ADD_PAINTING` | dịch (transition) | ADD_ENTITY | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 25 / 0x19 | `LEVEL_EVENT` | dịch (transition) | LEVEL_EVENT | Đã xác minh | audit động `.14` |
| 26 / 0x1a | `BLOCK_EVENT` | dịch (transition) | BLOCK_EVENT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 27 / 0x1b | `ENTITY_EVENT` | dịch (transition) | ENTITY_EVENT | Đã xác minh | audit động `.14` |
| 28 / 0x1c | `MOB_EFFECT` | dịch (transition) | UPDATE_MOB_EFFECT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 29 / 0x1d | `UPDATE_ATTRIBUTES` | dịch (transition) | UPDATE_ATTRIBUTES | Đã xác minh | audit động `.14` |
| 30 / 0x1e | `INVENTORY_TRANSACTION` | dịch (handler) | InteractionPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 31 / 0x1f | `MOB_EQUIPMENT` | dịch (transition) | SET_EQUIPMENT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 32 / 0x20 | `MOB_ARMOR_EQUIPMENT` | dịch (transition) | SET_EQUIPMENT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 36 / 0x24 | `PLAYER_ACTION` | dịch (handler) | ClientPlayerPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 38 / 0x26 | `HURT_ARMOR` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 39 / 0x27 | `SET_ENTITY_DATA` | dịch (transition) | SET_ENTITY_DATA | Đã xác minh | audit động `.14` |
| 40 / 0x28 | `SET_ENTITY_MOTION` | dịch (transition) | SET_ENTITY_MOTION | Đã xác minh | audit động `.14` |
| 41 / 0x29 | `SET_ENTITY_LINK` | dịch (transition) | SET_PASSENGERS | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 42 / 0x2a | `SET_HEALTH` | cancel | UnhandledPackets.java | Đã xác minh | audit động `.14` |
| 43 / 0x2b | `SET_SPAWN_POSITION` | dịch (transition) | SET_DEFAULT_SPAWN_POSITION | Đã xác minh | audit động `.14` |
| 44 / 0x2c | `ANIMATE` | dịch (transition) | ANIMATE | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 45 / 0x2d | `RESPAWN` | dịch (transition) | RESPAWN | Đã xác minh | audit động `.14` |
| 46 / 0x2e | `CONTAINER_OPEN` | dịch (transition) | OPEN_SCREEN | Đã xác minh | audit động `.14` |
| 47 / 0x2f | `CONTAINER_CLOSE` | dịch (transition) | CONTAINER_CLOSE | Đã xác minh | audit động `.14` |
| 48 / 0x30 | `PLAYER_HOTBAR` | dịch (transition) | SET_HELD_SLOT | Đã xác minh | audit động `.14` |
| 49 / 0x31 | `INVENTORY_CONTENT` | dịch (transition) | CONTAINER_SET_CONTENT | Đã xác minh | audit động `.14` |
| 50 / 0x32 | `INVENTORY_SLOT` | dịch (transition) | CONTAINER_SET_SLOT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 51 / 0x33 | `CONTAINER_SET_DATA` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit tĩnh `.14` |
| 52 / 0x34 | `CRAFTING_DATA` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit động `.14` |
| 54 / 0x36 | `GUI_DATA_PICK_ITEM` | dịch (transition) | SYSTEM_CHAT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 56 / 0x38 | `BLOCK_ENTITY_DATA` | dịch (transition) | BLOCK_ENTITY_DATA | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 58 / 0x3a | `LEVEL_CHUNK` | dịch (handler) | WorldPackets.java | Đã xác minh | audit động `.14` |
| 59 / 0x3b | `SET_COMMANDS_ENABLED` | dịch (handler) | ChatPackets.java | Đã xác minh | audit động `.14` |
| 60 / 0x3c | `SET_DIFFICULTY` | dịch (transition) | CHANGE_DIFFICULTY | Đã xác minh | audit động `.14` |
| 61 / 0x3d | `CHANGE_DIMENSION` | dịch (transition) | RESPAWN | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 62 / 0x3e | `SET_PLAYER_GAME_TYPE` | dịch (handler) | ClientPlayerPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 63 / 0x3f | `PLAYER_LIST` | dịch (handler) | HudPackets.java | Đã xác minh | audit động `.14` |
| 64 / 0x40 | `SIMPLE_EVENT` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 65 / 0x41 | `LEGACY_TELEMETRY_EVENT` | classified | bedrock-only:telemetry | Đã xác minh | audit động `.14` |
| 66 / 0x42 | `SPAWN_EXPERIENCE_ORB` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit tĩnh `.14` |
| 67 / 0x43 | `MAP_ITEM_DATA` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit tĩnh `.14` |
| 70 / 0x46 | `CHUNK_RADIUS_UPDATED` | dịch (transition) | SET_CHUNK_CACHE_RADIUS | Đã xác minh | audit động `.14` |
| 72 / 0x48 | `GAME_RULES_CHANGED` | dịch (handler) | PlayPackets.java | Đã xác minh | audit động `.14` |
| 73 / 0x49 | `CAMERA` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 74 / 0x4a | `BOSS_EVENT` | dịch (transition) | BOSS_EVENT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 75 / 0x4b | `SHOW_CREDITS` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 76 / 0x4c | `AVAILABLE_COMMANDS` | dịch (handler) | ChatPackets.java | Đã xác minh | audit động `.14` |
| 79 / 0x4f | `COMMAND_OUTPUT` | dịch (transition) | SYSTEM_CHAT | Đã xác minh | audit động `.14` |
| 80 / 0x50 | `UPDATE_TRADE` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit tĩnh `.14` |
| 81 / 0x51 | `UPDATE_EQUIP` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit tĩnh `.14` |
| 82 / 0x52 | `RESOURCE_PACK_DATA_INFO` | dịch (handler) | ResourcePackPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 83 / 0x53 | `RESOURCE_PACK_CHUNK_DATA` | dịch (handler) | ResourcePackPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 85 / 0x55 | `TRANSFER` | dịch (transition) | TRANSFER | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 86 / 0x56 | `PLAY_SOUND` | dịch (transition) | SOUND | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 87 / 0x57 | `STOP_SOUND` | dịch (transition) | STOP_SOUND | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 88 / 0x58 | `SET_TITLE` | dịch (handler) | HudPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 89 / 0x59 | `ADD_BEHAVIOR_TREE` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 91 / 0x5b | `SHOW_STORE_OFFER` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 93 / 0x5d | `PLAYER_SKIN` | dịch (handler) | OtherPlayerPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 95 / 0x5f | `AUTOMATION_CLIENT_CONNECT` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 96 / 0x60 | `SET_LAST_HURT_BY` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 99 / 0x63 | `PHOTO_TRANSFER` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 100 / 0x64 | `MODAL_FORM_REQUEST` | dịch (transition) | SHOW_DIALOG | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 103 / 0x67 | `SERVER_SETTINGS_RESPONSE` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 104 / 0x68 | `SHOW_PROFILE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 105 / 0x69 | `SET_DEFAULT_GAME_TYPE` | dịch (handler) | ClientPlayerPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 106 / 0x6a | `REMOVE_OBJECTIVE` | dịch (transition) | SET_OBJECTIVE | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 107 / 0x6b | `SET_DISPLAY_OBJECTIVE` | dịch (transition) | SET_DISPLAY_OBJECTIVE | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 108 / 0x6c | `SET_SCORE` | dịch (handler) | HudPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 109 / 0x6d | `LAB_TABLE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 110 / 0x6e | `UPDATE_BLOCK_SYNCED` | dịch (transition) | BLOCK_UPDATE | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 111 / 0x6f | `MOVE_ENTITY_DELTA` | dịch (transition) | ENTITY_POSITION_SYNC | Đã xác minh | audit động `.14` |
| 112 / 0x70 | `SET_SCOREBOARD_IDENTITY` | dịch (handler) | HudPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 114 / 0x72 | `UPDATE_SOFT_ENUM` | dịch (handler) | ChatPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 115 / 0x73 | `NETWORK_STACK_LATENCY` | dịch (transition) | PING | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 118 / 0x76 | `SPAWN_PARTICLE_EFFECT` | dịch (transition) | LEVEL_PARTICLES | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 119 / 0x77 | `AVAILABLE_ENTITY_IDENTIFIERS` | dịch (handler) | JoinPackets.java | Đã xác minh | audit động `.14` |
| 121 / 0x79 | `NETWORK_CHUNK_PUBLISHER_UPDATE` | dịch (transition) | SET_CHUNK_CACHE_RADIUS | Đã xác minh | audit động `.14` |
| 122 / 0x7a | `BIOME_DEFINITION_LIST` | dịch (handler) | JoinPackets.java | Đã xác minh | audit động `.14` |
| 123 / 0x7b | `LEVEL_SOUND_EVENT` | dịch (transition) | SOUND | Đã xác minh | audit động `.14` |
| 124 / 0x7c | `LEVEL_EVENT_GENERIC` | dịch (transition) | LEVEL_PARTICLES | Đã xác minh | audit động `.14` |
| 130 / 0x82 | `ON_SCREEN_TEXTURE_ANIMATION` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 133 / 0x85 | `STRUCTURE_TEMPLATE_DATA_RESPONSE` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 136 / 0x88 | `CLIENT_CACHE_MISS_RESPONSE` | dịch (handler) | PlayPackets.java | Đã xác minh | audit động `.14` |
| 137 / 0x89 | `EDUCATION_SETTINGS` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 138 / 0x8a | `EMOTE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 139 / 0x8b | `MULTIPLAYER_SETTINGS` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 142 / 0x8e | `COMPLETED_USING_ITEM` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 143 / 0x8f | `NETWORK_SETTINGS` | dịch (handler) | LoginPackets.java | Đã xác minh | audit động `.14` |
| 145 / 0x91 | `CREATIVE_CONTENT` | dịch (handler) | InventoryPackets.java | Đã xác minh | audit động `.14` |
| 146 / 0x92 | `PLAYER_ENCHANT_OPTIONS` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 148 / 0x94 | `ITEM_STACK_RESPONSE` | dịch (handler) | InventoryPackets.java | Đã xác minh | audit động `.14` |
| 149 / 0x95 | `PLAYER_ARMOR_DAMAGE` | dịch (transition) | SET_EQUIPMENT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 150 / 0x96 | `CODE_BUILDER` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 151 / 0x97 | `UPDATE_PLAYER_GAME_TYPE` | dịch (transition) | PLAYER_INFO_UPDATE | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 152 / 0x98 | `EMOTE_LIST` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 153 / 0x99 | `POSITION_TRACKING_DB_SERVER_BROADCAST` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 155 / 0x9b | `DEBUG_INFO` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 156 / 0x9c | `PACKET_VIOLATION_WARNING` | dịch (transition) | DISCONNECT | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 157 / 0x9d | `MOTION_PREDICTION_HINTS` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 158 / 0x9e | `ANIMATE_ENTITY` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 159 / 0x9f | `CAMERA_SHAKE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 160 / 0xa0 | `PLAYER_FOG` | cancel | UnhandledPackets.java | Đã xác minh | audit động `.14` |
| 161 / 0xa1 | `CORRECT_PLAYER_MOVE_PREDICTION` | dịch (transition) | PLAYER_POSITION | Đã xác minh | audit động `.14` |
| 162 / 0xa2 | `ITEM_REGISTRY` | dịch (handler) | JoinPackets.java | Đã xác minh | audit động `.14` |
| 164 / 0xa4 | `DEBUG_RENDERER` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 165 / 0xa5 | `SYNC_ENTITY_PROPERTY` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh | audit động `.14` |
| 166 / 0xa6 | `ADD_VOLUME_ENTITY` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 167 / 0xa7 | `REMOVE_VOLUME_ENTITY` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 168 / 0xa8 | `SIMULATION_TYPE` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 169 / 0xa9 | `NPC_DIALOGUE` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 170 / 0xaa | `EDU_URI_RESOURCE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 172 / 0xac | `UPDATE_SUB_CHUNK_BLOCKS` | dịch (handler) | WorldPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 174 / 0xae | `SUB_CHUNK` | dịch (handler) | WorldPackets.java | Đã xác minh | audit động `.14` |
| 176 / 0xb0 | `PLAYER_START_ITEM_COOLDOWN` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit tĩnh `.14` |
| 177 / 0xb1 | `SCRIPT_MESSAGE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 179 / 0xb3 | `TICKING_AREAS_LOAD_STATUS` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 180 / 0xb4 | `DIMENSION_DATA` | dịch (handler) | JoinPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 181 / 0xb5 | `AGENT_ACTION_EVENT` | classified | bedrock-only:education-agent | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 182 / 0xb6 | `CHANGE_MOB_PROPERTY` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 183 / 0xb7 | `LESSON_PROGRESS` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 186 / 0xba | `TOAST_REQUEST` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 187 / 0xbb | `UPDATE_ABILITIES` | dịch (transition) | PLAYER_ABILITIES | Đã xác minh | audit động `.14` |
| 188 / 0xbc | `UPDATE_ADVENTURE_SETTINGS` | dịch (handler) | ClientPlayerPackets.java | Đã xác minh | audit động `.14` |
| 189 / 0xbd | `DEATH_INFO` | dịch (handler) | HudPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 190 / 0xbe | `EDITOR_NETWORK` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 191 / 0xbf | `FEATURE_REGISTRY` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 192 / 0xc0 | `SERVER_STATS` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 195 / 0xc3 | `GAME_TEST_RESULTS` | classified | bedrock-only:debug-editor-script | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 196 / 0xc4 | `UPDATE_CLIENT_INPUT_LOCKS` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 198 / 0xc6 | `CAMERA_PRESETS` | cancel | UnhandledPackets.java | Đã xác minh | audit động `.14` |
| 199 / 0xc7 | `UNLOCKED_RECIPES` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit động `.14` |
| 300 / 0x12c | `CAMERA_INSTRUCTION` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 302 / 0x12e | `TRIM_DATA` | classified | java-counterpart-unmapped (có đối tác Java, chưa dịch ngữ nghĩa) | [Chưa xác minh] | audit động `.14` |
| 303 / 0x12f | `OPEN_SIGN` | dịch (transition) | OPEN_SIGN_EDITOR | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 304 / 0x130 | `AGENT_ANIMATION` | classified | bedrock-only:education-agent | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 307 / 0x133 | `SET_PLAYER_INVENTORY_OPTIONS` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 308 / 0x134 | `SET_HUD` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 309 / 0x135 | `AWARD_ACHIEVEMENT` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 310 / 0x136 | `CLOSE_FORM` | dịch (transition) | CLEAR_DIALOG | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 313 / 0x139 | `JIGSAW_STRUCTURE_DATA` | classified | bedrock-only:debug-editor-script | Đã xác minh | audit động `.14` |
| 314 / 0x13a | `CURRENT_STRUCTURE_FEATURE` | cancel | UnhandledPackets.java | Đã xác minh | audit động `.14` |
| 316 / 0x13c | `CAMERA_AIM_ASSIST` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 317 / 0x13d | `CONTAINER_REGISTRY_CLEANUP` | dịch (handler) | InventoryPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 318 / 0x13e | `MOVEMENT_EFFECT` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 320 / 0x140 | `CAMERA_AIM_ASSIST_PRESETS` | cancel | UnhandledPackets.java | Đã xác minh | audit động `.14` |
| 324 / 0x144 | `PLAYER_VIDEO_CAPTURE` | cancel | UnhandledPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 325 / 0x145 | `PLAYER_UPDATE_ENTITY_OVERRIDES` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 326 / 0x146 | `PLAYER_LOCATION` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 338 / 0x152 | `CAMERA_SPLINE` | classified | bedrock-only:client-ui | Đã xác minh | audit động `.14` |
| 344 / 0x158 | `SYNC_WORLD_CLOCKS` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh | audit động `.14` |
| 337 / 0x151 | `VOXEL_SHAPES` | classified | bedrock-only:debug-editor-script | Đã xác minh | audit động `.14` |
| 351 / 0x15f | `SET_PLAYER_FURNACE_OPTIONS` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 352 / 0x160 | `RECORD_STARTED` | classified | no-java-packet (Bedrock-only, không có đối tác Java) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 355 / 0x163 | `STONECUTTER_SET_RECIPE` | classified | bedrock-only:client-ui | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| 341 / 0x155 | `LOCATOR_BAR` | dịch (handler) | LocatorBarPackets.java | Đã xác minh (tĩnh) | audit tĩnh `.14` |

## 2. Serverbound Java 26.3 → Bedrock (69)

| Packet Java | Đích / xử lý | Mức | Bằng chứng |
| --- | --- | --- | --- |
| `ACCEPT_TELEPORTATION` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `ATTACK` | dịch: Bedrock `INVENTORY_TRANSACTION` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `BLOCK_ENTITY_TAG_QUERY` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `BUNDLE_ITEM_SELECTED` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CHANGE_DIFFICULTY` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CHANGE_GAME_MODE` | dịch: Bedrock `SET_PLAYER_GAME_TYPE` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CHAT_ACK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh | audit động `.14` |
| `CHAT_COMMAND` | dịch: Bedrock `COMMAND_REQUEST` | Đã xác minh | audit động `.14` |
| `CHAT_COMMAND_SIGNED` | dịch: Bedrock `COMMAND_REQUEST` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CHAT` | dịch: Bedrock `TEXT` | Đã xác minh | audit động `.14` |
| `CHAT_SESSION_UPDATE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CHUNK_BATCH_RECEIVED` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CLIENT_COMMAND` | dịch: Bedrock `RESPAWN` | Đã xác minh | audit động `.14` |
| `CLIENT_TICK_END` | dịch: Bedrock `PLAYER_AUTH_INPUT` | Đã xác minh | audit động `.14` |
| `CLIENT_INFORMATION` | dịch: Bedrock `REQUEST_CHUNK_RADIUS` | Đã xác minh | audit động `.14` |
| `COMMAND_SUGGESTION` | handler riêng: handler `ChatPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `CONFIGURATION_ACKNOWLEDGED` | handler riêng: handler `ConfigurationPackets.java` (cancel/classify) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CONTAINER_BUTTON_CLICK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CONTAINER_CLICK` | handler riêng: handler `InventoryPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `CONTAINER_CLOSE` | dịch: Bedrock `CONTAINER_CLOSE` | Đã xác minh | audit động `.14` |
| `CONTAINER_SLOT_STATE_CHANGED` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `COOKIE_RESPONSE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CUSTOM_PAYLOAD` | handler riêng: handler `PlayPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `DEBUG_SUBSCRIPTION_REQUEST` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `EDIT_BOOK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `ENTITY_TAG_QUERY` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `INTERACT` | dịch: Bedrock `INVENTORY_TRANSACTION` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `JIGSAW_GENERATE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `KEEP_ALIVE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh | audit động `.14` |
| `LOCK_DIFFICULTY` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `MOVE_PLAYER_POS` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `MOVE_PLAYER_POS_ROT` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `MOVE_PLAYER_ROT` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `MOVE_PLAYER_STATUS_ONLY` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `MOVE_VEHICLE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PADDLE_BOAT` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PICK_ITEM_FROM_BLOCK` | dịch: Bedrock `BLOCK_PICK_REQUEST` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PICK_ITEM_FROM_ENTITY` | dịch: Bedrock `ENTITY_PICK_REQUEST` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PING_REQUEST` | handler riêng: handler `PlayPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `PLACE_RECIPE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PLAYER_ABILITIES` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PLAYER_ACTION` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `PLAYER_COMMAND` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PLAYER_INPUT` | handler riêng: handler `ClientPlayerPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `PLAYER_LOADED` | classified/cancel (Bedrock không có đối tác) | Đã xác minh | audit động `.14` |
| `PONG` | dịch: Bedrock `NETWORK_STACK_LATENCY` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `PUNCH` | dịch: Bedrock `ANIMATE` | Đã xác minh | audit động `.14` |
| `RECIPE_BOOK_CHANGE_SETTINGS` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `RECIPE_BOOK_SEEN_RECIPE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `RENAME_ITEM` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `RESOURCE_PACK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SEEN_ADVANCEMENTS` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SELECT_TRADE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_BEACON` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_CARRIED_ITEM` | dịch: Bedrock `MOB_EQUIPMENT` | Đã xác minh | audit động `.14` |
| `SET_COMMAND_BLOCK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_COMMAND_MINECART` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_CREATIVE_MODE_SLOT` | handler riêng: handler `InventoryPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `SET_GAME_RULE` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_JIGSAW_BLOCK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_STRUCTURE_BLOCK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SET_TEST_BLOCK` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SIGN_UPDATE` | dịch: Bedrock `BLOCK_ENTITY_DATA` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `SPECTATOR_ACTION` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `TELEPORT_TO_ENTITY` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `TEST_INSTANCE_BLOCK_ACTION` | classified/cancel (Bedrock không có đối tác) | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `USE_ITEM_ON` | handler riêng: handler `InteractionPackets.java` (cancel/classify) | Đã xác minh | audit động `.14` |
| `USE_ITEM` | dịch: Bedrock `INVENTORY_TRANSACTION` | Đã xác minh (tĩnh) | audit tĩnh `.14` |
| `CUSTOM_CLICK_ACTION` | dịch: Bedrock `MODAL_FORM_RESPONSE` | Đã xác minh (tĩnh) | audit tĩnh `.14` |

## 3. Rủi ro, tái hiện, phòng ngừa và test xác nhận

| Rủi ro | Nguyên nhân có thể | Cách tái hiện | Phòng ngừa | Test xác nhận |
| --- | --- | --- | --- | --- |
| Sai ID packet theo state | Cùng ID 0x02 là LOGIN success nhưng CONFIGURATION là disconnect; phát lại LOGIN_FINISHED sau khi đã sang CONFIGURATION | Broad probe 1.21.4 lặp login (xem `kicked:{"type":117}`) | `.12`: bỏ synthetic PLAY_STATUS, set CONFIGURATION trực tiếp | Bytecode `javap -c` + 36 phiên race, 0 `Chunk size` |
| Sai schema USE_ITEM_ON | 1.21.1 thiếu `worldBorderHit` so với 1.21.4/1.21.6; buffer/typed-value lẫn nhau | GUI 1.21.1 click chuột phải | `InteractionPackets`: `isReadable(VAR_INT,0)` + đọc điều kiện | `use-item-on-probe.js` 1.21.1, `errors=[]` |
| ACCEPT_TELEPORTATION replay | ViaBackwards `Protocol26_3To26_2` passthrough VAR_INT trên buffer đã tiêu thụ (`ridx=1,widx=1`) | Internal replay `transformOutgoing` | `.10/.12`: defensive handler public API + reflection storage | Regression 1.21.1/1.21.4/1.21.6, `exit=0` |
| Creative slot không có ack | Java 1.21.1 đợi `updateSlot:36`; Bedrock ITEM_STACK_RESPONSE chỉ update tracker nội bộ | `creative-slot-probe.js` 1.21.1 | `.11`: echo `CONTAINER_SET_CONTENT` đầy đủ | Probe nhận `window_items`, `errors=[]` |
| ITEM_STACK_REQUEST status 49 | GUI gửi 2 `SET_CREATIVE_MODE_SLOT`/click; request 2 dùng source stack chưa có `netId` | `gui-repro.js` cặp 2 packet cùng slot | `.14`: hàng đợi per-slot, flush sau ITEM_STACK_RESPONSE | 15/15 accepted, `oldNetId=271…287` |
| ITEM_STACK_REQUEST status 6 | Hai craft-action chain chồng cùng tick / trạng thái BDS tích lũy | GUI 22:17 (20/20); CLI chưa tái hiện | `.14`: serialize request per-slot; giữ chuẩn CraftCreative→CraftResults→Destroy→Take | `.14`: 0 status 6; nguyên nhân gốc vẫn [Chưa xác minh] |
| Item runtime id âm / custom item | Bedrock 1.26 dùng id âm (`-960`, `-213`, `-596`) và blockRuntimeId | Sweep id âm + 10 data component | Dùng descriptor từ `newItem`, giữ `ItemExtraDataWithoutBlockingTick` 10 byte | Sweep 21/21 accepted |
| Component/NBT lệch schema | `enchantments` / `attribute_modifiers` sai shape gây `SizeOf error for undefined` | Encode thử `createPacketBuffer` | Probe chỉ dùng 10 component hợp lệ; gateway luôn ghi `has_nbt=false` | Encode offline + gửi thật, 0 lỗi serialize |
| Thiếu / trùng / sai thứ tự packet | Deferred clientbound tới trước PLAY; replay skip BedrockProtocol | Race 36 phiên đồng thời | `wrapper.send(BedrockProtocol.class, false)`; audit PRE/POST/CANCEL/DEFERRED | `PRE = POST + CANCEL + DEFERRED` |
| Decode ok nhưng BDS từ chối ngữ nghĩa | status 6/11/49/51 không phải lỗi parse | Sweep action mode (`.13-exp`) | Log status + `restoreCreativeSlot` + queue per-slot | `.14` 0 rejection |
| Hàng đợi kẹt khi BDS không trả lời | Guard in-flight không bao giờ được giải phóng | Giả lập mất response | Stale timeout 5 s + xoá guard ở mọi early-return + drain loop | Edge stress `timed out=0`, 21 request drain hết |
| Giá trị biên: item rỗng / id lạ / burst | Drop im lặng hoặc gửi request vô nghĩa | `gui-edge-cases.json` (burst 5, id 0, id 9999) | Early-return có xoá guard; drop có chủ đích | 3 drop đúng, `oldNetId` tăng 289→315, 0 lỗi |
| Version lệch (1.21.1 / 1.21.4 / 1.21.6) | Field order / component set / state khác nhau | Chạy 3 version cùng kịch bản | Handler đọc theo typed-value, không hard-code version | Regression 7/7 `exit=0` |
| Rò rỉ GPL vào gói phát hành | Nhầm lẫn jar patched vào zip | Kiểm tra entry zip | Gateway là tiến trình riêng, `packageAgent` chỉ đóng installDist+docs | Zip: 0 `viaproxy\|viabedrock`, 0 jar, 0 class |

## 4. Ranh giới trung thực

- 77/169 packet clientbound Bedrock **không có đối tác Java**: được đăng ký, tiêu thụ, phân loại và audit,
  không dịch thành packet Java. Đây là giới hạn giao thức, không phải lỗi dịch.
- 9 packet `java-counterpart-unmapped` vẫn **[Chưa xác minh]** về dịch ngữ nghĩa đầy đủ.
- `status 6` gốc (GUI `JavaReal12`, 22:17) **chưa tái hiện được** bằng CLI trên cùng jar/BDS ⇒ nguyên nhân
  vẫn **[Chưa xác minh]**; `.14` đã loại bỏ cơ chế chồng craft-action chain nhưng không khẳng định triệt tiêu.
- LAN NetherNet/byulnuri, Java 1.21.7+, BDS 2225, render đồ hoạ đầy đủ: **[Chưa xác minh]**.
- Không đưa số liệu hiệu năng chưa đo. Mọi con số ở đây đều đọc được từ log/mã nguồn.

---

## 5. Kiểm kê field-level (append lượt automation 03:49, 2026-11-12)

Phần này bổ sung cho bảng §1–§2: **phiên bản/state áp dụng, từng field, kiểu,
endian/varint, ID counterpart Java và mức xác minh**. Nội dung gốc là §5 của bản
863 dòng từng nằm trong zip `42d41767…` (bị một phiên song song ghi đè mất khỏi
file local lúc 03:09); được khôi phục có đối chiếu lại với source `.14`/`.15`
(1259 dòng) và `javap -c -p` trên jar `.15` (`_m6/gw-data/javap-inventory-mpmc15.txt`).
**Không ghi đè** bảng packet-level §1–§4.

### 5.1 Phiên bản / state / ID trên dây (áp dụng cho mọi dòng)

| Chiều | Giao thức | Phiên bản | ID trên dây | State | Bằng chứng |
| --- | --- | --- | --- | --- | --- |
| Bedrock clientbound | Bedrock 1.26.51.1 (protocol 2193), RakNet UDP `127.0.0.1:19132` | BDS `bedrock_server.exe` 1.26.51.1 PID 18420 | `UNSIGNED_VAR_INT` | LOGIN / PLAY | `bds51-run.log`, `CREATIVE_CONTENT parsed: groups=124` |
| Bedrock serverbound | cùng | cùng | `UNSIGNED_VAR_INT` | PLAY | `ITEM_STACK_RESPONSE` status 0 thật |
| Java clientbound | ViaVersion 26.3 (protocol 777) | client 1.21.1 / 1.21.4 / 1.21.6 đã chạy | `VAR_INT` ViaVersion (**không** zigzag) | LOGIN / CONFIGURATION / PLAY | `javap -c -p` jar `.13`/`.15` |
| Java serverbound | cùng | cùng | `VAR_INT` ViaVersion | LOGIN / CONFIGURATION / PLAY | `javap -c -p` jar `.13`/`.15` |

Packet chỉ tồn tại ở state cụ thể (vd `CONFIGURATION_ACKNOWLEDGED`) đã ghi ở §2.
**Phân biệt**: `VAR_INT` Bedrock là zigzag32; `VAR_INT` Java là varint ViaVersion không zigzag.

### 5.2 Quy ước kiểu (đọc từ `BedrockTypes.java` / `BedrockItemType.java`)

| Ký hiệu | Ý nghĩa |
| --- | --- |
| `VAR_INT` | varint32 **zigzag** (số âm hợp lệ: request id, item id, netId) |
| `UNSIGNED_VAR_INT` | varuint32 (độ dài, số lượng, id packet) |
| `INT_LE` / `SHORT_LE` / `LONG_LE` | little-endian 4 / 2 / 8 byte |
| `STRING` | `UNSIGNED_VAR_INT` độ dài + UTF-8 |
| `BYTE` / `UNSIGNED_BYTE` | 1 byte có dấu / không dấu |
| `Types.VAR_INT` (Java) | varint32 ViaVersion |
| `Types.SHORT` (Java) | 2 byte **big-endian** |
| `Types.REMAINING_BYTES` | toàn bộ buffer còn lại |

ID packet Bedrock trên dây lấy từ `MinecraftPacketIds.java` (generated) — mức
**Suy luận** cho giá trị wire tuyệt đối vì chưa dump raw frame; encoding được kiểm
chứng gián tiếp qua `ITEM_STACK_RESPONSE` status 0.

### 5.3 Clientbound Bedrock → Java: field theo thứ tự (11 packet inventory)

| Packet (ID) | Field đọc | Kiểu / endian / varint | Ghi sang Java | Mức |
| --- | --- | --- | --- | --- |
| `CONTAINER_OPEN` (46) | 1 container id · 2 type · 3 position · 4 entity unique id (đọc rồi bỏ) | `BYTE` · `BYTE` · `BLOCK_POSITION` (3× `VAR_INT` zigzag) · `VAR_LONG` | `VAR_INT` container id · `VAR_INT` type (qua `getBedrockToJavaContainers`) · `TAG` title | Đã xác minh |
| `CONTAINER_CLOSE` (47) | 1 container id · 2 type · 3 serverInitiated | `BYTE` → `VAR_INT` · `BYTE` · `BOOLEAN` | — | Đã xác minh |
| `PLAYER_HOTBAR` (48) | 1 selected slot · 2 container id · 3 shouldSelectSlot | `UNSIGNED_VAR_INT` · `BYTE` · `BOOLEAN` | `VAR_INT` slot (chỉ khi `0≤slot<9`, đúng container, cờ bật) | Đã xác minh |
| `INVENTORY_CONTENT` (49) | 1 container id · 2 items[] · 3 container name · 4 storage item | `UNSIGNED_VAR_INT` · item array (§5.6) · `FULL_CONTAINER_NAME` · item | `writeJavaContainerSetContent` | Đã xác minh |
| `INVENTORY_SLOT` (50) | 1 container id · 2 slot · 3 container name · 4 storage item · 5 item | `UNSIGNED_VAR_INT` · `UNSIGNED_VAR_INT` · `OPTIONAL_FULL_CONTAINER_NAME` · optional item · item | `VAR_INT` id · `VAR_INT` revision=0 · `SHORT` slot (BE) · `V26_3.item`; HUD slot 0 → `SET_CURSOR_ITEM` | Đã xác minh |
| `CONTAINER_SET_DATA` (51) | **không có handler** trong `InventoryPackets.java` | — | `java-counterpart-unmapped` trong `CLIENTBOUND_DISPOSITIONS` | [Chưa xác minh] field |
| `MODAL_FORM_REQUEST` (100) | form payload (ngoài phạm vi inventory) | — | `SHOW_DIALOG` (143) | Suy luận |
| `CREATIVE_CONTENT` (145) | `REMAINING_BYTES`: `UNSIGNED_VAR_INT` groupCount → (`BYTE` category · `STRING` name · item icon)×N → `UNSIGNED_VAR_INT` itemCount → (`UNSIGNED_VAR_INT` entryId · item · `UNSIGNED_VAR_INT` groupIndex)×M | item = §5.6 | `cancel()`; lưu `entryId → BedrockItem` | Đã xác minh (source + log `groups=124`) |
| `ITEM_STACK_RESPONSE` (148) | `UNSIGNED_VAR_INT` responseCount; mỗi response: `UNSIGNED_BYTE` status · `VAR_INT` requestId · `BOOLEAN` hasContainers · [`UNSIGNED_VAR_INT` containerCount · (`FULL_CONTAINER_NAME` + `UNSIGNED_VAR_INT` slotCount · mỗi slot: `UNSIGNED_BYTE` slot · `UNSIGNED_BYTE` hotbarSlot · `UNSIGNED_BYTE` count · `BOOLEAN` hasNetId + optional `VAR_INT` stackNetId · `STRING` customName · `STRING` filteredCustomName · `VAR_INT` durabilityCorrection)] | như cột giữa | `cancel()`; status 0 → `applyItemStackResponseSlot` (gán netId thật); khác 0 → `restoreCreativeSlot` | Đã xác minh (source + BDS status 0) |
| `PLAYER_ARMOR_DAMAGE` (149) | 1 size · rồi size×(armor slot, damage) | `UNSIGNED_VAR_INT` · (`VAR_INT` · `SHORT_LE`) | `VAR_INT` entityId + mỗi entry `BYTE` slot (bit `0x80` nếu còn tiếp) + `V26_3.item` | Đã xác minh |
| `CLOSE_FORM` (310) | không đọc field | — | `CLEAR_DIALOG` (142); đóng form | Đã xác minh |
| `CONTAINER_REGISTRY_CLEANUP` (317) | `FULL_CONTAINER_NAME_ARRAY` (độ dài `UNSIGNED_VAR_INT`) | như cột giữa | `cancel()`; xoá dynamic container | Đã xác minh |

### 5.4 Serverbound Java 26.3 → Bedrock: field theo thứ tự (7 packet inventory)

| Packet Java (ID) | Field đọc | Kiểu | Đích Bedrock / hành động | Mức |
| --- | --- | --- | --- | --- |
| `CONTAINER_CLICK` (18) | containerId · revision · slot · button · action | `VAR_INT` · `VAR_INT` · `SHORT` (BE) · `BYTE` · `VAR_INT`→`ContainerInput` | `cancel()` + `container.handleClick`; nếu chưa có container → tự sinh `INTERACT(33)` OpenInventory | Đã xác minh |
| `CONTAINER_CLOSE` (19) | containerId | `VAR_INT` → `BYTE` | `CONTAINER_CLOSE` (47) + `BYTE` type=NONE + `BOOLEAN` false | Đã xác minh |
| `PICK_ITEM_FROM_BLOCK` (36) | position · includeData | `BLOCK_POSITION1_14` → `BLOCK_POSITION` · `BOOLEAN` | `BLOCK_PICK_REQUEST` (34) + `UNSIGNED_BYTE 9` | Đã xác minh |
| `PICK_ITEM_FROM_ENTITY` (37) | entityId · includeData | `VAR_INT` · `BOOLEAN` | `ENTITY_PICK_REQUEST` (35) | Đã xác minh |
| `SET_CARRIED_ITEM` (54) | slot | `SHORT` (BE) | `MOB_EQUIPMENT` (31) | Đã xác minh |
| `SET_CREATIVE_MODE_SLOT` (57) | slot · item | `SHORT` (BE) · `V26_3.lengthPrefixedItem` (có data components) | `cancel()` → `handleCreativeModeSlot` → tự sinh `ITEM_STACK_REQUEST` (147) | Đã xác minh (source + BDS) |
| `CUSTOM_CLICK_ACTION` (68) | id · payload | `STRING` · `CUSTOM_CLICK_ACTION_TAG` (NBT) | `MODAL_FORM_RESPONSE` (101): `UNSIGNED_VAR_INT` formId · `BOOLEAN` hasResponse · `STRING` response · `BOOLEAN` hasCancelReason · `BYTE` reason | Đã xác minh |

**Packet Bedrock serverbound không có đối tác Java 1-1:**

| ID | Tên | Ghi chú | Mức |
| --- | --- | --- | --- |
| 30 / 0x1E | `INVENTORY_TRANSACTION` | handler ở `InteractionPackets.java`, không thuộc `InventoryPackets.java` | Đã xác minh (phân loại) |
| 33 / 0x21 | `INTERACT` | tự sinh: `UNSIGNED_BYTE` action=`OpenInventory` · `UNSIGNED_VAR_LONG` runtimeId · `OPTIONAL_POSITION_3F` null | Đã xác minh (source) |
| 147 / 0x93 | `ITEM_STACK_REQUEST` | tự sinh bởi `handleCreativeModeSlot`, **KHÔNG** phải dịch 1-1 | Đã xác minh (source + BDS) |

### 5.5 ID counterpart Java 26.3 đã đối chiếu chéo (16 packet)

| Packet Java 26.3 | ID dec/hex | Chiều |
| --- | --- | --- |
| `OPEN_SCREEN` | 60 / 0x3C | clientbound |
| `CONTAINER_CLOSE` | 17 / 0x11 | clientbound |
| `CONTAINER_SET_CONTENT` | 18 / 0x12 | clientbound |
| `CONTAINER_SET_SLOT` | 20 / 0x14 | clientbound |
| `SET_HELD_SLOT` | 107 / 0x6B | clientbound |
| `SET_EQUIPMENT` | 104 / 0x68 | clientbound |
| `SET_CURSOR_ITEM` | 98 / 0x62 | clientbound |
| `SHOW_DIALOG` | 143 / 0x8F | clientbound |
| `CLEAR_DIALOG` | 142 / 0x8E | clientbound |
| `CONTAINER_CLICK` | 18 / 0x12 | serverbound |
| `CONTAINER_CLOSE` | 19 / 0x13 | serverbound |
| `PICK_ITEM_FROM_BLOCK` | 36 / 0x24 | serverbound |
| `PICK_ITEM_FROM_ENTITY` | 37 / 0x25 | serverbound |
| `SET_CARRIED_ITEM` | 54 / 0x36 | serverbound |
| `SET_CREATIVE_MODE_SLOT` | 57 / 0x39 | serverbound |
| `CUSTOM_CLICK_ACTION` | 68 / 0x44 | serverbound |

Bảng đầy đủ **144 clientbound + 69 serverbound = 213 ID** (ordinal enum = ID
varint trên dây) parse bằng `javap -c -p` lưu tại
`_m6/gw-data/gateway-test2/id-tables-aut12.md`; jar `.15` dùng cùng ViaVersion
26.3 nên bảng không đổi. Mức: **Đã xác minh (javap)**.

### 5.6 Cấu trúc con (field / kiểu / endian / varint)

**`BedrockItem` (`BedrockItemType.read`)** — Bedrock ≥1.26.40 **không còn
short-circuit cho air**: `VAR_INT` identifier (zigzag32, **có thể âm**, đã đo
`-960/-213/-596`) · `UNSIGNED_SHORT_LE` amount · `UNSIGNED_VAR_INT` data ·
`VAR_INT` blockRuntimeId · `UNSIGNED_VAR_INT` userDataLength · userData
(`SHORT_LE` marker 0/-1 · `BYTE` version=1 · `TAG_LE` NBT · `UTF8_STRING_ARRAY`
canPlace/canBreak · `LONG_LE` blockingTicks chỉ shield). Khi ghi: item empty →
chỉ `VAR_INT 0`; ngược lại thêm `BOOLEAN` hasNetId + optional `VAR_INT` netId.
Mức **Đã xác minh (source)**; bảng ánh xạ identifier đầy đủ **[Chưa xác minh]**.

**`ITEM_STACK_REQUEST` (147) do gateway tự sinh**:

| # | Field | Kiểu | Giá trị production |
| --- | --- | --- | --- |
| 1 | requests | `UNSIGNED_VAR_INT` | 1 |
| 2 | request id | `VAR_INT` (zigzag32) | `-1, -3, -5, …` |
| 3 | actions | `UNSIGNED_VAR_INT` | 3 (slot mới) / 4 (có Destroy) / 1 (chỉ Destroy, xoá item) |
| 4 | action chain | §5.6.1 | `CraftCreative → CraftResults → (Destroy) → Take` |
| 5 | custom names | `UNSIGNED_VAR_INT` | 0 |
| 6 | filter cause | `INT_LE` | 0 |

Gửi bằng `request.sendToServer(BedrockProtocol.class)` — **không** dùng `send()`
generic (bẫy deferred replay, memory 11/11).

**5.6.1 `StackRequestAction`**: `writeItemStackRequestAction` ghi 2 field —
`UNSIGNED_VAR_INT` variant = `id` nếu `id≤6` else `id-2`, rồi `UNSIGNED_BYTE` legacy id.

| ID | Tên | Variant | Payload production |
| --- | --- | --- | --- |
| 0 | `Take` | 0 | `UNSIGNED_BYTE` count · source `StackRequestSlot` · destination `StackRequestSlot` |
| 1 | `Place` | 1 | chỉ mode thử nghiệm `place` |
| 4 | `Destroy` | 4 | `UNSIGNED_BYTE` count=`min(oldAmount,255)` · source `StackRequestSlot` |
| 6 | `Create` | 6 | chỉ mode thử nghiệm `create` |
| 14 | `CraftCreative` | 12 | `UNSIGNED_VAR_INT` creativeEntryId · `UNSIGNED_BYTE` timesCrafted (1..255) |
| 19 | `CraftResults` (`CraftResultsDeprecated`) | 17 | `UNSIGNED_VAR_INT` resultItems=1 · `StackRequestItem` · `UNSIGNED_BYTE` timesCrafted |

Nguồn `Take` = `CreatedOutputContainer` slot **50**, stackId = `requestId`
(`creativeTakeSourceStackId`); đích = container thật, stackId 0. Thứ tự chuỗi khớp
ghi chú source và đối chiếu gophertunnel 2193 / Cinnabar 1.26.60 (memory 11/11
23:10). Mức **Đã xác minh (source + BDS status 0)**; byte wire tuyệt đối của
variant: **Suy luận**.

**5.6.2 `StackRequestSlot`**: `FULL_CONTAINER_NAME` (`BYTE` enum + `BOOLEAN`
hasDynamicId + optional `INT_LE`) · `UNSIGNED_BYTE` slot · `INT_LE` stackId.

**5.6.3 `StackRequestItem`** (`writeStackRequestItem`, `present` = có identifier và
không rỗng): `UNSIGNED_VAR_INT` descriptorVariant (1/0) · `UNSIGNED_BYTE`
legacyVariant (1/0) · nếu present: `STRING` identifier + `VAR_INT` metadata
(zigzag32) · `SHORT_LE` count · `UNSIGNED_VAR_INT` blockRuntimeId ·
`UNSIGNED_VAR_INT` extraDataLength (0 khi !present, **10** thường, **18** shield) ·
`SHORT_LE` hasNbt=0 · `INT_LE` canPlaceOn length=0 · `INT_LE` canBreak length=0 ·
(`LONG_LE` blockingTick chỉ shield). Đối chiếu `itemUserData` 10 byte gophertunnel.
Mức **Đã xác minh (source)**.

**5.6.4 `ItemStackNetResult`** (đã đọc enum): 0 `Success` · 2
`InvalidRequestActionType` · 6 `InvalidRequestCraftActionType` · 7
`InvalidCraftRequest` · 11 `InvalidCraftResultItem` · 13
`MissingCreatedOutputContainer` · 49 `FailedToValidateSrcSlot` · 50
`FailedToValidateDstSlot` · 51 `InvalidAdjustedAmount` · 55 `CannotPlaceItem`.
Quan sát: `.12` 20× status 6 (GUI thật 11/11), `.12` baseline CLI 4× status 49,
`.14` 3× `PacketMalformed` do `count` biên, `.15` 0 rejection / 0 malformed.

### 5.7 Trạng thái field-level theo từng packet (trung thực, không suy diễn)

| Nhóm | Số packet | Field / kiểu / endian / varint | Mức |
| --- | --- | --- | --- |
| Clientbound Bedrock inventory (§5.3) | 11 | **có đầy đủ** | Đã xác minh (source + log) |
| Serverbound Java inventory (§5.4) | 7 | **có đầy đủ** | Đã xác minh (source + log) |
| Cấu trúc con (§5.6) | 4 nhóm | **có đầy đủ** | Đã xác minh (source); byte wire tuyệt đối của `variant`: Suy luận |
| ID đối tác Java 26.3 (§5.5) | 144 + 69 = **213** | ID + hướng **có** | Đã xác minh (javap) |
| 169 clientbound Bedrock **ngoài** 11 packet §5.3 | 158 | **chưa kiểm kê field-level** | [Chưa kiểm chứng] |
| 69 serverbound Java **ngoài** 7 packet §5.4 | 62 | **chưa kiểm kê field-level** | [Chưa kiểm chứng] |
| 9 packet `java-counterpart-unmapped` | 9 | không có ánh xạ wire/registry | [Chưa kiểm chứng] |

Lý do chưa kiểm kê: chưa đọc source handler của từng packet; **không tự suy diễn
field từ tên packet**. Bảng §1/§2 vẫn cung cấp ID, hướng, đối tác Java và mức xác
minh cho toàn bộ 169 + 69 packet — phần thiếu là chi tiết từng field, không phải
thiếu tên packet. Các packet `ITEM_STACK_REQUEST` / `ITEM_STACK_RESPONSE` là trọng
tâm của lỗi `status 6` nên đã được kiểm kê đầy đủ ở §5.3/§5.6.

### 5.8 Bổ sung do bản `.15` (clamp `count`)

`handleCreativeModeSlot` trước `.15` ghi
`count = Math.max(1, Math.min(cfg.count(newItem.amount()), 255))`. Trên BDS thật
1.26.51, cùng layout `actions=3 crafts=1`, `count=64` → status 0 nhưng
`count=65/100/127` → `[SERVER DISCONNECT] Packet violation warning:
PacketMalformed (TerminatingConnection)`. Bản `.15` đổi thành
`Math.max(1, Math.min(cfg.count(newItem.amount()), 64))` (đã xác minh bytecode
`bipush 64 → Math.min → Math.max` trong `_m6/gw-data/javap-inventory-mpmc15.txt`).
Log `.15` ghi rõ `amount=65 count=64` rồi `Bedrock accepted item stack request
-1` ⇒ **giá trị wire bị clamp, không phải bỏ request**. Mức **Đã xác minh (BDS)**.
Giới hạn: item có `max_stack_size < 64` (vd 16) chưa đo ⇒ **[Chưa xác minh]**;
clamp cứng 64 có thể vượt max stack của những item đó.

### 5.9 §0 (giữ nguyên)

Không sửa byte Minecraft sau HELLO · không spoof/tắt online-mode · không
parse/inject sau Encryption Response · proxy chỉ bind `127.0.0.1` · không log
secret/token · chỉ iroh+iroh-relay · không TUN/TAP/admin · bản
ViaBedrock/ViaProxy patched là tiến trình GPL riêng, **KHÔNG** nhúng vào
`mpmc-agent-*.zip`.

---

## 6. Kết quả lượt automation 03:49–04:0x (BDS thật, jar `.15`)

**Không dùng `BUILD SUCCESSFUL` làm bằng chứng.** Bằng chứng duy nhất là dòng
`Bedrock accepted item stack request <id>` (in khi `status == 0` đọc được từ
`ITEM_STACK_RESPONSE` của BDS) và `oldNetId` thật. Log:
`_m6/gw-data/gateway-test2/vp-aut14-test2.log` (baseline `.14`) và
`vp-mpmc15-test2.log` (sau fix), `_m6/bds51/bds51-run.log`.

### 6.1 Trạng thái đọc được lúc bắt đầu

- Phiên song song đã **chốt `mpmc.14`** (không còn `-exp`): `gradle.properties`
  `0.0.31-mpmc.14`, jar `1d19a731…` build 02:59–03:00, pin `3.4.14-mpmc.14`,
  zip `42d41767…` build 03:05, 2 gateway production `:25569`/`:25575` start 03:08.
  Hoạt động cuối cùng 03:09 ⇒ lượt này không còn xung đột ghi đè.
- **Hai việc còn dở**: `gateway-test2/` vẫn nạp jar `.13` (`62670568…`);
  `M6-PACKET-INVENTORY.md` trong zip 80 786 B nhưng local chỉ 36 337 B (§5/§6
  field-level bị ghi đè mất).
- Functional connect probe 03:5x: `25569/25575 CONNECT_OK`, `25570
  ECONNREFUSED`, `19132 ECONNREFUSED` trên TCP (bình thường, BDS dùng UDP).
  BDS PID 18420 sống.

### 6.2 Quy trình đã làm

1. Deploy `.14` vào `gateway-test2/` (backup `.13` → `.prev13`, không xoá), start
   `:25570` bind `127.0.0.1` → `127.0.0.1:19132`, stderr 0 byte.
2. **Tái hiện bằng CLI** (`_m5/client/*.js`, `minecraft-protocol` 1.21.1):
   `gui-repro.js` 8 case cặp creative-click + `status6-burst.js` 20 request cùng
   tick ⇒ **33 accepted / 0 rejected / 0 `status 6` / 0 status 49**. `status 6`
   gốc (GUI thật 11/11) **vẫn không tái hiện được bằng CLI** — đúng mọi lượt trước.
3. **14 case biên tách từng phiên** ⇒ phát hiện lỗi có căn cứ, tái hiện được trên
   `.14`: `count=1/64` → status 0; **`count=65/100/127` → BDS kick
   `Packet violation warning: PacketMalformed (TerminatingConnection)`** (log
   dòng 132/133, 147/148, 162/163 của `vp-aut14-test2.log`); `128/255/256` → drop
   an toàn; item rỗng → drop; lặp 5/10 → 5/10 accepted; `slot 200` → script
   timeout 45 s **[Chưa xác minh]** nguyên nhân (không có WARN trong log).
   Cùng layout `actions=3 crafts=1`, chỉ đổi `count` ⇒ nguyên nhân là **giá trị
   amount vượt `max_stack_size`**, không phải lệch byte cấu trúc.
4. **Sửa root cause** `InventoryPackets.java`: clamp `count` tối đa 64 trước khi
   ghi `CraftResults` / `Take`. Bump `0.0.31-mpmc.15` + dependency ViaProxy +
   pin `3.4.14-mpmc.15` / SHA `71ea40ad…`. Build đúng quy trình
   (`publishToMavenLocal -x test --offline --no-daemon` → `build -x test --offline
   --no-daemon`, repo D `_m6/gateway-build/m2repo`): `VB_EXIT=0`, `VP_EXIT=0`.
   `javap -c -p` xác nhận bytecode `bipush 64 → Math.min → Math.max` có thật
   trong jar (`javap-inventory-mpmc15.txt`).
5. Deploy `.15` vào **cả 2 thư mục** `gateway/` + `gateway-test2/` (backup
   `.prev14-aut0359` = `1d19a731…`), restart `:25569` và `:25570`, cả 2 bind
   loopback, stderr 0 byte. Lab `:25575` giữ nguyên process (vẫn nạp `.14` trong
   bộ nhớ), không kill.

### 6.3 Bằng chứng BDS chấp nhận sau fix (`.15`)

| Case | `.14` (trước) | `.15` (sau) |
| --- | --- | --- |
| `count=64` | 1 accepted, 0 malformed | 1 accepted, 0 malformed |
| `count=65` | 0 accepted, **1 kick `PacketMalformed`** | **1 accepted (status 0)**, 0 malformed |
| `count=100` | 0 accepted, **1 kick `PacketMalformed`** | **1 accepted (status 0)**, 0 malformed |
| `count=127` | 0 accepted, **1 kick `PacketMalformed`** | **1 accepted (status 0)**, 0 malformed |
| `count=128/255/256`, item rỗng, `count=0` | drop an toàn | drop an toàn |
| lặp 5 / 10 request cùng slot | 5 / 10 accepted | 5 / 10 accepted |

Log `.15` dòng 70–71: `amount=65 count=64` → `Bedrock accepted item stack request
-1`; dòng 79–80: `amount=100 count=64` → `Bedrock accepted …`. Tức request vẫn
được gửi, chỉ giá trị wire bị clamp — không phải bỏ request để che lỗi.

**Tổng `vp-mpmc15-test2.log`: 298 dòng, 57 `Bedrock accepted` (status 0),
0 `Bedrock rejected`, 0 `status 6`, 0 `status 49`, 0 `PacketMalformed`,
5 `[CLASSIFIED]`, 29 dòng `oldNetId` thật, 19 CONNECT / 19 DISCONNECT,
stderr 0 byte.** BDS `bds51-run.log`: **0 `PacketViolationWarning`**. 2 dòng
`java.net.SocketException: Connection reset` là hệ quả đóng sớm 2 phiên CLI
timeout, không phải lỗi dịch packet.

### 6.4 Hồi quy

5/5 script `exit=0` qua `:25570`: `verify-actions.js` **1.21.1 / 1.21.4 /
1.21.6**, `creative-slot-probe.js` 1.21.1, `use-item-on-probe.js` 1.21.1
(`stillConnected=true`, `errors=[]`). **Không còn lần kẹt CONFIGURATION 1.21.4**
như lượt 02:2x — nhưng đó là quan sát một lần, chưa đủ để khẳng định đã hết race:
**[Chưa xác minh]**.

### 6.5 Còn lại / [Chưa xác minh]

- `status 6` gốc vẫn **chưa có cặp "trước status 6 / sau status 0" trên cùng
  probe**: chỉ xuất hiện ở GUI thật 11/11, CLI không tái hiện được dù đã thử
  burst, item id âm và `USE_ITEM_ON` combo ở các lượt trước. `status 6` và
  `status 49` có chung root cause hay không: **[Chưa xác minh]**. Cảm quan kéo/đặt
  item bằng chuột trên GUI thật sau `.15`: **[Chưa xác minh]**.
- Clamp cứng 64: item có `max_stack_size < 64` (vd 16) chưa đo ⇒ **[Chưa xác minh]**.
- `count=0` và `slot 200` làm script CLI timeout (45 s) mà không có WARN trong log
  gateway: **[Chưa xác minh]** tầng chờ (client hay handler).
- 158 clientbound + 62 serverbound packet chưa kiểm kê field-level (§5.7);
  9 `java-counterpart-unmapped`; raw Bedrock frame id; bảng identifier
  Bedrock ↔ Java; LAN NetherNet/byulnuri; Java 1.21.7+/1.21.11; BDS 1.26.60/2225;
  render đồ hoạ đầy đủ; death screen Java.
- Lab `:25575` (PID 25624) vẫn nạp jar `.14` trong bộ nhớ dù production pin đã
  `.15`; sẽ nạp `.15` ở lần restart sau — **[Chưa xác minh]** ảnh hưởng, không
  thuộc 2 gateway production.

### 6.6 Đóng gói phát hành

- `./gradlew packageAgent -x test --offline --no-daemon` ⇒ `PKG_EXIT=0`;
  `build/dist/mpmc-agent-0.1.0.zip` được build lại để mang pin `.15` và 2 docs mới.
- Kiểm tra: `mpmc/docs/M6-BEDROCK.md` và `mpmc/docs/M6-PACKET-INVENTORY.md` trong
  zip **trùng SHA-256 với file local**; ranh giới GPL
  `names=0 fatjar=0 classes=0` (62 entry, không có jar ViaProxy/ViaBedrock, không
  có class `net/raphimc/viabedrock`).
- Giải nén zip vào `_m6/release-check15/` + copy jar `.15` vào `data/gateway/` →
  `sh mpmc/bin/mpmc --data-dir data gateway verify` ⇒
  `Trạng thái: SHA-256 khớp (mpmc-patched 3.4.14-mpmc.15)`, `VERIFY_EXIT=0`.
  Warning `xargs: assertion "bc_ctl.arg_max >= LINE_MAX"` là noise đã biết, không fatal.

---

## 7. Lượt automation 05:11–05:4x (2026-11-12) — bản `.16`, field-level `ITEM_STACK_REQUEST` / `CREATIVE_CONTENT`

### 7.1 Phạm vi bản `.16`

Pin `3.4.14-mpmc.16` / `ViaBedrock 0.0.31-mpmc.16`, SHA-256
`44bfbe8216af8b59a5fb2d9e9e75073da86e388494d3481b72fcd471161735a1`. Thay đổi duy nhất
trong `InventoryPackets.java`: thêm `clampStackCount(int) = max(1, min(v, 64))` và dùng
nó cho 5 vị trí ghi số lượng (`count` creative, `times crafted` ×2, `Destroy count`,
`StackRequestItem count`). **Không đổi giá trị wire production** (mọi giá trị production
vốn đã ≤64), không đổi thứ tự action, không đổi byte Minecraft sau HELLO.
`javap -c -p` trên jar `.16` đếm được 6 tham chiếu `clampStackCount` ⇒ bytecode có thật,
không chỉ là source. **Không dùng `BUILD SUCCESSFUL` làm bằng chứng** (xem §7.6).

### 7.2 `ITEM_STACK_REQUEST` — serverbound Bedrock, packetId 147 (field-level)

Nguồn: `InventoryPackets.handleCreativeModeSlot` (dòng 1012–1070),
`writeItemStackRequestAction` (1148), `writeItemStackRequestSlot` (1154),
`writeStackRequestItem` (1164); đối chiếu `javap -c -p` jar `.16` và phản hồi BDS
1.26.51 (protocol 2193). Thứ tự đúng thứ tự ghi ra `PacketWrapper`.

| # | Trường | Kiểu (`BedrockTypes` / `Types`) | Endian / mã hoá | Giá trị production | Mức xác minh |
|---|---|---|---|---|---|
| 1 | `requests` | `UNSIGNED_VAR_INT` | varuint32 (LEB128) | `1` | Đã xác minh (source + BDS status 0) |
| 2 | `requestId` | `VAR_INT` | zigzag32 → varint | `nextItemStackRequestId()`, âm lẻ (`-1/-3/…`) | Đã xác minh (log + `ITEM_STACK_RESPONSE` khớp id) |
| 3 | `actionCount` | `UNSIGNED_VAR_INT` | varuint32 | `3` (CraftCreative + CraftResults + Take) hoặc `4` (+ Destroy) | Đã xác minh (log `actions=3/4`) |
| 4 | mỗi action: `variant` | `UNSIGNED_VAR_INT` | varuint32, `id <= 6 ? id : id - 2` | xem bảng 7.3 | Đã xác minh (source + gophertunnel 2193) |
| 5 | mỗi action: `legacy id` | `UNSIGNED_BYTE` | 1 byte | bằng enum value | Đã xác minh (source) |
| 6 | payload action | xem 7.3 | — | — | Đã xác minh (source + BDS) |
| 7 | `customNames` | `UNSIGNED_VAR_INT` | varuint32 | `0` | Đã xác minh (source) |
| 8 | `filterCause` | `INT_LE` | little-endian int32 | `0` | Đã xác minh (source) |

`StackRequestSlot` (dùng trong Destroy/Take/Place/Swap): `FULL_CONTAINER_NAME`
(ContainerEnumName + optional dynamic id) · `UNSIGNED_BYTE slot` · `INT_LE stackId`
(little-endian). Nguồn slot created-output = `CreatedOutputContainer/50`, `stackId =
requestId` (id request làm luôn stack net id). **Đã xác minh** qua 35 `oldNetId` thật
trong log `.16`.

`StackRequestItem` (payload `CraftResults`): `UNSIGNED_VAR_INT descriptorVariant` (`1`
= name, `0` = none) · `UNSIGNED_BYTE legacyDescriptorVariant` · nếu present:
`STRING identifier` (lu32 length + UTF-8) + `VAR_INT metadata` (zigzag32) ·
`SHORT_LE count` (little-endian int16) · `UNSIGNED_VAR_INT blockRuntimeId` ·
extra data: `UNSIGNED_VAR_INT length` = 10 (shield 18) + `SHORT_LE hasNbt=0` +
2 × `INT_LE` (canPlaceOn / canBreak = 0) + (shield) `LONG_LE blockingTicks`.
`count` **phải nằm trong `[1, 64]`** — xem §7.4. **Đã xác minh** (source + BDS).

### 7.3 Bảng action type (ID lấy trực tiếp từ enum nguồn)

| Action | legacy id | variant trên dây | Payload | Mức |
|---|---|---|---|---|
| `Take` | 0 | 0 | `UNSIGNED_BYTE count` · source slot · destination slot | Đã xác minh (source + BDS) |
| `Place` | 1 | 1 | như `Take` | Đã xác minh (source + mode `place` status 0) |
| `Swap` | 2 | 2 | source slot · destination slot | Suy luận (enum + source, chưa bật `swap` trên BDS) |
| `Drop` | 3 | 3 | — | [Chưa kiểm chứng] (không dùng) |
| `Destroy` | 4 | 4 | `UNSIGNED_BYTE count` · source slot | Đã xác minh (log `actions=4`) |
| `Consume` | 5 | 5 | — | [Chưa kiểm chứng] (không dùng) |
| `Create` | 6 | 6 | `UNSIGNED_BYTE resultsSlot=0` | Đã xác minh (mode `create` → status 11) |
| `CraftRecipe` / `CraftRecipeAuto` | 12 / 13 | 10 / 11 | — | [Chưa kiểm chứng] (không dùng) |
| `CraftCreative` | 14 | 12 | `UNSIGNED_VAR_INT creativeEntryId` · `UNSIGNED_BYTE timesCrafted` (1..64) | Đã xác minh (source + BDS status 0) |
| `CraftResults` | 19 | 17 | `UNSIGNED_VAR_INT resultItems=1` · `StackRequestItem` · `UNSIGNED_BYTE timesCrafted` | Đã xác minh (source + BDS) |

Quy tắc variant `id <= 6 ? id : id - 2` (bỏ 2 id `PlaceInContainer`/`TakeOutContainer`
không gửi) khớp đối chiếu `gophertunnel-2193` ghi ở §5.6. Các action 9/10/11/15/16/17/18
có trong enum nhưng **chưa kiểm kê payload** ⇒ **[Chưa kiểm chứng]**.

### 7.4 Validation `count ∈ [1, 64]` của BDS (phát hiện mới, có bằng chứng byte-level)

`mode=empty-descriptor` trên `.15` ghi `StackRequestItem count=0` ⇒ BDS trả:

```
[SERVER DISCONNECT] Packet violation warning: PacketMalformed (TerminatingConnection)
Violating Packet: ItemStackRequest
 Context: 'the input is outside of the accepted range [1, 64]
readNoHeader failed! packetId: 147'
```

⇒ BDS 1.26.51 validate **mọi** field số lượng / `timesCrafted` của `ITEM_STACK_REQUEST`
(147) trong `[1, 64]`. Đây là root cause chung cho cả 2 họ lỗi đã đo:
`count=65/100/127` (lượt 02:2x/03:4x) và `count=0` (descriptor rỗng). Trước lượt này
các memory ghi "[Chưa xác minh] cần capture byte encoder Java" — nay có `Context` của
chính BDS. `.16` clamp tập trung tại encoder thay vì chỉ clamp `cfg.count(...)`.
Mức **Đã xác minh (BDS)**. Giới hạn: item có `max_stack_size < 64` (vd 16) chưa đo ⇒
**[Chưa xác minh]**; clamp cứng 64 có thể vượt max stack của những item đó.

### 7.5 `CREATIVE_CONTENT` (clientbound) — field-level + bảng ánh xạ identifier

Nguồn: handler `CREATIVE_CONTENT` (dòng 631–678) + dump thật
`_m6/gw-data/gateway-test2/creative-dump-aut16.tsv` (1980 entry, sinh từ chính gói
BDS qua `-Dmpmc.creative.dump`). Cấu trúc: `UNSIGNED_VAR_INT groupCount` · mỗi group:
`UNSIGNED_BYTE category` + `STRING name` + `BedrockItem icon` ·
`UNSIGNED_VAR_INT itemCount` · mỗi item: `UNSIGNED_VAR_INT entryId` + `BedrockItem item`
+ `UNSIGNED_VAR_INT groupIndex`. `BedrockItem` đọc bằng `itemType()` (xem §5.2).
**Đã xác minh** (source + `items=1980 stored=1980 remaining=0`).

5 `creativeId` từng gây `status 6` trên `.12` (log `vp-mpmc12-main.log` 22:17–22:45),
đối chiếu trực tiếp với dump:

| entryId | bedrockId | identifier | data | amount | blockRuntimeId | groupIndex |
|---|---|---|---|---|---|---|
| 450 | 168 | `minecraft:prismarine` | 0 | 1 | -1243235801 | 17 |
| 885 | -596 | `minecraft:sniffer_egg` | 0 | 1 | -637589777 | 46 |
| 1455 | -960 | `minecraft:damaged_anvil` | 0 | 1 | -467540639 | 88 |
| 1475 | -213 | `minecraft:composter` | 0 | 1 | 787090290 | 91 |
| 1602 | 138 | `minecraft:beacon` | 0 | 1 | 561914719 | 105 |
| 604 (đối chứng) | 1 | `minecraft:stone` | 0 | 1 | -2144268767 | 27 |

**Đính chính memory 11/11 23:10**: `138 = minecraft:beacon`, `168 = minecraft:prismarine`
(ghi ngược trước đó). Toàn bảng 1980 entry là ánh xạ Bedrock `bedrockId ↔ identifier`
đã đo; ánh xạ **Bedrock ↔ Java identifier** đầy đủ vẫn **[Chưa xác minh]**.

### 7.6 Săn `status 6` — kết quả âm tính có kiểm soát (ưu tiên 1)

`status 6 = InvalidRequestCraftActionType` (enum nguồn dòng 15). Trên `.12` có 20 lần
chỉ trong phiên GUI thật `JavaReal12` 22:17–22:45, `oldNetId=null`, hay thành cặp 2
request cùng slot, tự sạch sau `AutoCompaction` 22:47 (§5.7). Lượt này tái hiện bằng
CLI (`_m5/client/*.js`, `minecraft-protocol` 1.21.1) qua `127.0.0.1:25570` với cùng
BDS `127.0.0.1:19132`:

- Đúng 5 creative entry上面 bằng `cfg.force` + cặp 2 click (`gui-repro.js`) → **status 0**.
- Sweep 10 tổ hợp action chain (`-Dmpmc.req.control`):

| Mode control | Kết quả BDS |
|---|---|
| `full,crafts=2` / `crafts=3` / `descriptor-match` / `place` / `cc-only` / `cc-destroy-take` / `force=…,amount=100` | `status 0` (accepted) |
| `results-only` (thiếu `CraftCreative`) | `status 3 = ActionRequestNotAllowed` |
| `create` | `status 11 = InvalidCraftResultItem` |
| `empty-descriptor` | `.15`: kick `PacketMalformed`; `.16`: `status 0` |

⇒ **0 `status 6`** trong mọi tổ hợp. Giả thuyết "sai action chain sinh status 6" bị
loại bỏ có bằng chứng; các mã lân cận (3, 11) được đo thay vì suy diễn. Cặp
"trước `status 6` / sau status 0" **vẫn chưa có trên cùng một probe** (phía trước là
GUI thật trên `.12`, phía sau là CLI trên `.16`); nguyên nhân gốc `status 6`
**[Chưa xác minh]**, nghi do trạng thái BDS tích lũy (cửa sổ 22:17–22:45 sạch sau
AutoCompaction). Không khẳng định `.16` "triệt tiêu status 6".

### 7.7 Bằng chứng BDS chấp nhận bản `.16` (KHÔNG phải BUILD SUCCESSFUL)

Cùng probe `mode=empty-descriptor,force=1455`, cùng BDS, cùng control file:

| Bản | Thời gian | Kết quả |
|---|---|---|
| `.15` | 05:19:43 | `PacketMalformed (TerminatingConnection)`, 0 accepted, client bị ngắt |
| `.16` | 05:31:54 | `Bedrock accepted item stack request -1` (**status 0**), 0 malformed, client sống |

Log `_m6/gw-data/gateway-test2/vp-mpmc16-test2.log`: **295 dòng / 67 `Bedrock accepted`
(status 0) / 0 rejected / 0 `status 6` / 0 `PacketMalformed` / 35 `oldNetId` thật /
10 CONNECT / 10 DISCONNECT / stderr 0 byte**. BDS `bds51-run.log`:
**0 `PacketViolationWarning`**. Dòng accepted chỉ in khi `status == 0` đọc từ
`ITEM_STACK_RESPONSE` (dòng 713–715) ⇒ phản hồi BDS thật.

### 7.8 Test biên + hồi quy (ưu tiên 3)

`edge-cases-aut12.json` 19 case (item rỗng, `count=0/1/127/255/256`, lặp 5/10 cùng
slot, `window_click` trước/sau/không kèm creative, slot 200, empty↔filled cùng slot,
slot 0/45): `exit=0`, không kick, không timeout, 5 `[CLASSIFIED]` tường minh.
`status6-burst.js` 20 request cùng tick `exit=0`. Baseline `gui-repro.js` 8 case `exit=0`.
Hồi quy **5/5 `exit=0`** (`verify-actions.js` 1.21.1 / 1.21.4 / 1.21.6,
`creative-slot-probe.js` — 1 lần retry do kẹt CONFIGURATION 35 s,
`use-item-on-probe.js` với `errors=[]`). Lần kẹt CONFIGURATION vẫn **[Chưa xác minh]**
nguyên nhân (mẫu lặp lại từ các lượt trước, retry sạch).

### 7.9 Cập nhật bảng trạng thái kiểm kê (trung thực)

| Nhóm | Số lượng | Trạng thái field-level |
|---|---|---|
| 11 clientbound inventory (§5.3) + `CREATIVE_CONTENT` (§7.5) | 12 | **Đã xác minh** |
| 7 serverbound inventory (§5.4) + `ITEM_STACK_REQUEST` (§7.2) | 8 | **Đã xác minh** |
| 158 clientbound Bedrock còn lại | 158 | **[Chưa kiểm chứng]** |
| 62 serverbound Java còn lại | 62 | **[Chưa kiểm chứng]** |
| 9 `java-counterpart-unmapped` | 9 | **[Chưa kiểm chứng]** |
| action payload 9/10/11/15/16/17/18, raw Bedrock frame id trên dây | — | **[Chưa kiểm chứng]** |

§0 giữ nguyên: không sửa byte sau HELLO · không spoof/tắt online-mode · không
parse/inject sau Encryption Response · proxy chỉ bind `127.0.0.1` · không log
secret/token · chỉ iroh + iroh-relay · không TUN/TAP/admin · bản ViaBedrock/ViaProxy
patched là tiến trình GPL riêng, KHÔNG nhúng vào `mpmc-agent-*.zip`.

---

## 8. Lượt automation 06:57–07:1x (2026-11-12) — packet id Bedrock trên dây + 9 `java-counterpart-unmapped` (jar `.16`)

> Append có nhãn thời gian, KHÔNG ghi đè §1–§7. Kiểm tra trước khi sửa:
> `M6-PACKET-INVENTORY.md` mtime `2026-11-12 05:36:24`, 809 dòng, heading cuối §7;
> `M6-BEDROCK.md` `05:37:14`; zip `cd44f362…` `05:37:36` ⇒ không có phiên song song.
> Jar production vẫn `.16` SHA `44bfbe82…`, BDS `1.26.51.1` PID 18420.

### 8.1 Nguồn số liệu và ranh giới của cụm "trên dây"

- Gateway audit lab `127.0.0.1:25580`, jar **`.16`** (`44bfbe82…`, copy từ production,
  **không build lại**), `-Dmpmc.audit=true`, bind loopback → BDS `127.0.0.1:19132`.
  Lab audit của lượt 05:55 là jar `.13` ⇒ kết quả đó **không áp sang `.16`**; lượt này
  dựng lại bằng đúng jar production.
- Client `_m5/client/packet-audit-probe.js` / mineflayer 1.21.4 / user `AUD16A`:
  `exit=0`, `errors=[]`, `stillConnected=true`, 36 clientbound + 17 serverbound Java distinct,
  31 lệnh BDS có phản hồi (một số `Incorrect permission level` là cấu hình quyền host,
  không phải lỗi dịch).
- `[AUDIT] PRE dir=CLIENTBOUND … id=… name=… bytes=…` do
  `BedrockProtocol.transform()` (`BedrockProtocol.java:362–369`) ghi **trước**
  `super.transform()`; `preId = wrapper.getId()` là **packet id Bedrock ở mức pipeline
  ViaBedrock**, tức sau khi RakNet giải framing / giải nén và **trước khi** đổi sang id Java.
  `preBytes` = `inputBuffer.readableBytes()` (`:479–486`) = độ dài **payload sau packet id**,
  không phải hex dump frame. `packetName()` tra đúng enum Bedrock (`:473–490`), nên không
  có nhãn `UNKNOWN_BEDROCK_ID_*` giả như lỗi audit `.5` đã sửa.
- **Ranh giới trung thực**: đây KHÔNG phải raw RakNet frame (UDP header + RakNet datagram
  + compression + encryption). Bắt hex frame thật ở tầng UDP giữa gateway và BDS chưa làm:
  **[Chưa kiểm chứng]**.
- `bytes=-1` (id 10 `SET_TIME`) nghĩa là `inputBuffer == null` lúc audit đọc, **không** phải
  payload 0 byte; không dùng giá trị này để kết luận kích thước gói.

### 8.2 Cân bằng phase và tính toàn vẹn (đếm từ log thật `vp-audit16-0657.log`)

| Phase | Số dòng |
|---|---|
| `PRE` | **1160** |
| `POST` | 479 |
| `CANCEL` | 676 |
| `DEFERRED` | 5 |
| `CLASSIFIED` | 40 |

`PRE 1160 = POST 479 + CANCEL 676 + DEFERRED 5` ✅ cân bằng tuyệt đối. Chiều clientbound
`PRE = 284` / **53 id distinct**; chiều serverbound `PRE = 876` / **27 id distinct**.
`UNKNOWN_BEDROCK_ID=0`, `hasHandler=false=0`, `error=true=0`, `unmapped=true=0`,
`UNCLASSIFIED=0`, `SEVERE=0`, `.err` 0 byte. Bảng dưới đếm **chỉ dòng `PRE`** (bản nháp
`audit16-report-0657.txt` đếm mọi phase nên `count` gấp đôi — đã đính chính).

### 8.3 Bảng 53 packet id Bedrock clientbound quan sát được (1 phiên, jar `.16`)

| ID | Tên Bedrock | PRE | payload bytes | ID | Tên Bedrock | PRE | payload bytes |
|---:|---|---:|---:|---:|---|---:|---:|
| 2 | PLAY_STATUS | 2 | 4 | 136 | CLIENT_CACHE_MISS_RESPONSE | 7 | 1..8468 |
| 3 | SERVER_TO_CLIENT_HANDSHAKE | 1 | 427 | 143 | NETWORK_SETTINGS | 1 | 10 |
| 6 | RESOURCE_PACKS_INFO | 1 | 27 | 145 | CREATIVE_CONTENT | 1 | 56420 |
| 7 | RESOURCE_PACK_STACK | 1 | 10 | 148 | ITEM_STACK_RESPONSE | 1 | 17 |
| 9 | TEXT | 2 | 28..30 | 160 | PLAYER_FOG | 1 | 1 |
| 10 | SET_TIME | 1 | -1 | 161 | CORRECT_PLAYER_MOVE_PREDICTION | 1 | 36 |
| 11 | START_GAME | 1 | 73879 | 162 | ITEM_REGISTRY | 1 | 113448 |
| 21 | UPDATE_BLOCK | 4 | 10 | 165 | SYNC_ENTITY_PROPERTY | 13 | 72..250 |
| 25 | LEVEL_EVENT | 1 | 15 | 174 | SUB_CHUNK | 1 | 34098 |
| 29 | UPDATE_ATTRIBUTES | 2 | 841..2768 | 187 | UPDATE_ABILITIES | 2 | 33 |
| 39 | SET_ENTITY_DATA | 10 | 16..393 | 188 | UPDATE_ADVENTURE_SETTINGS | 1 | 5 |
| 42 | SET_HEALTH | 1 | 1 | 198 | CAMERA_PRESETS | 1 | 337 |
| 43 | SET_SPAWN_POSITION | 1 | 32 | **199** | **UNLOCKED_RECIPES** | **2** | 656..662 |
| 45 | RESPAWN | 3 | 14 | **302** | **TRIM_DATA** | **1** | 1269 |
| 46 | CONTAINER_OPEN | 1 | 6 | 313 | JIGSAW_STRUCTURE_DATA | 2 | 209307 |
| 47 | CONTAINER_CLOSE | 1 | 3 | 314 | CURRENT_STRUCTURE_FEATURE | 6 | 1 |
| 48 | PLAYER_HOTBAR | 1 | 3 | 320 | CAMERA_AIM_ASSIST_PRESETS | 1 | 1096 |
| 49 | INVENTORY_CONTENT | 4 | 20..444 | 337 | VOXEL_SHAPES | 2 | 23178 |
| **52** | **CRAFTING_DATA** | **1** | 1042769 | 338 | CAMERA_SPLINE | 1 | 1 |
| 58 | LEVEL_CHUNK | 113 | 18 | 344 | SYNC_WORLD_CLOCKS | 4 | 16..239 |
| 59 | SET_COMMANDS_ENABLED | 1 | 1 | 65 | LEGACY_TELEMETRY_EVENT | 17 | 15..60 |
| 60 | SET_DIFFICULTY | 1 | 1 | 70 | CHUNK_RADIUS_UPDATED | 2 | 1 |
| 63 | PLAYER_LIST | 3 | 21219 | 72 | GAME_RULES_CHANGED | 1 | 749 |
| 76 | AVAILABLE_COMMANDS | 1 | 395698 | 79 | COMMAND_OUTPUT | 28 | 87..114 |
| 119 | AVAILABLE_ENTITY_IDENTIFIERS | 1 | 8907 | 121 | NETWORK_CHUNK_PUBLISHER_UPDATE | 22 | 8..10 |
| 122 | BIOME_DEFINITION_LIST | 1 | 7667 | 123 | LEVEL_SOUND_EVENT | 2 | 47..57 |
| 124 | LEVEL_EVENT_GENERIC | 2 | 63 | | | | |

Mức xác minh cho bảng này: **đã xác minh** (id + tên + số lượt + độ dài payload đọc trực
từ `[AUDIT] PRE` của jar `.16` trên BDS thật). 53/169 clientbound định nghĩa bởi Bedrock
1.26.51 ⇒ 116 loại còn lại **không xuất hiện trong phiên probe này**, không được kết luận
là "không tồn tại": **[Chưa kiểm chứng]** trên dây.

### 8.4 Bảng 9 packet `java-counterpart-unmapped`

ID Bedrock lấy từ `MinecraftPacketIds.java` (enum nguồn, **đã xác minh** bằng file):
`51 / 52 / 66 / 67 / 80 / 81 / 176 / 199 / 302`. Tên Java lấy từ
`minecraft-data/pc/1.21.1/protocol.json` và `1.21.6/protocol.json` (`play.toClient`
mappings, **đã xác minh** bằng dữ liệu). Việc gọi đó là "counterpart danh nghĩa" là
**suy luận** — chưa có bằng chứng runtime ánh xạ wire/registry.

| ID Bedrock | Tên Bedrock | Có trên dây (phiên này) | Java counterpart danh nghĩa | Lý do ViaBedrock chưa ánh xạ | Mức xác minh |
|---:|---|---|---|---|---|
| 51 | CONTAINER_SET_DATA | PRE=0 | `craft_progress_bar` (`0x14` 1.21.1 / `0x13` 1.21.6) | cần ánh xạ container id + property index (fuel/progress/enchant level…) | ID đã xác minh; counterpart suy luận; wire **[Chưa kiểm chứng]** |
| 52 | CRAFTING_DATA | **PRE=1**, 1 042 769 B, `CLASSIFIED` ×1 | `declare_recipes` (`0x77` / `0x7e`) | Bedrock gửi toàn bộ bảng recipe; cần registry recipe type + item id 2 chiều | **Đã xác minh** id/bytes/classified; counterpart suy luận |
| 66 | SPAWN_EXPERIENCE_ORB | PRE=0 | `spawn_entity_experience_orb` (`0x02`, **chỉ 1.21.1**; 1.21.6 gộp vào `spawn_entity`) | cần entity runtime id → Java entity id + unique id | ID đã xác minh; wire **[Chưa kiểm chứng]** |
| 67 | MAP_ITEM_DATA | PRE=0 | `map` (`0x2c` cả 2 bản) | schema map id / icon / decoration khác nhau | ID đã xác minh; wire **[Chưa kiểm chứng]** |
| 80 | UPDATE_TRADE | PRE=0 | `trade_list` (`0x2d` cả 2 bản) | cần item + villager entity id + recipe id | ID đã xác minh; wire **[Chưa kiểm chứng]** |
| 81 | UPDATE_EQUIP | PRE=0 | `entity_equipment` (`0x5b` / `0x5f`) | cần entity runtime id → Java entity id + equipment slot enum | ID đã xác minh; wire **[Chưa kiểm chứng]** |
| 176 | PLAYER_START_ITEM_COOLDOWN | PRE=0 | `set_cooldown` (`0x17` / `0x16`) | cần item identifier → Java item id + cooldown group | ID đã xác minh; wire **[Chưa kiểm chứng]** |
| 199 | UNLOCKED_RECIPES | **PRE=2**, 656..662 B, `CLASSIFIED` ×2 | `unlock_recipes` (`0x41`, 1.21.1) → 1.21.6 thay bằng `recipe_book_add/remove/settings` (`0x43/0x44/0x45`) | khác biệt schema theo version + recipe id mapping | **Đã xác minh** id/bytes/classified; counterpart suy luận |
| 302 | TRIM_DATA | **PRE=1**, 1269 B, `CLASSIFIED` ×1 | registry `minecraft:trim_pattern` / `trim_material` qua `registry_data` | cần ánh xạ registry entry, không phải packet riêng | **Đã xác minh** id/bytes/classified; counterpart suy luận |

`CLASSIFIED` của 3 packet này trong log: `[AUDIT] CLASSIFIED dir=CLIENTBOUND id=302 name=TRIM_DATA
reason=java-counterpart-unmapped`, tương tự `id=52` ×1 và `id=199` ×2 ⇒ **4 dòng khớp
đúng 4 lượt PRE**. Đây là bằng chứng trực tiếp rằng 3 packet không rơi fallback im lặng mà
được tiêu thụ có lý do, đúng thiết kế §5.5 / `CLIENTBOUND_DISPOSITIONS`
(`BedrockProtocol.java:85–96`).

### 8.5 Kết luận và phần còn lại

- Đã có **bảng id Bedrock trên dây ở mức pipeline ViaBedrock** cho jar `.16`, kèm cân bằng
  phase và 0 lỗi. Đây là lời giải một phần cho mục "raw Bedrock frame id" trong danh sách
  [Chưa xác minh] của các lượt trước; **raw RakNet hex frame** vẫn **[Chưa kiểm chứng]**.
- 6/9 `java-counterpart-unmapped` chưa xuất hiện trong phiên probe ⇒ chỉ có ID từ enum
  nguồn, chưa có payload/byte đo được: **[Chưa kiểm chứng]**. Muốn dịch thật cần bổ sung
  ánh xạ registry item/entity/container/recipe và test riêng từng packet.
- `status 6` gốc và cặp "trước/sau" trên cùng probe: xem §8.6 dưới đây (lượt này cũng đo).
- §0 giữ nguyên: không sửa byte sau HELLO · không spoof/tắt online-mode · không
  parse/inject sau Encryption Response · proxy chỉ bind `127.0.0.1` · không log
  secret/token · chỉ iroh + iroh-relay · không TUN/TAP/admin · bản ViaBedrock/ViaProxy
  patched là tiến trình GPL riêng, KHÔNG nhúng vào `mpmc-agent-*.zip`.

### 8.6 Cặp "trước/sau" `count=100` trên `.12` ↔ `.16` (cùng probe, cùng BDS)

Đọc lại 20 dòng `status 6` gốc trong `_m6/gw-data/gateway/vp-mpmc12-main.log`
(22:17:49–22:45:14, `JavaReal12`): mọi dòng đều `oldNetId=null`, `old` có thể rỗng hoặc
đã chứa item (`new=138:0x64 old=138:0x64`, `new=-596:0x64 old=-596:0x64`,
`new=168:0x64 old=168:0x64`). **Bẫy log quan trọng**: `0x64` ở đây là **64 thập phân**
(quy ước `0x` + số thập phân đã ghi ở §5.9), **không phải 100**. Vậy `status 6` gốc xảy ra
với `count` nằm trọn trong `[1,64]`.

Dựng 2 lab riêng, cùng file case `_m6/gw-data/gateway-s6-12/s6-beacon100-0657.json`
(Java 1.21.1 `beacon id=396`, `count=100`, slot 39, 1 prime + 1 cặp 2 packet),
cùng BDS `127.0.0.1:19132`:

| Bản | Cổng | `amount` ghi ra wire | Kết quả BDS thật |
|---|---|---|---|
| `.12` (`b8943ebb…`) | `127.0.0.1:25578` | `100` (không clamp) | `Packet violation warning: PacketMalformed (TerminatingConnection)` / `the input is outside of the accepted range [1, 64]` / `packetId: 147` → kick ngay, **0 accepted, 0 `status 6`** |
| `.16` (`44bfbe82…`) | `127.0.0.1:25579` | `100 → count=64` | **3/3 `Bedrock accepted` (status 0 đọc từ `ITEM_STACK_RESPONSE`)**, `oldNetId=1627/1628` thật, 0 rejected / 0 `PacketMalformed` / 0 violation, client sống tới `done` |

⇒ Giả thuyết "count vượt `[1,64]` sinh `status 6`" **bị loại bỏ có bằng chứng**: `.12`
với count=100 cho `PacketMalformed`, không phải `status 6`. Kết quả này là **bằng chứng
độc lập thứ 2** (sau đính chính 06:06) xác nhận clamp `[1,64]` của `.15`/`.16` sửa đúng
lỗi terminate-connection. **`status 6` gốc vẫn [Chưa xác minh]**; `.16` không được khẳng
định triệt tiêu nó. 2 lab stderr 0 byte, không đụng 2 gateway production.

### 8.7 Định vị 5 case "drop không WARN" trên `.16` (lời giải cho mục tồn đọng)

Chạy lại đúng `_m6/gw-data/gateway-audit13/edge-drop-cases.json` (5 case: `empty-item`
id=0/c=0, `count-0` id=396/c=0, `count-255`, `count-256`, `empty-then-filled`) qua
audit lab **`.16`** `:25580`, user `DROP16`, jar `44bfbe82…`, `exit=0`, không
`socketClosed` sớm. Kết quả đếm từ cửa sổ log `drop16-window.log`:

| Mẫu | Số dòng |
|---|---|
| `[AUDIT] PRE dir=SERVERBOUND id=57 name=SET_CREATIVE_MODE_SLOT` | **5** |
| `[AUDIT] CANCEL dir=SERVERBOUND id=57` | **5** |
| `Creative slot … -> …` (log `:1083`, chỉ in khi tạo request) | **0** |
| `[CLASSIFIED] creative slot … not sent to BDS` (`:164–166`) | **2** |
| `Bedrock accepted` / `Bedrock rejected` | 0 / 0 |
| `WARN` / `Unhandled creative mode slot` | 0 / 0 |

⇒ **5/5 packet rời client và tới gateway; encoder Java không drop** (khẳng định lại
bằng `[AUDIT] PRE` thay vì suy từ `JSON.stringify(notch).length` như lượt 05:55).
Drop nằm ở handler `InventoryPackets.java`, và **đã tách được từng nhánh**:

| Case | Nhánh | Bằng chứng | Mức |
|---|---|---|---|
| `empty-item` (slot 36), `count-0` (slot 37), `empty-then-filled` (slot 11) | `:932–935` `newItem.isEmpty() && oldItem.isEmpty()` → return, xoá in-flight guard | không có `Creative slot`, không `[CLASSIFIED]`, không WARN | **Đã xác minh** (loại trừ) |
| `count-255` (slot 38), `count-256` (slot 39) | `:924–930` → `skipCreativeRequest(..., "no Java -> Bedrock item mapping", false)` | 2 dòng `[CLASSIFIED] creative slot 38/39 … not sent to BDS: no Java -> Bedrock item mapping (javaItem=<unmapped>)` | **Đã xác minh** |

**Khác biệt với `.13` (lượt 05:55)**: trên `.13` cả 5 case đều "im lặng"; trên `.16`
2 case `count=255/256` đã được **phân loại tường minh** nhờ `skipCreativeRequest`.
3 case còn lại vẫn im lặng theo thiết kế (no-op khi cả item mới lẫn item cũ đều rỗng),
không phải lỗi mất gói.

⚠️ **Đừng nhầm 2 loại log**: `[AUDIT] CLASSIFIED dir=CLIENTBOUND … reason=…` là phân
loại packet clientbound (§8.4, 40 dòng trong phiên audit), còn
`[CLASSIFIED] creative slot … not sent to BDS` là phân loại request creative bị bỏ
(2 dòng ở đây). Bảng trên chỉ đếm loại thứ hai.

**[Chưa xác minh]**: vì sao `ItemRewriter.bedrockItem(javaItem)` trả rỗng với
`count=255/256` trong kịch bản slot-rỗng này, trong khi lượt 05:55 (`OVER13`,
`edge-overcount-cases.json`, prime 64 trước) từng ghi `count=255 → amount=255 accepted`
trên `.13`. Giả thuyết: `lengthPrefixedItem` đọc `itemCount` lệch kiểu/varint ở tầng
decoder gateway (khớp nhận xét cũ "count=256 cụt về 0"), **chưa capture byte để khẳng
định**; cũng chưa loại trừ khác biệt giữa jar `.13` và `.16`. Cần probe tách riêng.



## 9. Bản `.18` — regression động và ranh giới còn lại (2026-11-12)

Jar `3.4.14-mpmc.18`, SHA `bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8`.
Log `_m6/gw-data/gateway/vp-audit18-reg.log` (`-Dmpmc.audit=true`, BDS 1.26.51 /
protocol 2193, Java 1.21.1 CLI, 8 kịch bản probe).

| Chỉ số | Giá trị đo được |
|---|---|
| `audit_lines` | 44164 |
| `PRE / POST / CANCEL / DEFERRED / CLASSIFIED` | 21988 / 19737 / 2211 / 40 / 188 |
| `PRE == POST + CANCEL + DEFERRED` | đúng (21988) |
| `POST toHost=true` | 4189 |
| `POST unmapped=true` / `UNKNOWN_BEDROCK_ID` / `hasHandler=false` / `UNCLASSIFIED` | 0 / 0 / 0 / 0 |
| `AUDIT ERROR` / `SEVERE` / `PROXY KICK` / `Caught unhandled` / `NullPointerException` | 0 / 0 / 0 / 0 / 0 |
| `INTERACT PRE/POST/CANCEL` | 15 / 11 / 4 (`toHost=11`, `outName=INVENTORY_TRANSACTION`) |
| `USE_ITEM_ON sequence` | `1..80`, **không còn `seq=0`** |
| creative `accepted / rejected` | 50 / 0 |

**Thay đổi handler so với `.16`** (không đổi bảng ID/disposition):

| Packet / luồng | Trước | Sau (`.18`) | Mức |
|---|---|---|---|
| Java 1.21.1 `USE_ITEM_ON` (serverbound) | ViaBackwards để lại VarInt `sequence` trong `inputBuffer`; `InteractionPackets` đọc typed value ⇒ luôn `seq=0`, gửi `BlockChangedAck(0)` | Đọc `sequence` từ `((PacketWrapperImpl) wrapper).getInputBuffer()` khi hết typed `VAR_INT` | **Đã xác minh** (log `seq=1..80`) |
| Java `INTERACT` action=0 (`use_entity`) | `Protocol26_1To1_21_11` / `EntityPacketRewriter26_1` gọi `wrapper.cancel()` ⇒ không tới `BedrockProtocol` | `replaceServerbound(ServerboundPackets1_21_6.INTERACT, handler)` sinh `LOW_PRECISION_VECTOR(0,0,0)`, giữ nguyên ID mapping | **Đã xác minh** (`[PIPEDIAG]` 691→690, `INTERACT toHost=11`) |
| `EntityTracker.getEntityByJid()` | Unbox `Long` null ⇒ NPE kick cả phiên | Trả `null`; caller đã `cancel()` | **Đã xác minh** (`NPE=0`) |
| `MpmcPipelineDiagnostics` | không tồn tại | Diagnostic mặc định **TẮT**, bật bằng `-Dmpmc.debug.pipeline=true`, chỉ chèn probe no-op vào `protocolList` SERVERBOUND, không đụng `reversedProtocolList` | **Đã xác minh** (dùng để root-cause, production không bật) |

**Phát hành**: `./gradlew packageAgent` → `gateway verify` trên zip giải nén + jar `.18`
cho `VERIFY_EXIT=0`; `unzip -l mpmc-agent-0.1.0.zip` không có `ViaProxy-*.jar`.

### 9.1 `[Chưa xác minh]` — GUI-only `status 6` / `status 60`

Phiên GUI thật `.17` (`vp-live-25569.clean.log`, `JavaReal17`): 13 request accept tới
`16:52:01`; từ `16:52:55` **mọi** `ITEM_STACK_REQUEST` bị BDS từ chối
(`status 6 = InvalidRequestCraftActionType`, `actions=3/4 crafts=1`;
`status 60 = CannotDestroyItem` khi xoá item, `actions=1`) tới `17:08:46`.
Cùng `creativeId=931` và cùng layout chain lại **accept** ở CLI `.18` ⇒ khác biệt
trạng thái phiên, không phải item/action chain.

| Thí nghiệm CLI (cùng BDS, jar `.18`) | Kết quả |
|---|---|
| `status6-trade-repro.js`: spawn egg + click 16 entity + creative | 6/6 accept, 0 reject, **0 `UPDATE_TRADE`** |
| `status6-longrun.js`: 1 kết nối 150 s, movement 20 Hz, 49 click (có delete) | **47/47 accept**, 0 reject, 0 `SEVERE`/kick/`NPE` |
| `screen-state-probe.js` `baseline/close/entity/entity-all/burst20/flood/gui-like/idle` | 0 status 6/60 (trong `vp-audit18-reg.log`) |

Giả thuyết mở: BDS mở màn hình trade qua `UPDATE_TRADE` (clientbound id 80, hiện
xếp `java-counterpart-unmapped` và bị tiêu thụ) ⇒ screen handler không còn là creative
inventory ⇒ `CraftCreative`/`Destroy` trần bị từ chối. **Chưa có bằng chứng wire**
(không có dòng `UPDATE_TRADE` nào trong log), nên **không sửa source theo giả thuyết này**.
Cần capture raw frame của chính phiên GUI thật để có cặp trước/sau trên cùng phiên.

Ranh giới khác giữ nguyên như §4/§8: 77/169 clientbound Bedrock không có đối tác Java
(đăng ký/tiêu thụ/phân loại, **không** dịch thành gói Java), 9 packet
`java-counterpart-unmapped`, LAN NetherNet/byulnuri, Java 1.21.7+, BDS 2225,
render đồ hoạ: **[Chưa xác minh]**.

**Bổ sung live GUI `.18` (19:53–…)**: GUI Java 1.21.1 thật (`JavaReal18`) nối qua
raw-frame proxy `:25574 → :25570`: 12.026 frame capture trong `raw18-gui.log`,
66 `SET_CREATIVE_MODE_SLOT`, 20 `USE_ITEM_ON`, 6 `CONTAINER_OPEN`, 12 `CONTAINER_CLOSE`,
**26 `Bedrock accepted` / 0 `Bedrock rejected`**, 0 exception, 0 `UPDATE_TRADE`.
`status 6/60` **chưa tái hiện** trên `.18`, nhưng vì trigger gốc (tương tác entity/trade)
chưa xảy ra trong phiên này nên vẫn xếp **[Chưa xác minh]**, không đổi kết luận §9.1.
