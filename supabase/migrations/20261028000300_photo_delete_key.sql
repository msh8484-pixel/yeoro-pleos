-- 사진 지우기 (2b): 올린 사람이 자기 사진을 지울 열쇠의 해시.
-- 열쇠(128비트 난수, base64url 22자)는 POST /upload 201 응답으로 한 번만 돌려주고, 서버에는 sha256 hex만 남긴다.
-- 이 열이 생기기 전에 올린 사진은 null → 올린 사람 쪽 삭제는 403(차에서만 지울 수 있다).
alter table public.photos
  add column if not exists delete_key_hash text check (delete_key_hash ~ '^[0-9a-f]{64}$');

-- 권한(사용자 전역 규칙: public 테이블을 바꾸는 SQL에는 GRANT를 같은 파일에).
-- photos는 민감 테이블: anon·authenticated는 막고(RLS 정책 없음 = 직접 접근 전부 거부), service_role만 쓴다.
alter table public.photos enable row level security;
revoke all on public.photos from anon, authenticated;
revoke all on public.photos from service_role;
grant select, insert, update, delete on public.photos to service_role;
