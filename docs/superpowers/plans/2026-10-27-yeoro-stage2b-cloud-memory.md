# 여로 2b단계: 사진·공공데이터·다시 가기 (클라우드 기억) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2a의 "예시" 자리를 실제 데이터로 채운다. 가족이 QR로 올린 사진이 앨범의 장마다 들어가고, 공공데이터(날씨·일몰·관광지·축제·특일)가 앨범 칩과 기록문과 판단 엔진에 들어간다. 같은 곳을 다시 가면 "그때 vs 지금"이, 앨범 끝과 정기 방문에는 "다시 가기"가, 지금 화면에는 "1년 전 이번 주" 회상이 뜬다. 왕복 여행의 귀갓길을 도착 장에서 떼어 낸다. AI 기록문은 맨 마지막에 선택 기능으로 붙인다.

**Architecture:** 앱은 Supabase(프로젝트 `dghpdvpkgvamsaidndqa`)의 Edge Function만 부른다. 비밀 키(`DATA_GO_KR_KEY`, 선택 `ANTHROPIC_API_KEY`)는 함수 secrets에만 있고, APK에는 공개용 publishable 키만 들어간다. 서버 테이블(`trips`, `photos`)은 RLS를 켜고 정책 없이 두어 anon·authenticated가 직접 읽지 못하게 하고, 함수만 service role로 접근한다. 앱은 오프라인 우선이다. 받은 값은 모두 Room(v4)에 여행별로 저장하고, 네트워크가 없거나 기록 동의가 꺼져 있으면 아무것도 보내지 않고 2a처럼 동작한다. 네트워크 호출은 `RecorderService`가 가진 `CloudSync` 하나에 모으고, P단이거나 여행이 닫힐 때만 돈다. 사진 업로드 페이지는 Edge Function이 HTML을 못 내보내므로(아래 조사 결과) 정적 페이지로 따로 올리고, QR 주소(`…/functions/v1/upload?t=`)는 그대로 두되 함수가 그 페이지로 302 리다이렉트한다.

**Tech Stack:** 앱은 2a와 같다(Kotlin 2.4.21, AGP 9.4.1, Compose BOM 2026.09.00, Room 2.8.5, KSP 2.3.12). 새 앱 의존성은 없다(HTTP는 `HttpURLConnection`, JSON은 `org.json`, 이미지는 `BitmapFactory`). 서버는 Supabase Edge Functions(Deno, `npm:@supabase/supabase-js@2`, AI만 `npm:@anthropic-ai/sdk`), Postgres, Storage. Supabase CLI `~/.local/bin/supabase` v2.106. 서버 순수 모듈 테스트는 이미 설치된 Node 24(`~/.local/node/bin/node --test`, 타입 제거 내장)로 돌린다. Deno는 설치하지 않는다(배포는 `supabase functions deploy --use-api`가 서버에서 번들한다).

## Global Constraints
- 1단계·2a Global Constraints는 그대로 유지한다: `docs/superpowers/plans/2026-10-26-yeoro-stage1-car-core.md`, `docs/superpowers/plans/2026-10-27-yeoro-stage2a-incar-ui.md`, `.superpowers/sdd/global-constraints.md`(로컬 파일, 이 섹션을 덧붙여 둔다).
- 사용자 문구는 한국어로 쓴다. 본문 30sp 이상, 터치 영역 76dp 이상.
- 모든 모션은 `LocalMotion`(ux/Motion.kt)을 거치고 P단에서만 돈다. 주행 중 화면은 정적이고 핵심만 보인다. 새 카드(사진 도착, 회상, 그때 vs 지금, 다시 가기 QR)도 같다.
- 디버그 전용 장치는 `app/src/debug`에만 두고, 릴리스에서는 아무 일도 하지 않는다(`DebugHooks` 패턴). 디버그 로그에만 업로드 주소를 찍을 수 있다.
- 테스트: 엔진은 JUnit, 앱은 Robolectric `@Config(sdk = [34])`. Room 스키마를 바꾸면 `app/schemas/ai.yeoro.data.YeoroDb/<v>.json`을 커밋하고 `MigrationTest`에 이전 버전 → 새 버전 테스트를 더한다.
- 서버 순수 모듈(`supabase/functions/_shared/*.ts`, `*.js`)은 Node가 타입만 지워 실행할 수 있는 문법만 쓴다: `enum`·`namespace`·생성자 매개변수 속성 금지, 상대 import에 `.ts`/`.js` 확장자 필수, 외부 import 금지. 테스트는 같은 폴더의 `*.test.ts`(`node:test`, `node:assert/strict`).
- **비밀은 APK·git·로그에 절대 넣지 않는다.** 로컬 비밀은 `~/.config/yeoro/keys.env`(`DATA_GO_KR_KEY`, 선택 `ANTHROPIC_API_KEY`)에 있고, 값을 화면에 찍지 않는다. 읽을 때는 `set -a; . ~/.config/yeoro/keys.env; set +a`. Supabase CLI 인증은 `SUPABASE_ACCESS_TOKEN="$SUPABASE_YEORO_TOKEN"`(~/.zshrc)로만 주고 찍지 않는다. 명령 출력에 키가 섞일 수 있으면 `sed`로 가린다. APK에 들어가는 값은 Supabase URL과 publishable 키뿐이고, 이것도 git에는 넣지 않고 `local.properties`(gitignore됨)에서 BuildConfig로 넣는다.
- **public 스키마에 테이블을 만드는 SQL에는 같은 파일 안에 GRANT를 반드시 쓴다(사용자 전역 규칙, 2026-10-30 Supabase 정책 변경).** anon GRANT는 비로그인 사용자가 정말 읽어야 하는 테이블에만 준다. 여로의 `trips`·`photos`는 민감 테이블이라 anon·authenticated에는 주지 않고(`revoke all`), `service_role`에만 `select, insert, update, delete`를 준다. RLS는 반드시 켜고, 정책은 GRANT와 별개로 판단한다(여로는 정책 없음 = 직접 접근 전부 거부). serial/identity 컬럼을 쓰면 시퀀스 `grant usage, select`도 넣는다(여로는 uuid라 해당 없음). 테이블을 지우고 다시 만들 때도 GRANT를 다시 쓴다.
- **오프라인 우선.** 모든 네트워크 기능은 실패해도 화면이 깨지지 않는다. 받은 데이터는 Room에 저장하고 캐시를 보여 준다. 실제 데이터가 하나도 없을 때만 2a의 "예시" 칩을 보인다. 네트워크 없이 `scripts/scenario_demo.sh`(2a 흐름)가 그대로 통과해야 한다.
- **기록 동의가 꺼져 있으면(끔·나중에·미정) 네트워크 호출을 하나도 하지 않는다.** 예외 없음.
- 네트워크 호출은 P단이거나 여행을 닫을 때만 한다. 주행 중(D)에는 부르지 않는다.
- 차 밖으로 나가는 것은 여행 등록(공유 토큰, 출발 시각, 목적지 이름), 공공데이터 조회(목적지 좌표·날짜), AI(켰을 때만, 가족 이름을 뺀 사실 문장)뿐이다. 위치 기록(경로)은 절대 보내지 않는다.
- 공공데이터 칩과 문장에는 출처(기상청·천문연·TourAPI)를 붙인다. 예보 값은 "예보"라고 밝힌다.
- 커밋 메시지는 한국어로 쓰고 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다.

## 조사 결과 (2026-10-27, 우리 키로 직접 호출해 확인)

키는 data.go.kr 인코딩 전 키다. 서버에서는 `URLSearchParams`로 넣으면 알아서 인코딩된다(미리 인코딩하지 않는다). 공통 함정: 결과가 여러 개면 `items.item`이 배열, 하나면 객체, 없으면 `items`가 빈 문자열 `""`이다. 항상 정규화한다. 잘못된 키는 HTTP 200에 XML/JSON `OpenAPI_ServiceResponse.cmmMsgHeader.returnReasonCode = "30"`(SERVICE_KEY_IS_NOT_REGISTERED_ERROR)으로 온다.

**1. 기상청 단기예보 `https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/{op}`**
- 공통 파라미터: `serviceKey, pageNo=1, numOfRows, dataType=JSON, base_date=YYYYMMDD, base_time=HHMM, nx, ny`. 정상은 `response.header.resultCode == "00"`.
- 격자 변환(LCC, RE=6371.00877, GRID=5, SLAT1=30, SLAT2=60, OLON=126, OLAT=38, XO=43, YO=136) 확인값: 서울시청(37.5665, 126.9780) → (60, 127), 시연 집 마포(37.5563, 126.9220) → (59, 126), 경포해변(37.8055, 128.9087) → (93, 133), 제주(33.4996, 126.5312) → (53, 38).
- `getUltraSrtNcst`(초단기실황, 관측값): base_time `HH00`. 항목 `PTY`(강수형태), `REH`, `RN1`(1시간 강수mm), `T1H`(기온), `UUU`, `VEC`, `VVV`, `WSD`. 예: 10-27 15:00 경포 T1H 19.2, PTY 0. **최근 24시간만 준다**: 25시간 전 요청은 `resultCode "10"`, "최근 1일 간의 자료만 제공합니다."
- `getUltraSrtFcst`(초단기예보): base_time `HH30`, 6시간 앞까지, `SKY` 포함. 최근 24시간.
- `getVilageFcst`(단기예보): base_time `0200, 0500, 0800, 1100, 1400, 1700, 2000, 2300`(발표 후 약 10분 뒤 제공). 항목 `TMP, SKY, PTY, POP, PCP, REH, SNO, TMN, TMX, UUU, VVV, VEC, WAV, WSD`. `fcstDate/fcstTime/fcstValue`. 1400 발표 하나에 798행, 예보 범위는 발표 시각 다음 시간부터 약 4일 뒤 00시까지(10-27 1400 → 10-31 0000). **base_date는 오늘·어제·그제까지만**(10-25 0500·2300 성공, 10-24는 "최근 3일 간의 자료만 제공합니다.").
- 코드: `SKY` 1 맑음, 3 구름많음, 4 흐림. `PTY`(단기) 0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기. `PTY`(초단기) 0 없음, 1 비, 2 비/눈, 3 눈, 5 빗방울, 6 빗방울눈날림, 7 눈날림.
- **과거 날씨는 사흘이 지나면 이 API로 얻을 수 없다.** 과거 일자료 API(`AsosDalyInfoService`, `AsosHourlyInfoService`)는 우리 키로 "등록되지 않은 서비스키"라 쓸 수 없다(활용신청 필요). 그래서 날씨는 **도착 직후(P단)와 여행을 닫을 때 받아 차에 저장**하고, 그 뒤에는 다시 묻지 않는다. 받지 못한 여행에는 날씨 칩을 만들지 않는다(가짜로 채우지 않는다).

**2. 천문연 출몰시각 `https://apis.data.go.kr/B090041/openapi/service/RiseSetInfoService/getLCRiseSetInfo`**
- 파라미터: `serviceKey, locdate=YYYYMMDD, longitude=128.9087, latitude=37.8055, dnYn=Y`(십진 좌표), `_type=json`. 과거 날짜도 된다(2025-10-25 확인).
- 응답 `items.item`(객체 하나): `sunrise "0641  "`, `sunset "1736  "`(끝 공백 있음, HHMM), `location "강릉"`(가까운 지점으로 맞춰 줌), `civile`, `naute`, `aste`, `moonrise` 등. 경포 2026-10-24 일몰 17:36.

**3. 천문연 특일 `https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo`**
- 파라미터: `serviceKey, solYear=2026, solMonth=10`(생략하면 1년치), `numOfRows=50`, `_type=json`. 응답 항목 `dateKind "01", dateName "개천절", isHoliday "Y", locdate 20261003`. 2026년 22건(대체공휴일·선거일 포함), 2027년도 이미 나온다. 11월은 0건(`items: ""`), 12월은 1건(객체).

**4. TourAPI 4.0 `https://apis.data.go.kr/B551011/KorService2/{op}`**
- 공통 필수: `serviceKey, MobileOS=ETC, MobileApp=yeoro, _type=json`(빠지면 `resultCode "11"` NO_MANDATORY_REQUEST_PARAMETERS). 정상은 `resultCode "0000"`.
- `locationBasedList2`: `mapX=경도, mapY=위도, radius(m, 최대 20000), arrange=E(거리순), contentTypeId(선택), numOfRows, pageNo`. 항목 `contentid, contenttypeid, title, addr1, mapx, mapy, dist(m), cat1~3, lclsSystm1~3, lDongRegnCd, lDongSignguCd, firstimage, tel`. 경포해변 1km: 축제(15) "병오년 해맞이 행사" 173m, 음식점(39), 숙박(32)… 관광지(12)만 2km: 라카이 샌드파인 수영장 490m, 경포호수광장 859m. 결과는 빠짐이 있다(바로 옆 경포도립공원이 위치 검색에는 안 나오고 키워드 검색에는 나온다). 그래서 분류는 "500m 안의 여러 항목 중 하나라도"로 판단한다.
- contentTypeId: 12 관광지, 14 문화시설, 15 축제공연행사, 25 여행코스, 28 레포츠, 32 숙박, 38 쇼핑, 39 음식점. 캠핑장은 `cat3 "A03021700"`.
- `searchFestival2`: `eventStartDate=YYYYMMDD`(이날 이후 끝나는 행사가 나온다: 10-01로 물으면 09-24 시작 행사도 나옴), `lDongRegnCd=51`(강원), `lDongSignguCd=150`(강릉). 지역 코드는 `locationBasedList2` 결과의 `lDongRegnCd/lDongSignguCd`를 쓴다. 옛 `areaCode`는 KorService2에서 결과 0건. 항목 `title, eventstartdate, eventenddate, mapx, mapy, addr1, contentid`. 2026-10-24 강릉: **강릉커피축제 10-21~10-25**(시안의 "커피축제 둘째 날"과 같은 실재 축제), 강원한돈페스타 10-23~25.
- `detailCommon2?contentId=`: `homepage`는 빈 문자열, 맨 URL(`https://www.coffeefestival.net/`), 또는 `<a href="…">` HTML일 수 있다. href를 뽑고 http/https만 쓴다. `overview`, `tel`도 있다.
- 일일 한도(개발계정): 기상청 10,000회, TourAPI 1,000회, 천문연 10,000회. 여행당 호출이 10회 안쪽이라 충분하다.

**5. Supabase**
- 프로젝트에 함수·secrets 없음(2026-10-27 확인). 키: legacy anon/service_role JWT와 새 `sb_publishable_…`/`sb_secret_…`가 모두 있다. 함수 안에서는 `SUPABASE_URL`, `SUPABASE_SECRET_KEYS`(JSON, `default`)·`SUPABASE_PUBLISHABLE_KEYS`(JSON)를 쓰고, 없으면 `SUPABASE_SERVICE_ROLE_KEY`/`SUPABASE_ANON_KEY`로 물러난다.
- **Edge Function은 HTML을 내보낼 수 없다**: "GET requests that return `text/html` will be rewritten to `text/plain`"(공식 문서 Routing). `static_files`도 `--use-api` 배포에서는 안 된다. → 업로드 페이지는 정적 호스팅(Task 2), 함수는 302 리다이렉트와 JSON API만.
- 공개 테이블 기본 권한: 오늘(10-27)은 아직 정책 변경 전이라 새 public 테이블에 anon·authenticated 권한이 자동으로 붙는다. 그래서 마이그레이션은 `revoke all … from anon, authenticated`를 명시한다(변경 뒤에도 같은 결과).

## File Structure (신규/변경)
```
supabase/
├── config.toml                         # project_id, [functions.*] verify_jwt = false
├── migrations/20261028000000_yeoro_cloud.sql
└── functions/
    ├── _shared/http.ts                 # cors, json(), error(), requireApiKey(), clientIp
    ├── _shared/admin.ts                # service role client (Deno 전용, 테스트 없음)
    ├── _shared/validate.ts (+ .test.ts)# 토큰·기기 비밀·날짜 검사, sha256Hex
    ├── _shared/exif.js (+ .test.ts)    # JPEG EXIF DateTimeOriginal (서버·업로드 페이지 공용, 순수 JS)
    ├── _shared/datago.ts (+ .test.ts)  # items 정규화, KMA 격자, base 시각 고르기, 코드 → 한국어, homepage href
    ├── _shared/place.ts (+ .test.ts)   # contentType → PlaceKind, 축제 고르기, 날짜 차이
    ├── _shared/diary.ts (+ .test.ts)   # AI 요청 검증·프롬프트 (Task 11)
    ├── trip/index.ts                   # 여행 등록·삭제, 만료 정리
    ├── upload/index.ts                 # GET 302 → 정적 페이지, GET info JSON, POST 사진
    ├── photos/index.ts                 # 사진 목록 + 서명 URL (기기 비밀 필요)
    ├── outside/index.ts                # 날씨·일몰·관광지·축제·특일, /outside/holidays
    └── diary/index.ts                  # AI 기록문 (Task 11)
web/upload/index.html, web/upload/page.js   # 휴대폰 업로드 페이지(정적)
scripts/backend_smoke.sh, scripts/publish_upload_page.sh, scripts/exif_stamp.py, scripts/demo_upload.sh
app/src/main/java/ai/yeoro/
├── cloud/CloudConfig.kt                # BuildConfig URL·키, enabled
├── cloud/CloudApi.kt                   # 인터페이스 + CloudResult + 원격 모델
├── cloud/HttpCloudApi.kt               # HttpURLConnection 구현
├── cloud/CloudJson.kt                  # org.json 파싱(순수, 테스트)
├── cloud/DeviceSecret.kt               # 설치마다 SecureRandom 32바이트
├── cloud/CloudSync.kt                  # 등록·삭제 큐·공공데이터·사진 동기화 (동의 게이트)
├── cloud/PhotoFiles.kt                 # filesDir/photos/<tripId>/…, 다운로드·삭제
├── cloud/Outside.kt                    # OutsideInfo, 칩·문장 (순수)
├── cloud/Holidays.kt                   # 연도별 특일 캐시 (prefs)
├── data/YeoroDb.kt                     # v4: trip_outside, trip_photos, pending_deletes, trip_diary
├── data/ShareTokens.kt                 # 등록 표시, tokenOrNull, all()
├── ui/album/TripLegs.kt                # 왕복 여행 나누기 (순수)
├── ui/album/PhotoMatch.kt              # 사진 → 장 (순수)
├── ui/album/ThenNow.kt                 # 그때 vs 지금 (순수 + 화면)
├── ui/album/Again.kt                   # 다시 가기 링크 (순수 + QR 패널)
├── ui/common/PhotoImage.kt             # 파일 → Bitmap (LruCache, inSampleSize)
├── ui/live/Promo.kt                    # 사진 도착·회상 카드 고르기 (순수 + 카드)
└── ui/live/Memory.kt                   # 1년 전 이번 주 (순수)
```

---

### Task 1: Supabase 기반과 `trip` 함수 (여행 등록·삭제)

**Files:** Create `supabase/config.toml`, `supabase/migrations/20261028000000_yeoro_cloud.sql`, `supabase/functions/_shared/{http.ts,admin.ts,validate.ts,validate.test.ts}`, `supabase/functions/trip/index.ts`, `scripts/backend_smoke.sh`. Modify `.gitignore`(`supabase/.temp/`, `supabase/.branches/`), README(백엔드 절).

**Interfaces (Produces):**
- SQL (이 파일 하나에 테이블·RLS·GRANT·버킷을 모두 둔다):
```sql
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
```
- `config.toml`: `project_id = "dghpdvpkgvamsaidndqa"`, 함수 5개(`trip`, `upload`, `photos`, `outside`, `diary`) 모두 `verify_jwt = false`(업로드 페이지는 키 없이 부르고, 차가 쓰는 publishable 키는 JWT가 아니다). 대신 차가 부르는 함수는 `requireApiKey(req)`로 `apikey` 헤더가 `SUPABASE_PUBLISHABLE_KEYS`의 값 또는 `SUPABASE_ANON_KEY`와 같은지 본다(아니면 401).
- `_shared/validate.ts`: `isToken(s): boolean`(`^[A-Za-z0-9_-]{22}$`), `isDeviceSecret(s): boolean`(`^[A-Za-z0-9_-]{43}$`, 32바이트 base64url), `sha256Hex(s: string): Promise<string>`(WebCrypto), `parseIsoInstant(s): Date | null`(오프셋 필수).
- `_shared/http.ts`: `CORS`(`Access-Control-Allow-Origin: *`, headers `authorization, apikey, content-type, x-yeoro-device`, methods `GET, POST, DELETE, OPTIONS`), `json(body, status = 200)`, `fail(status, code: string)` → `{"error": code}`, `requireApiKey(req): Response | null`.
- `trip` HTTP 계약 (헤더 `apikey: <publishable>`, `content-type: application/json`):
  - `POST /functions/v1/trip` body `{"token":"<22>","device":"<43>","started_at":"2026-10-31T08:52:00+09:00","dest_name":"강릉 경포해변"|null}`
    - 201 `{"token","expires_at"}` 새로 등록 / 200 같은 기기가 다시 등록(만료일은 바꾸지 않음) / 409 `{"error":"token_taken"}` 다른 기기 / 400 `{"error":"bad_request"}` / 401
    - 등록할 때마다 만료 뒤 7일이 지난 여행을 최대 20개 정리한다(Storage `<token>/` 아래 파일 삭제 → 행 삭제). 스케줄러 없이 서버 보관 기간을 지킨다.
  - `DELETE /functions/v1/trip` body `{"token","device"}` → 204(없는 토큰도 204) / 403 `{"error":"device_mismatch"}`. Storage `trip-photos/<token>/` 전부와 행(사진은 cascade)을 지운다.

**동작:**
- 마이그레이션 적용: 이 저장소의 프로젝트 MCP `supabase-yeoro`의 `apply_migration`(name `yeoro_cloud`, 위 SQL)으로 적용하고, `list_migrations`가 기록한 버전으로 로컬 파일 이름을 맞춘다. MCP가 없으면 `supabase link --project-ref dghpdvpkgvamsaidndqa` 후 `supabase db push`(DB 비밀번호가 필요하면 사용자에게 묻는다).
- 적용 뒤 확인 SQL: `select grantee, privilege_type from information_schema.role_table_grants where table_name in ('trips','photos')` → `service_role`만, `select relrowsecurity from pg_class where relname in ('trips','photos')` → 둘 다 true. 보안 어드바이저(`get_advisors security`)에 새 경고가 없어야 한다("RLS enabled no policy"는 의도한 것이라 README에 이유를 적는다).
- secrets: `supabase secrets set --project-ref dghpdvpkgvamsaidndqa --env-file <(grep '^DATA_GO_KR_KEY=' ~/.config/yeoro/keys.env)` (값을 찍지 않는다). `ANTHROPIC_API_KEY`는 Task 11에서.
- 배포: `SUPABASE_ACCESS_TOKEN="$SUPABASE_YEORO_TOKEN" supabase functions deploy trip --use-api --project-ref dghpdvpkgvamsaidndqa`.
- `scripts/backend_smoke.sh`: `keys.env`를 읽지 않는다. publishable 키는 `local.properties`의 `yeoro.supabase.key`에서 읽고(값은 찍지 않음), 임의 토큰·기기 비밀을 `openssl rand`로 만든다. 단계마다 `OK  …`/`FAIL …`, 실패 시 0이 아닌 종료. Task 1에서는: 등록 201 → 재등록 200 → 다른 기기 409 → 잘못된 토큰 400 → 키 없이 401 → 다른 기기로 삭제 403 → 삭제 204. 이후 Task마다 단계를 더한다.

- [ ] TDD: `validate.test.ts` — 토큰 22자 통과/21자·`+` 거부, 기기 비밀 43자, `sha256Hex("abc")` = `ba7816bf…15ad`, 오프셋 없는 ISO 거부. `~/.local/node/bin/node --test 'supabase/functions/**/*.test.ts'` 실패 확인 → 구현 → 통과
- [ ] 마이그레이션 적용 + 권한·RLS 확인 SQL 결과를 태스크 보고에 붙인다
- [ ] secrets 설정, `trip` 배포, `scripts/backend_smoke.sh` 통과
- [ ] 커밋: `백엔드 기반: Supabase 마이그레이션(trips·photos, RLS·GRANT, 비공개 버킷)과 trip 함수, 스모크 테스트`

### Task 2: 사진 올리기 (`upload` 함수, 휴대폰 페이지, `photos` 함수)

**Files:** Create `supabase/functions/_shared/{exif.js,exif.test.ts}`, `supabase/functions/upload/index.ts`, `supabase/functions/photos/index.ts`, `web/upload/index.html`, `web/upload/page.js`, `scripts/publish_upload_page.sh`, `scripts/exif_stamp.py`, `test/fixtures/exif/`(작은 JPEG 3개: EXIF 있음·오프셋 있음·EXIF 없음). Modify `scripts/backend_smoke.sh`.

**Interfaces (Produces):**
- `_shared/exif.js`(순수 JS, 브라우저·Deno·Node 공용, 외부 의존 없음):
  - `export function exifDateTimeOriginal(buf: ArrayBuffer): { local: string, offset: string | null } | null` — APP1 `Exif\0\0` → TIFF(II/MM) → IFD0 → ExifIFD(0x8769) → `0x9003 DateTimeOriginal`("YYYY:MM:DD HH:MM:SS"), `0x9011 OffsetTimeOriginal`("+09:00"). 첫 64KB만 본다. 깨진 입력은 null(예외 없음).
  - `export function exifToIso(e, defaultOffset = "+09:00"): string | null` — "2026:10:31 13:30:00" → "2026-10-31T13:30:00+09:00".
  - `export function fitWithin(w, h, max): { w: number, h: number }` — 긴 변을 max 이하로, 비율 유지, 정수.
- 정적 페이지 호스팅: 기본안은 공개 저장소 `msh8484-pixel/yeoro-upload`의 GitHub Pages(`https://msh8484-pixel.github.io/yeoro-upload/?t=<token>`). 본 저장소는 비공개라 Pages를 못 쓴다. **공개 저장소를 만들기 전에 사용자 확인을 받는다.** 거절하면 Vercel 정적 배포로 바꾸고 `UPLOAD_PAGE_URL`만 바꾼다. `scripts/publish_upload_page.sh`가 `web/upload/index.html`, `web/upload/page.js`, `supabase/functions/_shared/exif.js`를 그 저장소에 복사해 푸시한다.
- `upload` HTTP 계약 (키 없음, CORS `*`):
  - `GET /functions/v1/upload?t=<token>` → 302 `Location: <UPLOAD_PAGE_URL>?t=<token>`(토큰 형식이 틀려도 리다이렉트하고 페이지가 안내한다). QR 주소는 2a 그대로다.
  - `GET /functions/v1/upload?t=<token>&info=1` → 200 `{"dest_name","started_on":"2026-10-31","count":3,"max":200,"expires_at"}` / 404 `{"error":"unknown_token"}` / 410 `{"error":"expired"}`
  - `POST /functions/v1/upload?t=<token>` multipart: `photo`(JPEG ≤20MB, 긴 변 ≤2048), `thumb`(JPEG ≤1MB, 긴 변 ≤720), `w`, `h`, `taken_at`(ISO+오프셋, 선택), `taken_at_source`(`exif|file|none`, 선택)
    - `taken_at`이 없으면 서버가 `photo` 바이트에서 `exifDateTimeOriginal`로 읽는다(curl 시연 경로). 그것도 없으면 `none`.
    - 검사: JPEG 매직 `FF D8 FF`(아니면 415 `not_jpeg`), 크기(413 `too_large`), 토큰 없음 404, 만료 410, 여행당 200장(429 `photo_limit`), 토큰당 1분에 30장(429 `rate_limited`).
    - 저장: `trip-photos/<token>/<uuid>.jpg`, `<uuid>_t.jpg`(contentType `image/jpeg`, upsert false), 그다음 `photos` 행. 행 삽입이 실패하면 올린 두 파일을 지운다.
    - 201 `{"id","count"}`
- `photos` HTTP 계약: `GET /functions/v1/photos?t=<token>` 헤더 `apikey`, `x-yeoro-device: <43자 기기 비밀>` → 200 `{"expires_at","photos":[{"id","taken_at":"…"|null,"taken_at_source","uploaded_at","w","h","thumb_url","url"}]}`(서명 URL 1시간, `createSignedUrls`로 한 번에), 403 `device_mismatch`, 404 `unknown_token`. 업로드 토큰만으로는 목록을 볼 수 없다.

**동작 (업로드 페이지, 시안 장면 7 "엄마의 폰"):**
- 모바일 우선 한 화면. 남색 `#0E1220` 바탕, 앰버 `#FFB46A` 버튼, 시스템 글꼴(외부 CDN 없음). 제목 "{목적지} 사진 올리기", 날짜 줄 "2026년 10월 31일 여행 · 지금까지 3장".
- 큰 버튼 두 개(높이 64px 이상): [사진 고르기](`<input type=file accept="image/*" multiple>`)와 [여로 앨범에 올리기]. 안내 "사진 고르기는 나중에 해도 돼요. 이 주소는 30일 동안 열려 있어요." "얼굴 인식 없이 찍은 시각으로만 정리해요."
- 사진마다: 원본 바이트로 `exifDateTimeOriginal` → 있으면 `exif`, 없으면 `file.lastModified`로 `file`. 그다음 `createImageBitmap` → canvas로 2048·720 두 장을 JPEG(품질 0.85·0.8)로. 한 장씩 순서대로 올리고 진행 막대("4장 중 2장"). 실패한 사진은 "이 사진은 열 수 없어요"로 표시하고 계속.
- 끝나면 "4장 모두 보냈어요. 차가 앨범의 제자리에 넣을게요." 만료(410)·없는 토큰(404)은 각각 "이 QR은 기간(30일)이 지났어요" / "이 QR은 여로에 등록되지 않았어요. 차의 카드에 있는 QR을 다시 찍어 주세요."
- `scripts/exif_stamp.py <in.jpg> <out.jpg> "2026:10:31 13:30:00" [+09:00]`: 표준 라이브러리만으로 APP1 EXIF(IFD0 → ExifIFD에 0x9003, 0x9011)를 넣는다. 기존 APP1 EXIF는 바꿔 끼운다. 픽스처도 이것으로 만든다.

- [ ] TDD: `exif.test.ts` — 픽스처 3개(EXIF 있음 → "2026-10-31T13:30:00+09:00", 오프셋 +00:00 그대로, 없음 → null), 잘린 버퍼 null, Motorola(MM) 바이트 순서, `fitWithin(4032, 3024, 2048)` = (2048, 1536), 작은 사진은 그대로
- [ ] `upload`·`photos` 구현 → 배포 → 사용자 확인 후 Pages 저장소 생성·`publish_upload_page.sh`
- [ ] `backend_smoke.sh`에 더하기: 등록 → info 200 → 픽스처 POST 201(서버가 EXIF 읽음) → PNG 415 → 기기 비밀 없이 목록 403 → 목록 200(서명 URL을 curl로 받아 JPEG 매직 확인) → 삭제 → info 404
- [ ] 데스크톱 Chrome의 모바일 화면으로 페이지에서 사진 2장 올리기 → 목록에 `taken_at_source: "exif"` 확인, 스크린샷 `docs/screens/2b/upload-page.png`. 실제 휴대폰 스캔은 사용자에게 한 번 부탁한다(보고서에 남김)
- [ ] 커밋: `사진 올리기: upload·photos 함수, 휴대폰 업로드 페이지(EXIF 촬영 시각·축소), 스모크 확장`

### Task 3: 공공데이터 (`outside` 함수)

**Files:** Create `supabase/functions/_shared/{datago.ts,datago.test.ts,place.ts,place.test.ts}`, `supabase/functions/outside/index.ts`. Modify `scripts/backend_smoke.sh`.

**Interfaces (Produces):**
- `_shared/datago.ts`:
  - `items(body: unknown): any[]` — 배열·객체·`""`·없음을 배열로.
  - `toGrid(lat, lon): { nx: number, ny: number }` — 조사 결과의 LCC 상수.
  - `kst(d: Date): { date: "YYYYMMDD", hhmm: "HHMM", hour: number }`
  - `ncstBase(t: Date, now: Date): { date, time } | null` — t의 KST 정시. 그 정시 + 40분이 아직 안 지났으면 한 시간 앞. now − base > 23시간이면 null.
  - `vilageBase(t: Date, now: Date): { date, time } | null` — 발표 시각(02,05,…,23시) 중 `min(t, now − 10분)` 이하의 가장 늦은 것. 그 base_date가 KST 그제보다 이르면 null. t가 now + 4일 넘으면 null.
  - `skyText(code): "맑음" | "구름많음" | "흐림" | null`, `ptyText(code, ncst: boolean): string | null`(0이면 null)
  - `hhmm(s: "1736  "): "17:36"`, `homepageHref(s): string | null`(`<a href="…">`면 href, 맨 URL이면 그대로, http/https만)
- `_shared/place.ts`:
  - `placeKindOf(items: {contenttypeid, cat3, dist}[], radiusM = 500): "TOURIST" | "LODGING" | "CAMPING" | "OTHER"` — 반경 안 항목 중 `cat3 == "A03021700"` → CAMPING, 12·14·15·28 → TOURIST, 32 → LODGING, 우선순위 CAMPING > TOURIST > LODGING.
  - `pickFestival(items, date: "YYYYMMDD", lat, lon, maxKm = 15)` → 진행 중(start ≤ date ≤ end)이고 가장 가까운 것 `{ name, start, end, dayIndex, distanceKm, contentId }` 또는 null. `dayIndex = date − start + 1`.
  - `haversineKm(a, b)`
- `outside` HTTP 계약 (헤더 `apikey`):
  - `GET /functions/v1/outside?lat=37.8055&lon=128.9087&t=<epoch ms>&parts=weather,sun,place,festival,holiday`(parts 생략 = 전부)
  - 200 (부분 실패는 그 칸만 null, 이유는 `errors`):
```json
{ "at": "2026-10-24T13:20:00+09:00",
  "weather": { "tempC": 18.0, "sky": "맑음", "precip": null, "kind": "observed", "source": "기상청" },
  "sun": { "sunrise": "06:41", "sunset": "17:36", "source": "천문연" },
  "place": { "kind": "TOURIST", "name": "경포호수광장", "contentId": "3454461", "homepage": null, "source": "TourAPI" },
  "festival": { "name": "강릉커피축제", "start": "2026-10-21", "end": "2026-10-25", "dayIndex": 4,
                "distanceKm": 3.6, "homepage": "https://www.coffeefestival.net/", "source": "TourAPI" },
  "holiday": null,
  "errors": [] }
```
  - 400 lat/lon 범위(위도 33~39, 경도 124~132) 밖이나 t 없음. 401 키 없음.
  - `GET /functions/v1/outside/holidays?year=2026` → 200 `{"year":2026,"holidays":[{"date":"2026-10-03","name":"개천절"},…]}`(isHoliday "Y"만, numOfRows 50, 월 생략)

**동작:**
- 날씨: `ncstBase`가 있으면 `getUltraSrtNcst`(T1H, PTY, kind `observed`), 하늘은 `vilageBase`의 `getVilageFcst`에서 t 시각 `SKY`. 실황이 없으면 단기예보의 TMP·SKY·PTY(kind `forecast`). 둘 다 없으면 `weather: null`, `errors: ["weather:out_of_window"]`.
- 장소: `locationBasedList2` radius 1000, numOfRows 30 → `placeKindOf`(500m). name은 500m 안 첫 관광지(12)의 title, homepage는 그 contentId의 `detailCommon2` → `homepageHref`.
- 축제: 위 결과 첫 항목의 `lDongRegnCd/lDongSignguCd`로 `searchFestival2`(eventStartDate = t의 날짜, numOfRows 50) → `pickFestival`. 고른 축제의 homepage도 `detailCommon2`로.
- 특일: t의 연월로 `getRestDeInfo` → 그날이 휴일이면 `{"name"}`.
- 위쪽 호출은 `Promise.allSettled`로 동시에, 각각 `AbortSignal.timeout(6000)`. 함수 전체 10초 안.

- [ ] TDD: `datago.test.ts` — 격자 확인값 4개(조사 결과), items 세 모양, `ncstBase`(now 16:04·t 15:20 → 1500 / now 16:04·t 15:50 → 1500 / now 16:20·t 16:05 → 16:40 전이라 1500 / t가 now보다 25시간 전 → null), `vilageBase`(t 그제 06:00 → 그제 0500, t 사흘 전 → null, t 내일 13:00·now 16:04 → 오늘 1400), sky·pty 코드, `hhmm("1736  ")`, homepage 세 모양. `place.test.ts` — 경포 응답 축약 픽스처로 TOURIST, 숙박만 → LODGING, 캠핑, 커피축제 dayIndex 4(2026-10-24), 끝난 축제 제외
- [ ] 구현 → 배포 → 스모크에 더하기: 경포 지금 시각 → weather.kind observed·sun·place 200, 2026-10-24 13:20 → festival "강릉커피축제"·weather null(사흘 지남), holidays 2026 → 22건 중 "2026-10-09 한글날", 범위 밖 400
- [ ] 커밋: `공공데이터: outside 함수(기상청 실황·예보, 천문연 일몰·특일, TourAPI 장소·축제)`

### Task 4: 앱 클라우드 기반 (Room v4, API 클라이언트, 동기화, 동의·개인정보)

**Files:** Create `cloud/{CloudConfig.kt,CloudApi.kt,HttpCloudApi.kt,CloudJson.kt,DeviceSecret.kt,CloudSync.kt,PhotoFiles.kt}`. Modify `app/build.gradle.kts`(BuildConfig), `AndroidManifest.xml`(`INTERNET`, `ACCESS_NETWORK_STATE`), `data/YeoroDb.kt`(v4), `data/ShareTokens.kt`, `data/TripRepository.kt`, `service/ArrivalPolicy.kt`(`UPLOAD_BASE_URL`을 CloudConfig에서), `service/RecorderService.kt`, `ui/home/HomeViewModel.kt`, `ui/settings/SettingsScreen.kt`(동의 화면·설정), `app/src/debug/.../DebugReceiver.kt`(`sync_now`), README(개인정보·설정법). Test `app/src/test/java/ai/yeoro/cloud/{CloudJsonTest,CloudSyncTest}.kt`, `data/MigrationTest`(3→4), `data/YeoroDaoV4Test.kt`

**Interfaces (Produces):**
- `local.properties`: `yeoro.supabase.url=https://dghpdvpkgvamsaidndqa.supabase.co`, `yeoro.supabase.key=sb_publishable_…`. `buildConfigField("String", "SUPABASE_URL", …)`, `SUPABASE_KEY`(없으면 `""`).
- `object CloudConfig { val baseUrl: String; val key: String; val enabled: Boolean /* key 있음 */; fun fn(name: String) = "$baseUrl/functions/v1/$name" }`. `UPLOAD_BASE_URL = CloudConfig.fn("upload") + "?t="`(값은 2a와 같다).
- Room v4 (Migration 3→4, 스키마 4.json):
```kotlin
@Entity(tableName = "trip_outside", foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = CASCADE)])
data class TripOutsideEntity(@PrimaryKey val tripId: Long, val fetchedMs: Long, val json: String)   // {"arrival":{…},"departure":{…}}
@Entity(tableName = "trip_photos", indices = [Index("tripId")], foreignKeys = [/* trips cascade */])
data class TripPhotoEntity(@PrimaryKey val id: String, val tripId: Long, val takenAtMs: Long?, val takenAtSource: String,
                           val uploadedMs: Long, val w: Int, val h: Int, val thumbFile: String?, val fullFile: String?, val seen: Boolean = false)
@Entity(tableName = "pending_deletes") data class PendingDeleteEntity(@PrimaryKey val token: String, val queuedMs: Long)
@Entity(tableName = "trip_diary", foreignKeys = [/* trips cascade */])   // Task 11이 쓴다. 여기서는 표만
data class TripDiaryEntity(@PrimaryKey val tripId: Long, val factsHash: String, val json: String, val createdMs: Long)
```
  DAO 추가: `upsertOutside`, `outside(tripId): Flow<TripOutsideEntity?>`, `outsideNow(tripId)`, `upsertPhotos(list)`(OnConflict REPLACE, 단 seen·파일 경로는 기존 값 유지하도록 Repository에서 병합), `photos(tripId): Flow<List<TripPhotoEntity>>`, `unseenPhotoCounts(): Flow<List<TripCount>>`(`data class TripCount(val tripId: Long, val n: Int)`), `markPhotosSeen(tripId)`, `pendingDeletes()`, `insertPendingDelete`, `deletePending(token)`, `diary(tripId)`, `upsertDiary`.
- `ShareTokens` 추가: `tokenOrNull(tripId): String?`, `isRegistered(tripId)`, `markRegistered(tripId)`, `all(): Map<Long, String>`. `forget`는 등록 표시도 지운다.
- `class DeviceSecret(prefs) { val value: String }` — prefs `yeoro_cloud`의 `device`, 없으면 SecureRandom 32바이트 base64url(43자).
```kotlin
sealed interface CloudResult<out T> { data class Ok<T>(val value: T) : CloudResult<T>; data class Fail(val status: Int?, val code: String) : CloudResult<Nothing> }
interface CloudApi {
    suspend fun registerTrip(token: String, startedAtMs: Long, destName: String?): CloudResult<Unit>
    suspend fun deleteTrip(token: String): CloudResult<Unit>
    suspend fun photos(token: String): CloudResult<List<RemotePhoto>>
    suspend fun download(url: String, dest: java.io.File): CloudResult<Unit>
    suspend fun outside(lat: Double, lon: Double, atMs: Long, parts: Set<String> = emptySet()): CloudResult<String>   // 원문 JSON (캐시에 그대로)
    suspend fun holidays(year: Int): CloudResult<List<java.time.LocalDate>>
    suspend fun diary(requestJson: String): CloudResult<String>
}
data class RemotePhoto(val id: String, val takenAtMs: Long?, val takenAtSource: String, val uploadedMs: Long, val w: Int, val h: Int, val thumbUrl: String, val url: String)
```
  `HttpCloudApi(config, deviceSecret)`: `Dispatchers.IO`, 연결 10초·읽기 20초, 헤더 `apikey`, 기기 비밀은 `photos`·`trip`에만. `CloudJson`: `parseRemotePhotos(json)`, `parseHolidays(json)`, 등록 body 만들기(시각은 기기 시간대 오프셋 ISO).
- `class CloudSync(api, dao, tokens, files: PhotoFiles, consent: () -> Boolean, now: () -> Long, enabled: Boolean = CloudConfig.enabled)`:
  - `suspend fun ensureRegistered(tripId: Long): Boolean` — 동의·enabled가 아니면 false. 이미 등록이면 true. 아니면 trip의 startMs·destination으로 `registerTrip`(201/200 → `markRegistered`).
  - `suspend fun deleteTrip(tripId: Long)` — 등록된 토큰이면 `pending_deletes`에 넣고, Room 삭제(cascade), `files.deleteTrip(tripId)`, `tokens.forget`, `OpenedAlbums.forget`. 네트워크가 되면 바로 처리.
  - `suspend fun syncOnce(reason: String): SyncReport` — 순서: 삭제 큐(204·404 → 큐에서 뺌) → 미등록이지만 QR을 띄운 여행 등록 → (Task 6) 공공데이터 → (Task 7) 사진. 각 단계는 실패해도 다음 단계를 한다. 동의·enabled가 아니면 즉시 `SyncReport()`.
  - `data class SyncReport(val deleted: Int = 0, val registered: Int = 0, val outside: Int = 0, val newPhotos: Int = 0)`
  - `fun shouldSync(consented: Boolean, gear: Gear, enabled: Boolean): Boolean = consented && enabled && gear == Gear.P` (순수, 테스트)

**동작:**
- RecorderService가 `CloudSync`를 하나 만든다. 부르는 때: 도착 카드를 띄울 때(`ensureRegistered` → `syncOnce("arrival")`), 여행이 닫힐 때(`syncOnce("close")`), P로 바뀔 때와 P인 동안 10분마다(`syncOnce("parked")`). D에서는 부르지 않는다. 디버그 `--ez sync_now true` → `syncOnce("debug")`. 디버그 빌드는 결과를 `cloud sync reason=… deleted=… registered=… outside=… photos=…`로 로그에 남기고, 도착 때 `upload_url=<주소>`를 남긴다(시연 스크립트용).
- 홈의 [지우기]와 이후 모든 여행 삭제는 `CloudSync.deleteTrip`을 거친다(`TripRepository.deleteTrip` 직접 호출을 없앤다).
- 동의 화면(`ConsentScreen`)과 설정에 "차 밖으로 나가는 것" 목록을 같은 데이터로 보인다:
  - "사진 올리기를 위해 여행 등록: 공유 토큰, 출발 시각, 목적지 이름. 지나온 길(위치 기록)은 보내지 않아요."
  - "사진: 가족이 QR로 직접 올린 사진만 받아요. 서버에는 30일(+정리 7일)까지만 두어요."
  - "날씨·일몰·관광 정보: 목적지 좌표와 날짜로 기상청·천문연·한국관광공사에 물어봐요."
  - "AI 기록문(켤 때만): 기록문의 사실 문장만, 가족 이름은 빼고 보내요." (Task 11 전까지는 이 줄을 숨긴다)
  - "여행을 지우면 서버의 사진과 등록도 함께 지워요. 기록 동의를 끄면 아무것도 보내지 않아요."
- README 개인정보 절을 위 목록으로 바꾸고 "모든 기록은 차 안에만"을 고친다. 실행 절에 `local.properties` 두 줄 설정법을 넣는다(키 값은 적지 않는다).

- [ ] TDD: `CloudSyncTest`(가짜 CloudApi, Robolectric 인메모리 DB) — 동의 꺼짐이면 api 호출 0회(등록·삭제·sync 모두), enabled false도 0회, 등록 201 → 표시, 409 → 표시 안 함, 삭제는 오프라인(Fail)이면 큐에 남고 다음 sync에 204로 비워짐, 삭제 후 Room·파일·토큰 없음, `shouldSync` 표. `CloudJsonTest` — 사진 목록(taken_at null 포함), 특일. `MigrationTest` 3→4(기존 행 보존, 새 표 존재). DAO v4(사진 cascade, unseen 개수, markSeen)
- [ ] 구현 → `./gradlew :app:testDebugUnitTest` → 에뮬레이터: `local.properties`에 키를 넣고 시연(`DEMO_UNTIL=arrival`) → 로그 `registered=1`, `backend_smoke`와 같은 방법으로 토큰 등록 여부를 info 200으로 확인 → 동의 끄고 sync_now → `cloud sync` 로그 없음(호출 없음) → 동의 화면·설정 스크린샷 `docs/screens/2b/consent.png`, `settings-cloud.png`
- [ ] 커밋: `앱 클라우드 기반: Room v4, Supabase 클라이언트·동기화(동의 게이트, 삭제 큐), 차 밖으로 나가는 것 안내`

### Task 5: 왕복 여행 — 돌아오는 길을 도착 장에서 떼기

**Files:** Create `ui/album/TripLegs.kt`. Modify `ui/album/Chapters.kt`(ChapterKind.RETURN, buildChapters, chapterDiary), `ui/album/TripAlbumViewModel.kt`, `ui/album/RouteMap.kt`, `ui/album/TripAlbumScreen.kt`(RETURN 장 표시). Test `app/src/test/java/ai/yeoro/ui/album/TripLegsTest.kt`, `ChaptersTest.kt` 추가분

**Interfaces (Produces):**
```kotlin
/** samples는 시간순. arriveIdx: 목적지에 닿은 샘플, leaveIdx: 목적지 곁을 마지막으로 떠난 샘플(왕복일 때만), returnsHome: 집으로 돌아온 여행 */
data class TripLegs(val arriveIdx: Int, val leaveIdx: Int?, val returnsHome: Boolean)
fun tripLegs(samples: List<LocationSampleEntity>, dest: Pair<Double, Double>?, home: Pair<Double, Double>?): TripLegs?   // 샘플 없으면 null
enum class ChapterKind { DEPARTURE, STOP, ARRIVAL, RETURN }
```
- AlbumData 추가: `returnRoute: List<Pair<Double, Double>>`(왕복이 아니면 빈 목록), `route`는 출발~떠난 지점까지.

**동작 (모두 순수 함수, 반경 상수는 기존 `ARRIVAL_RADIUS_M` 300m를 쓴다):**
- arriveIdx: 목적지 좌표가 있으면 300m 안에 처음 든 샘플. 없거나 한 번도 안 들었으면 집에서 가장 먼 샘플(같으면 앞), 집도 모르면 마지막 샘플.
- returnsHome: 집을 알고, 마지막 샘플이 집 1km 안이고, 도착 지점이 집에서 2km 이상이고, arriveIdx가 마지막이 아닐 때.
- leaveIdx(returnsHome일 때만): arriveIdx 이후 도착 지점 300m 안에 있던 마지막 샘플.
- buildChapters: 도착 시각 = samples[arriveIdx]. 도착 전 STOP → STOP 장. 도착~떠남 사이 STOP은 도착 장에 합친다. 도착 장의 머문 시간 = 떠남 − 도착(왕복), 아니면 2a 규칙. 떠남 이후의 모든 순간(정차 포함)은 RETURN 장 "돌아오는 길" 하나에 들어간다(시작 = 떠남 시각, 좌표 = 집).
- RETURN 기록문: "17시 05분, 집으로 출발했습니다." + 순간 문장 + "19시 40분, 집에 도착했습니다."(마지막 샘플 시각).
- 앨범: 경로는 갈 길만 앰버로 그리고, 돌아오는 길은 같은 색 alpha 0.25, 2dp, 글로우 없음, 그리기 애니메이션 없음. 통계 km는 전체(갈 길+올 길), 제목의 "{목적지}까지 Nkm"와 "출발부터 도착까지" 시간은 갈 길만.
- 시연(`close_trip`은 경포해변에서 닫음)은 returnsHome false라 2a와 같아야 한다.

- [ ] TDD: `TripLegsTest` — 서울→강릉→서울(도착 idx·떠남 idx·returnsHome true), 편도(false), 목적지 없음 + 집 있음(가장 먼 점), 집 모름(마지막 점), 목적지 근처 두 번 들락날락(마지막 근처 샘플이 떠남), 도착 지점이 집 1.5km(false). `ChaptersTest` — 왕복이면 RETURN 장 1개·귀갓길 휴게소 STOP은 RETURN의 순간으로·도착 장 머문 시간, 기록문 첫·끝 문장, 2a 기존 테스트 전부 통과
- [ ] 구현 → 디버그 `seed_trip`에 `--es seed_trip round`(강릉 왕복, 17:05 출발 19:40 귀가, 귀갓길 정차 1회) 추가 → 앨범 공개·RETURN 장 스크린샷 `docs/screens/2b/round-trip-*.png` → 2a 시연 스크립트 회귀(장 3개 그대로)
- [ ] 커밋: `왕복 여행: 돌아오는 길을 한 장으로, 경로에는 흐린 귀갓길 선`

### Task 6: 그날의 바깥 — 앨범 칩·기록문·판단 엔진

**Files:** Create `cloud/Outside.kt`, `cloud/Holidays.kt`. Modify `cloud/CloudSync.kt`(syncOutside), `service/RecorderService.kt`(장소 분류·특일), `ui/album/TripAlbumViewModel.kt`, `ui/album/TripAlbumScreen.kt`(칩), `ui/album/Chapters.kt`(chapterDiary에 바깥 문장), `app/src/debug/.../DebugReceiver.kt`(`outside_time real|demo`). Test `app/src/test/java/ai/yeoro/cloud/{OutsideTest,HolidaysTest}.kt`, `CloudSyncTest` 추가분

**Interfaces (Produces):**
```kotlin
data class Weather(val tempC: Double?, val sky: String?, val precip: String?, val observed: Boolean)
data class SunTimes(val sunrise: String, val sunset: String)
data class PlaceInfo(val kind: PlaceKind, val name: String?, val homepage: String?)
data class Festival(val name: String, val start: LocalDate, val end: LocalDate, val dayIndex: Int, val distanceKm: Double, val homepage: String?)
data class OutsideInfo(val atMs: Long, val weather: Weather?, val sun: SunTimes?, val place: PlaceInfo?, val festival: Festival?, val holiday: String?)
data class TripOutside(val arrival: OutsideInfo?, val departure: OutsideInfo?)
fun parseOutside(json: String): OutsideInfo?            // 모르는 필드·null 허용, 깨지면 null
fun parseTripOutside(json: String): TripOutside
fun tripOutsideJson(arrival: String?, departure: String?): String
data class OutsideChipData(val text: String, val source: String, val kind: OutsideChipKind)   // WEATHER, SUN, FESTIVAL, HOLIDAY
fun outsideChips(o: TripOutside, kind: ChapterKind): List<OutsideChipData>
fun outsideSentences(o: OutsideInfo): List<String>
class Holidays(prefs, api: CloudApi?, consent: () -> Boolean) { fun isHoliday(d: LocalDate): Boolean; suspend fun refresh(year: Int) }
```
- `cloud/Outside.kt`에 `fun koOrdinalDay(n: Int): String`("첫째 날", "둘째 날", … "열째 날", 그 뒤 "11일째").

**동작:**
- **칩 문구(실제 데이터):** 날씨 "18℃ 맑음"(비가 오면 "16℃ 비"), 출처 "기상청 실황" 또는 "기상청 예보". 일몰 "일몰 17:36" "천문연". 축제 "강릉커피축제 넷째 날" "TourAPI". 휴일 "한글날" "천문연". DEPARTURE 장은 departure 날씨만, ARRIVAL 장과 공개 화면은 arrival의 날씨·일몰·축제·휴일, STOP·RETURN은 없음.
- **"예시" 칩:** 그 여행의 trip_outside가 없을 때만 2a의 예시 칩과 "예시" 꼬리표를 보인다. 하나라도 실제 값이 있으면 예시는 하나도 섞지 않는다.
- **기록문 문장(ARRIVAL 장 끝에 이어 붙임):** 실황 "도착할 무렵 기온은 18℃, 하늘은 맑았습니다." / 비 "도착할 무렵 비가 내리고 있었습니다." / 예보만 "기상청 예보로는 맑음, 18℃였습니다." / "해는 17시 36분에 졌습니다." / "가까운 곳에서 강릉커피축제가 넷째 날을 맞고 있었습니다." / "이날은 한글날이었습니다." 값이 없는 문장은 빠진다.
- **가져오는 때(CloudSync.syncOutside):** 도착 카드 직후 arrival(목적지 좌표, 도착 시각)을, 여행을 닫을 때 departure(첫 샘플, 출발 시각)와 비어 있는 arrival을 받는다. 그 뒤로도 sync마다 "닫힌 지 3일 안이고 trip_outside가 없거나 arrival이 null"인 여행만 다시 시도한다(날씨는 이 창이 지나면 영영 못 받는다). 좌표를 모르면 건너뛴다.
- **판단 엔진 — 장소:** RecorderService가 목적지를 받으면(P단·동의일 때) `outside(parts=place)`로 kind를 묻고, 좌표를 소수 셋째 자리로 반올림한 키로 prefs `yeoro_places`에 30일 캐시한다. 답이 오고 같은 목적지가 아직이면 `Destination(kind = …)`로 `DestinationSet`을 다시 보낸다. 오프라인·실패면 `PlaceKind.OTHER`(2a와 같음). 점수표의 "목적지 관광·숙박지 +20 (TourAPI)"가 실제로 켜진다.
- **판단 엔진 — 특일:** `Holidays`가 연도별 날짜 목록을 prefs `yeoro_holidays`에 저장한다(`y_2026` → "2026-01-01,…"). 서비스 시작과 해가 바뀔 때 동의·enabled면 `refresh`. tick의 `CalendarInfo(holiday = holidays.isHoliday(오늘))`. 캐시가 없으면 false(주말 판단만, 2a와 같음).
- **시연 시계:** 디버그 빌드에서 시계 보정이 있으면 날씨를 묻는 시각을 실제 지금으로 바꾸는 `--es outside_time real`을 둔다(기본 demo). 시연 날짜(다가오는 토요일)는 기상청 창 밖일 수 있어서다. README에 "시연의 날씨는 촬영한 날 경포해변의 실제 날씨"라고 밝힌다.

- [ ] TDD: `OutsideTest` — Task 3 응답 예시 JSON 파싱, null 칸, 칩(실황·예보 출처 문구, 장 종류별), "예시" 판단(trip_outside 없음 → 예시, 일몰만 있음 → 실제 칩만), 문장 다섯 가지, `koOrdinalDay`. `HolidaysTest` — 캐시 저장·읽기, 캐시 없음 false, 동의 꺼짐이면 refresh가 api를 안 부름. `CloudSyncTest` — 3일 창 안·밖 재시도
- [ ] 구현 → 에뮬레이터 시연(`--es outside_time real`, 네트워크 켬) → 도착 뒤 로그 `outside=1` → 앨범 공개·도착 장 칩과 기록문 스크린샷 `docs/screens/2b/outside-*.png`. 네트워크 끄고(`adb shell svc wifi disable; svc data disable`) 2a 시연 통과 + 예시 칩 확인
- [ ] 커밋: `그날의 바깥: 공공데이터 칩·기록문, 목적지 분류(TourAPI)와 공휴일(특일)을 판단 엔진에`

### Task 7: 가족 사진 — 장마다 제자리에, "사진 N장" 카드

**Files:** Create `ui/album/PhotoMatch.kt`, `ui/common/PhotoImage.kt`, `ui/live/Promo.kt`. Modify `cloud/CloudSync.kt`(syncPhotos), `cloud/PhotoFiles.kt`, `ui/album/TripAlbumViewModel.kt`, `ui/album/TripAlbumScreen.kt`(콜라주·표지·공개 화면 사진 정리 줄), `ui/live/LiveScreen.kt`, `ui/live/LiveViewModel.kt`. Test `app/src/test/java/ai/yeoro/ui/album/PhotoMatchTest.kt`, `ui/live/PromoTest.kt`, `CloudSyncTest` 추가분

**Interfaces (Produces):**
```kotlin
data class AlbumPhoto(val id: String, val takenAtMs: Long?, val w: Int, val h: Int, val thumbFile: String?, val fullFile: String?)
const val CLOCK_SKEW_MS = 10 * 60_000L
/** 사진 → 장 번호. 결과는 장 번호마다 촬영 시각순. */
fun assignPhotos(chapters: List<Chapter>, photos: List<AlbumPhoto>, skewMs: Long = CLOCK_SKEW_MS): Map<Int, List<AlbumPhoto>>
fun trustedTakenAt(takenAtMs: Long?, source: String, uploadedMs: Long): Long?
fun coverPhoto(chapters: List<Chapter>, byChapter: Map<Int, List<AlbumPhoto>>): AlbumPhoto?
// ui/live/Promo.kt
sealed interface Promo { data class Photos(val tripId: Long, val count: Int, val place: String?) : Promo; data class Memory(val tripId: Long, val title: String, val sub: String) : Promo }
fun livePromo(mode: LiveMode, gear: Gear, photos: Promo.Photos?, memory: Promo.Memory?): Promo?
@Composable fun PhotoImage(file: String?, modifier: Modifier, maxPx: Int, placeholder: @Composable () -> Unit)
```
- AlbumData 추가: `photos: Map<Int, List<AlbumPhoto>>`, `cover: AlbumPhoto?`, `photoCount: Int`.

**동작:**
- **이미지 불러오기 결정: Coil을 쓰지 않는다.** 사진은 한 번 내려받아 `filesDir/photos/<tripId>/<id>_t.jpg`(썸네일 720px)에 영구 저장해야 오프라인 앨범이 된다(캐시 퇴출이 있으면 안 됨). 그래서 `PhotoFiles`가 내려받고, `PhotoImage`가 `BitmapFactory`(`inSampleSize`로 maxPx 근처, `Dispatchers.IO`) + 프로세스 `LruCache`(maxMemory/8)로 그린다. 의존성 0, 코드 60줄 안팎.
- **CloudSync.syncPhotos:** 등록된 토큰 중 출발 30일 안인 여행마다 `photos` → 새 id는 `TripPhotoEntity`로 넣고 썸네일을 내려받는다. 표지로 쓸 사진(`coverPhoto`)만 원본(2048)도 받는다. 받은 개수를 SyncReport.newPhotos에. 다운로드 실패한 사진은 thumbFile null로 두고 다음 sync에 다시.
- **trustedTakenAt:** source가 `exif`면 그대로. `file`이고 |taken − uploaded| < 5분이면 null(iOS는 고를 때의 시각을 줌). `none`이면 null.
- **assignPhotos(순수):** 장 i의 창 = [startMs − skew, endMs + skew), 단 DEPARTURE의 끝은 다음 장 시작, ARRIVAL·RETURN의 끝은 다음 장 시작 또는 +∞. 창이 여럿 맞으면 원래 창(skew 없이)에 드는 장, 그래도 여럿이면 시작이 늦은 장. 어느 창에도 없으면 창까지 시간 거리가 가장 가까운 장. 시각을 모르는 사진은 ARRIVAL 장.
- **콜라주:** 장에 사진이 k장이면 min(k, 5)칸에 실제 사진(2a의 기울기·깊이·패럴랙스 그대로), 0장이면 2a 일러스트 자리. **표지:** `coverPhoto`(도착 장의 가로 사진 중 가장 큰 것, 없으면 첫 사진)를 꽉 차게 + 아래쪽 남색 그라디언트 덧칠, 없으면 2a 그라디언트. 메타 줄에 "사진 N장".
- **공개 화면 "사진 정리" 줄(시안 장면 7):** 사진이 있으면 장 이름마다 "N장" 칸과 썸네일 더미. 모션 허용 시 썸네일이 110ms 간격으로 칸에 쌓이고, 아니면 즉시.
- **지금 화면 카드:** `unseenPhotoCounts`가 있으면 `Promo.Photos`. 제목 "가족이 사진 4장을 보냈어요", 부제 "{목적지} 앨범의 제자리에 넣었어요", 버튼 [앨범 보기] → 그 여행 앨범(열면 `markPhotosSeen`). 보이는 곳: DAILY·ALBUM_READY, 그리고 RECORDING이면서 기어 P일 때(정적, 모션 없음). ASK·ARRIVAL·OFF에는 없음. 우선순위 Photos > Memory(Task 9).

- [ ] TDD: `PhotoMatchTest` — 휴게소 창 안, 창 사이 틈은 가까운 장, 경계 ±10분(출발 2분 전 사진은 출발 장), 겹칠 때 원래 창 우선, 시각 없음 → 도착 장, RETURN 장 있는 왕복, `trustedTakenAt` 세 경우, `coverPhoto`. `PromoTest` — 모드×기어 표, Photos가 Memory보다 먼저. `CloudSyncTest` — 새 사진만 넣기, seen 유지, 다운로드 실패 재시도
- [ ] 구현 → 에뮬레이터: 시연을 도착에서 멈추고(`DEMO_UNTIL=arrival`), 로그의 `upload_url`로 `backend_smoke`식 curl 업로드 2장 → `sync_now` → 카드 스크린샷 `docs/screens/2b/photos-card.png` → 여행 닫고 앨범 콜라주·표지·사진 정리 줄 스크린샷
- [ ] 커밋: `가족 사진: 촬영 시각으로 장에 넣기, 콜라주·표지 실제 사진, 지금 화면 "사진 N장" 카드`

### Task 8: 그때 vs 지금

**Files:** Create `ui/album/ThenNow.kt`. Modify `ui/album/TripAlbumViewModel.kt`, `ui/album/TripAlbumScreen.kt`(도착 장 뒤에 한 쪽), `YeoroApp.kt`(`ArrivalInfo.then`), `service/RecorderService.kt`(showArrival), `ui/live/ArrivalCard.kt`, `app/src/debug/.../SeedPrev.kt`, `DebugReceiver.kt`(`seed_prev`), `app/src/debug/assets/demo_prev.jpg`. Test `app/src/test/java/ai/yeoro/ui/album/ThenNowTest.kt`

**Interfaces (Produces):**
```kotlin
data class VisitSummary(val tripId: Long, val arriveMs: Long, val rained: Boolean, val tempC: Double?, val sky: String?, val seats: Int?, val photoFile: String?)
data class VisitSide(val tripId: Long, val dateText: String /* "2025.11.01" */, val fact: String /* "흐림 · 14℃ · 오후 2시 41분 도착" */, val photoFile: String?)
data class ThenNow(val then: VisitSide, val now: VisitSide, val heading: String, val comparisons: List<String>)
fun previousVisit(trips: List<TripEntity>, current: TripEntity, radiusM: Int = 500): TripEntity?
fun visitFact(v: VisitSummary, zone: ZoneId): String
fun comparisons(then: VisitSummary, now: VisitSummary, zone: ZoneId): List<String>
fun thenNowHeading(thenMs: Long, nowMs: Long, zone: ZoneId): String
// YeoroApp.kt
data class ArrivalInfo(…기존…, val then: VisitSide? = null)
```

**동작:**
- previousVisit: ALBUM_READY이고 목적지 좌표가 현재 목적지 500m 안이며 endMs < current.startMs인 여행 중 가장 늦은 것.
- visitFact: 날씨가 있으면 "흐림 · 14℃ · ", 이어서 "오후 2시 41분 도착". 날씨가 없으면 비가 왔으면 "비 온 날 · 오후 2시 41분 도착".
- comparisons(최대 3개, 이 순서): 도착 시각(하루 중 시각) 차가 10분 이상이면 "작년보다 1시간 21분 일찍 도착"(330~400일 차면 "작년", 아니면 "지난번") / 둘 다 비 "두 번 모두 비가 왔어요" / 둘 다 기온이 있고 차가 2℃ 이상 "4℃ 더 따뜻했어요"(또는 "쌀쌀했어요", 출처 기상청) / 인원이 다르면 "이번엔 한 명 더 함께"(줄면 "이번엔 한 명 적게").
- heading: 330~400일이면 "같은 곳, 일 년의 차이", 아니면 "지난번과 오늘".
- **앨범:** visit ≥ 2이면 도착 장 바로 다음 쪽에 "그때 vs 지금 · {목적지}" 쪽(장 트랙에 "그때 vs 지금" 점 추가). 두 사진 칸(그때: 그 여행의 표지 사진, 없으면 2a 장면 일러스트 / 지금: 이번 표지), 각 캡션 날짜·사실, 아래 비교 칩(실선, 출처 "운행 기록"·"와이퍼"·"좌석"·"기상청"). 두 칸이 250ms 간격으로 떠오른다(모션 허용 시).
- **도착 카드:** visit ≥ 2이면 단골 단계 막대 아래에 작게 "그때 2025.11.01 · 오후 2시 41분 도착" + 그때 사진 썸네일(없으면 일러스트). 정적.
- **디버그 `seed_prev`:** 강릉 경포해변 이전 방문을 "시연 토요일 − 364일"(같은 요일, 1년 전 이번 주)에 넣는다. 10:05 출발 → 14:41 도착, 경로 4점, RAIN_START/STOP, 좌석 3명, 사진 1장(`assets/demo_prev.jpg`를 filesDir로 복사해 `TripPhotoEntity`, id `seed-prev-1`, takenAt 14:50). 날씨는 넣지 않는다(가짜 기상청 값 금지). 다시 보내면 바꿔 넣는다. 시연 시계가 없으면 실제 오늘 기준.

- [ ] TDD: `ThenNowTest` — previousVisit(반경 안·밖, 이후 여행 제외, 가장 늦은 것), visitFact 두 형태, comparisons 네 가지와 최대 3개·순서, "작년"/"지난번", heading
- [ ] 구현 → `seed_prev` 후 시연 → 도착 카드 "두 번째 방문" + 그때 줄, 앨범 그때 vs 지금 쪽 스크린샷 `docs/screens/2b/then-now*.png`, D단 앨범 정적 요약 회귀
- [ ] 커밋: `그때 vs 지금: 두 번째 방문부터 앨범 한 쪽과 도착 카드에 지난 방문 비교`

### Task 9: 다시 가기와 1년 전 이번 주

**Files:** Create `ui/album/Again.kt`, `ui/live/Memory.kt`. Modify `ui/album/TripAlbumScreen.kt`(마지막 쪽), `ui/home/HomeScreen.kt`·`HomeViewModel.kt`(정기 방문의 [다시 가기]), `ui/live/Promo.kt`, `ui/live/LiveScreen.kt`, `ui/live/LiveViewModel.kt`, `ui/live/Qr.kt`(재사용). Test `app/src/test/java/ai/yeoro/ui/album/AgainTest.kt`, `ui/live/MemoryTest.kt`

**Interfaces (Produces):**
```kotlin
data class AgainLink(val label: String, val url: String)
fun naverMapUrl(name: String): String   // "https://map.naver.com/p/search/" + 공백은 %20인 UTF-8 인코딩
fun againLinks(destName: String?, outside: TripOutside?): List<AgainLink>   // [지도에서 보기] + (place.homepage ?: festival.homepage)이 http(s)면 [홈페이지], 최대 2개
fun memoryTrip(trips: List<TripEntity>, nowMs: Long, zone: ZoneId): TripEntity?
fun memoryDue(lastShownDate: String?, today: String): Boolean
fun memoryText(trip: TripEntity, photoCount: Int, zone: ZoneId): Promo.Memory
```

**동작:**
- **다시 가기 쪽(앨범 마지막 쪽, 시안 장면 11):** 제목 "{목적지}, 다시 갈까요?", 부제 "링크를 폰으로 넘겨요. 예약은 폰에서 해요." 링크마다 QR(240dp 이상, zxing 재사용)과 라벨("지도에서 보기 · 네이버 지도", "홈페이지 · 강릉커피축제"). 단골 단계 줄 "세 번째 방문이면 단골이에요"(regularStageText 재사용). 혜택은 2a처럼 "예시" 꼬리표. 차 안 예약은 없다. 목적지 이름이 없으면 이 쪽을 만들지 않는다.
- **홈 정기 방문:** 묶음 표지마다 [다시 가기](76dp). 누르면 같은 QR 패널을 대화상자로(가장 최근 여행의 trip_outside 사용). 주행 중에는 버튼이 비활성이고 "차를 세우면 열 수 있어요".
- **1년 전 이번 주(시안 장면 1):** memoryTrip = ALBUM_READY 여행 중 출발 날짜가 (오늘 − 1년) ± 3일 안인 것, 날짜 차가 작은 것, 같으면 늦게 끝난 것. 지금 화면 DAILY에서 기어 P일 때만 `Promo.Memory`: 제목 "1년 전 이번 주, 우리는 {목적지}에 있었어요"(목적지 없으면 "1년 전 이번 주의 여행"), 부제 "2025년 11월 1일 · 사진 1장", 썸네일(없으면 장면 일러스트), [앨범 보기]·[닫기]. 처음 보인 날을 prefs `memory_shown_date`에 적고, 같은 날 다시는 띄우지 않는다(하루 한 번). 금요일 저녁 귀가 규칙은 하지 않는다(로드맵에 적는다).

- [ ] TDD: `AgainTest` — 한글·공백 인코딩("강릉 경포해변" → `%EA%B0%95%EB%A6%89%20%EA%B2%BD%ED%8F%AC%ED%95%B4%EB%B3%80`), 홈페이지 없음·javascript: 거부·축제 홈페이지 대체. `MemoryTest` — ±3일 경계, 2년 전 제외, 확인 안 한 여행 제외, 가장 가까운 날, 윤년(2/29 → 2/28 기준), memoryDue
- [ ] 구현 → `seed_prev` 후 지금 화면 회상 카드, 앨범 다시 가기 쪽, 홈 대화상자 스크린샷 `docs/screens/2b/{memory,again,home-again}.png` → 실제 폰으로 QR 하나 찍어 네이버 지도 검색이 열리는지 사용자 확인 요청
- [ ] 커밋: `다시 가기(지도·홈페이지 QR 넘기기)와 1년 전 이번 주 회상 카드`

### Task 10: 통합 시연과 백엔드 스모크

**Files:** Modify `scripts/scenario_demo.sh`, `scripts/backend_smoke.sh`, README. Create `scripts/demo_upload.sh`, `docs/screens/2b/`. Use `scripts/demo_photos/*.jpg`(컨트롤러가 넣는 저작권 걱정 없는 풍경 사진)

**동작:**
- 먼저 `scripts/demo_photos/`에 JPEG가 4장 이상 있는지 확인한다. 없으면 멈추고 컨트롤러에게 요청한다(만들어 넣지 않는다). 긴 변이 2048을 넘으면 `sips -Z 2048`로 줄인 사본을 쓴다.
- `scripts/demo_upload.sh <upload_url> <YYYY-MM-DD>`: 사진마다 `exif_stamp.py`로 그날 08:50(출발), 10:05(휴게소), 13:30, 14:10(경포해변) 촬영 시각을 넣고, `sips -Z 720`로 썸네일을 만들어 `upload`에 curl POST(`taken_at`은 보내지 않아 서버가 EXIF를 읽게 한다). 201이 아니면 실패.
- `scenario_demo.sh` 흐름(2a 장면 번호 뒤에 2b 장면을 끼운다):
  0. 준비: 2a 준비 + `dbg --ez seed_prev true --es outside_time real` → 지금 화면 **회상 카드** 스크린샷(`2b/demo-00-memory.png`)
  1~7. 2a 그대로(출발 제안 점수에 "목적지 관광·숙박지 +20"이 보이면 그것도 기록)
  8. 도착 카드: "두 번째 방문" + 그때 줄 → 로그 `upload_url=`에서 주소를 읽어 `demo_upload.sh` → `dbg --ez sync_now true` → `photos=4` 확인 → `skip_min 1`로 도착 카드 닫힘 → **"가족이 사진 4장을 보냈어요"** 카드 스크린샷
  9~12. 귀가로 닫기 → 앨범 공개(실제 바깥 칩, 사진 정리 줄) → 표지(실제 사진) → 장(사진 콜라주, 도착 장 기록문의 바깥 문장) → **그때 vs 지금** → **다시 가기** QR
  13. 홈 → 정기 방문 → [다시 가기] 대화상자
- `DEMO_OFFLINE=1`: 클라우드 장면(업로드·sync)을 건너뛰고 예시 칩으로 끝까지 가야 한다(오프라인 회귀). 키가 없는 빌드(`yeoro.supabase.key` 비움)도 같은 결과.
- `backend_smoke.sh`: 함수 5개를 한 번에 확인(diary는 200 또는 503 모두 OK, 503이면 "AI 꺼짐"이라고 출력). 등록부터 삭제까지 끝나면 서버에 남는 것이 없어야 한다.
- README: 2b 상태 표, 시연 장면 표(`docs/screens/2b/`), 백엔드 구조(함수 5개와 계약 요약, RLS 정책 없음의 이유, 업로드 페이지가 정적인 이유), 공공데이터 출처와 한계(날씨는 사흘이 지나면 못 받음, 시연 날씨는 촬영일의 실제 날씨), 개인정보 절, 테스트 개수 갱신. 예시로 남은 것(혜택)은 계속 "예시"로 적는다.

- [ ] 사진 확인 → `demo_upload.sh` 단독 실행으로 4장 201 → 시연 전체 2회 연속 통과(2회째는 지난 여행 정리 포함) → `DEMO_OFFLINE=1` 통과 → `backend_smoke.sh` 통과 → 스크린샷 정리
- [ ] 커밋: `2b 통합 시연: 회상·사진 업로드·실제 공공데이터·그때 vs 지금·다시 가기, 백엔드 스모크, README`

### Task 11: AI 기록문 (선택, 마지막)

이 Task를 빼도 위 Task 전부가 그대로 출시된다. `ANTHROPIC_API_KEY`가 없거나 호출이 실패하면 규칙 기반 기록문이 그대로 쓰인다.

**Files:** Create `supabase/functions/_shared/{diary.ts,diary.test.ts}`, `supabase/functions/diary/index.ts`, `app/src/main/java/ai/yeoro/cloud/AiDiary.kt`. Modify `cloud/CloudSync.kt`(여행 닫힌 뒤 한 번), `settings/Settings.kt`(`aiDiary: StateFlow<Boolean>`, 기본 꺼짐), `ui/settings/SettingsScreen.kt`, `ui/album/TripAlbumViewModel.kt`, `ui/album/TripAlbumScreen.kt`, `app/src/debug/.../DebugReceiver.kt`(`ai_diary true|false`), `scripts/scenario_demo.sh`, README. Test `app/src/test/java/ai/yeoro/cloud/AiDiaryTest.kt`

**Interfaces (Produces):**
- `diary` HTTP 계약 (헤더 `apikey`):
  - `POST /functions/v1/diary` body `{"chapters":[{"kind":"DEPARTURE","name":"집 앞 출발","facts":["08시 52분에 출발했습니다.", "…"]}],"outside":["해는 17시 36분에 졌습니다."]}` — 장 ≤ 12, 장마다 사실 ≤ 20, 문장 ≤ 200자
  - 200 `{"chapters":["…장마다 다듬은 글…"],"model":"claude-sonnet-5-5"}` / 400 형식 오류 / 503 `{"error":"ai_unavailable"}`(키 없음, 상위 오류, 시간 초과 20초, `stop_reason == "refusal"`, 장 수 불일치)
  - 호출: `npm:@anthropic-ai/sdk`, 모델 `claude-sonnet-5-5`(사용자 지정), `max_tokens` 4000, `output_config: { effort: "low", format: <{"chapters": string[]} JSON 스키마> }`, 서버 측 폴백 `betas: ["server-side-fallback-2026-07-01"], fallbacks: "default"`(거절 시 자동 대체, 그래도 refusal이면 503). 구현 전에 claude-api 스킬의 TypeScript README로 정확한 SDK 모양을 확인한다. 시스템 프롬프트: "주어진 사실만으로 담백한 한국어 기록문을 쓴다. 사실에 없는 시각·숫자·장소·사람·감정을 더하지 않는다. 동화체·과장 금지. 장마다 2~4문장."
- 앱:
```kotlin
fun diaryRequest(chapters: List<Chapter>, outside: TripOutside?, zone: ZoneId): String   // 가족 이름 없이(names = emptyMap()) 만든 사실 문장
fun factsHash(requestJson: String): String
/** AI 글이 입력 사실에 없는 시각·숫자·장소를 담으면 false */
fun validateAiText(text: String, facts: List<String>): Boolean
fun withFamilyNames(text: String, names: Map<Seat, String>): String   // "뒷좌석 왼쪽" 같은 좌석 이름을 가족 이름으로 되돌림
```

**동작:**
- 설정 "AI로 기록문 다듬기"(기본 꺼짐, 동의 꺼짐이면 비활성). 켜져 있고 동의·enabled이면 여행을 닫은 뒤 sync에서 한 번 요청한다. 응답은 장마다 `validateAiText`를 통과한 것만 쓰고, 떨어진 장은 규칙 기반 글을 쓴다. 결과는 `trip_diary`(factsHash가 같으면 다시 묻지 않음). 503·오프라인이면 아무것도 안 하고 규칙 기반 그대로.
- validateAiText(순수): 출력의 모든 숫자열(`\d+`)과 시각(`\d{1,2}시( \d{1,2}분)?`, `\d{1,2}:\d{2}`)이 입력 사실에 그대로 있어야 한다. 장소 꼬리(해변|휴게소|공원|호수|마을|시장|역|항|산|강|축제|시|군|구|동|읍|면)로 끝나는 낱말은 입력에 있어야 한다. 길이는 입력 사실 합의 2배 이하. 하나라도 어기면 false.
- 앨범 기록문 아래 작은 꼬리표는 그대로 "차가 기록한 사실로 쓴 글"이다. AI 글이면 "AI가 다듬음" 꼬리표를 하나 더 붙인다.
- 개인정보: 요청에는 가족 이름이 들어가지 않는다(좌석 이름으로 보내고 앱에서 되돌린다). 경로·좌표도 없다. 설정과 README의 "AI 기록문" 줄을 이제 보인다.
- `ANTHROPIC_API_KEY`가 `keys.env`에 있을 때만 `supabase secrets set`(값을 찍지 않음). 없으면 함수만 배포하고 503을 확인한다.

- [ ] TDD: `diary.test.ts` — 요청 검증(장 13개 400, 긴 문장 400), 응답 장 수 불일치 → 503 판단. `AiDiaryTest` — 이름이 요청에 없음, 새 숫자("3시 15분", "20km") 거부, 입력에 없는 "속초해변" 거부, 입력 그대로 통과, 좌석 이름 되돌리기, factsHash 같으면 재요청 안 함, 503 → 규칙 기반 유지
- [ ] 구현 → 배포 → 스모크(키 있으면 200·없으면 503) → 시연에 `--es ai_diary true` 장면 추가 → AI 글이 있는 장 스크린샷 `docs/screens/2b/ai-diary.png`
- [ ] 커밋: `AI 기록문(선택): diary 함수(claude-sonnet-5-5)와 사실 검증, 실패하면 규칙 기반`

## 일정 (제출 11/15, 2b 목표 11/8)
- 10/28 T1 · 10/29 T2 · 10/30 T3 · 10/31 T4 · 11/1 T5 · 11/2 T6 · 11/3~4 T7 · 11/5 T8 · 11/6 T9 · 11/7 T10 · 11/8 T11. T11이 밀리면 빼고 제출한다.

## Self-Review
- 요청한 결정 대응:
  - 1 키는 함수 secrets에만, 앱은 publishable 키(BuildConfig, local.properties) → Global Constraints, T1, T4
  - 2 오프라인 우선, 예시 칩은 실제 데이터가 없을 때만 → T4, T6, T10(`DEMO_OFFLINE`)
  - 3 `outside`(기상청·천문연 일몰·TourAPI·특일), 여행별 캐시 v4 + 마이그레이션 테스트, 앨범 칩·기록문 사실 문장, 엔진 PlaceKind·holiday, P단·여행 닫을 때만 → T3, T4, T6
  - 4 업로드(토큰=권한, EXIF·lastModified, 2048 축소, 비공개 버킷, 등록된 토큰만, 30일, 기기 비밀로 목록, 20MB·200장·분당 30장, RLS) → T1, T2. HTML 제약 때문에 정적 페이지 + 302로 바꿈
  - 5 사진 → 장(±10분, 밖이면 가까운 장), 이미지 로더 결정(Coil 없이), 자리표시 유지, "사진 N장" 카드(P만) → T7
  - 6 그때 vs 지금(방문 2회 이상, 500m, 앨범·도착 카드, 순수 선택 테스트) → T8
  - 7 다시 가기(네이버 지도·TourAPI 홈페이지 QR, 앨범 끝·정기 방문), 1년 전 이번 주(±3일, P만, 하루 한 번), 금요일 규칙은 생략 → T9
  - 8 왕복 여행 → T5
  - 9 AI 마지막, `claude-sonnet-5-5`, 503 폴백, 검증기, 캐시, 꼬리표 → T11
  - 10 INTERNET 권한, 동의·설정·README, 동의 꺼짐이면 호출 0, 여행 삭제 시 서버 삭제, AI에 가족 이름 빼기 → T4, T11
  - 11 시연(사진 업로드 curl, EXIF 주입, 카드·앨범·그때 vs 지금·다시 가기)과 `backend_smoke.sh` → T1~T3, T10
- 이전 단계 남은 과제: 왕복 여행 앨범 → T5. QR 주소가 실제로 열림 → T2. 나머지(디버그 시계·좁은 창 미확인 등)는 2b 범위 밖으로 둔다.
- 이름 일관성: `CloudSync.syncOnce/ensureRegistered/deleteTrip`, `TripOutside/OutsideInfo/parseTripOutside`, `AlbumPhoto/assignPhotos/coverPhoto`, `Promo.Photos/Promo.Memory/livePromo`, `VisitSide/ArrivalInfo.then`, `TripLegs/ChapterKind.RETURN`을 Task 사이에서 같은 이름으로 썼다. 서버 계약의 필드 이름(`taken_at_source`, `thumb_url`, `dest_name`)은 T2 정의와 T4 파서가 같다.
- 열린 질문(실행 전에 사용자에게):
  1. 업로드 페이지용 공개 GitHub 저장소 `msh8484-pixel/yeoro-upload`(Pages)를 만들어도 되는가? 아니면 Vercel.
  2. 과거 날씨를 사흘 넘어서도 보이려면 data.go.kr에서 "종관기상관측(ASOS) 일자료" 활용신청이 필요하다. 신청하면 T3에 한 단계를 더한다(신청 안 해도 계획은 그대로 돈다).
  3. `ANTHROPIC_API_KEY`를 `keys.env`에 넣을지(T11).
