-- service_role 권한을 프로젝트 기본 권한(REFERENCES/TRIGGER/TRUNCATE 등)과 무관하게 같은 결과로 만든다.
-- 앞선 마이그레이션(20261028000000)은 이미 적용되어 고치지 않고, 여기서 revoke 후 다시 grant 한다.
revoke all on public.trips, public.photos from service_role;
grant select, insert, update, delete on public.trips  to service_role;
grant select, insert, update, delete on public.photos to service_role;
