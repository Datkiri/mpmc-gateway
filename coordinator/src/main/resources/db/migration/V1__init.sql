create table agents (
  endpoint_id       text primary key,
  display_name      text,
  created_at        timestamptz not null default now(),
  last_seen_at      timestamptz,
  banned_until      timestamptz
);

create table rooms (
  room_id           uuid primary key,
  code              char(8) not null unique,
  host_endpoint_id  text not null references agents(endpoint_id),
  host_token_hash   bytea not null,
  invite_token_hash bytea not null,
  approval_mode     text not null check (approval_mode in ('prompt','invite')),
  max_members       int not null default 8,
  state             text not null check (state in ('active','closed','expired')),
  created_at        timestamptz not null default now(),
  expires_at        timestamptz not null,
  last_heartbeat    timestamptz not null
);

create table members (
  room_id           uuid references rooms(room_id) on delete cascade,
  endpoint_id       text not null,
  role              text not null check (role in ('host','guest')),
  joined_at         timestamptz not null default now(),
  last_seen         timestamptz not null,
  primary key (room_id, endpoint_id)
);

create table join_requests (
  request_id        uuid primary key,
  room_id           uuid references rooms(room_id) on delete cascade,
  endpoint_id       text not null,
  state             text not null check (state in ('pending','approved','rejected','expired')),
  reason            text,
  created_at        timestamptz not null default now(),
  expires_at        timestamptz not null,
  approved_at       timestamptz
);

create table tickets (
  jti               uuid primary key,
  room_id           uuid not null,
  endpoint_id       text not null,
  issued_at         timestamptz not null default now(),
  expires_at        timestamptz not null,
  max_streams       int not null default 4
);

create table relay_grants (
  endpoint_id       text primary key,
  granted_until     timestamptz not null,
  reason            text
);

create table audit_log (
  id                bigserial primary key,
  ts                timestamptz not null default now(),
  actor             text,
  action            text not null,
  room_id           uuid,
  detail            jsonb
);

create index on join_requests (room_id, state);
create index on tickets (expires_at);
create index on audit_log (ts);
