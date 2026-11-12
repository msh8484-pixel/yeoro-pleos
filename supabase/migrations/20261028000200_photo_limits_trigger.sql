-- 사진 수 제한을 DB에서 원자적으로 지킨다 (2b Task 2 리뷰 수정).
-- 여행(토큰)당 200장, 토큰당 최근 60초 30장. upload 함수의 앞선 count 검사는 빠른 거절용으로 남기고,
-- 동시에 들어온 요청 사이의 경쟁은 이 트리거가 막는다: 같은 토큰의 삽입을 advisory lock으로 줄 세운 뒤 다시 센다.
-- (lock은 트랜잭션이 끝날 때 풀리므로, 다음 삽입은 앞 삽입이 커밋된 행을 새 스냅샷으로 본다.)
--
-- 새 public 테이블은 없다. 트리거 함수는 security invoker(기본값)다: 삽입 주체(service_role)가 photos를
-- select할 수 있으니 definer가 필요 없다. 그래도 직접 호출 경로는 막는다(execute revoke).
-- 오류: SQLSTATE P0001, message 'photo_limit' 또는 'rate_limited' → upload 함수가 429로 바꾼다.

create or replace function public.photos_enforce_limits()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  total  int;
  recent int;
begin
  perform pg_advisory_xact_lock(hashtext('yeoro.photos:' || new.token));

  select count(*) into total from public.photos where token = new.token;
  if total >= 200 then
    raise exception using errcode = 'P0001', message = 'photo_limit';
  end if;

  select count(*) into recent
    from public.photos
   where token = new.token
     and uploaded_at > now() - interval '60 seconds';
  if recent >= 30 then
    raise exception using errcode = 'P0001', message = 'rate_limited';
  end if;

  return new;
end;
$$;

revoke all on function public.photos_enforce_limits() from public, anon, authenticated;

drop trigger if exists photos_enforce_limits on public.photos;
create trigger photos_enforce_limits
  before insert on public.photos
  for each row execute function public.photos_enforce_limits();
