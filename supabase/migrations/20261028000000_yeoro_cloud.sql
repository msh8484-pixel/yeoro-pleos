-- 여로 클라우드 기억(2b): 여행 등록·사진 메타데이터·비공개 사진 버킷
-- 테이블·RLS·GRANT·버킷을 이 파일 하나에 둔다 (2026-10-30 Supabase 정책 변경 대비 GRANT 명시).

create table public.trips (
  token       text primary key check (token ~ '^[A-Za-z0-9_-]{22}$'),
  device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),   -- sha256(기기 비밀)
  started_at  timestamptz not null,
  dest_name   text check (char_length(dest_name) <= 80),
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default now() + interval '30 days'
);
create table public.photos (
  id              uuid primary key default gen_random_uuid(),
  token           text not null references public.trips(token) on delete cascade,
  taken_at        timestamptz,
  taken_at_source text not null check (taken_at_source in ('exif','file','none')),
  uploaded_at     timestamptz not null default now(),
  path            text not null unique,      -- <token>/<id>.jpg
  thumb_path      text not null unique,      -- <token>/<id>_t.jpg
  w int not null check (w between 1 and 8192),
  h int not null check (h between 1 and 8192),
  bytes int not null check (bytes between 1 and 20971520)
);
create index photos_token_uploaded_idx on public.photos (token, uploaded_at);
alter table public.trips  enable row level security;
alter table public.photos enable row level security;
-- 정책 없음: anon·authenticated는 어떤 행도 못 본다. Edge Function만 service_role로 접근한다.
revoke all on public.trips, public.photos from anon, authenticated;
grant select, insert, update, delete on public.trips  to service_role;
grant select, insert, update, delete on public.photos to service_role;
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('trip-photos', 'trip-photos', false, 20971520, array['image/jpeg'])
on conflict (id) do nothing;
