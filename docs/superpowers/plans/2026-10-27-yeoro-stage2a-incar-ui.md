# 여로 2a단계: 차 안 화면과 애니메이션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 1단계 엔진 위에 실제 제출할 차 안 화면을 만든다. 시안(`~/projects/hyundai/album-prototype/index.html`, 메인 스토리 12장면)의 흐름과 효과를 Compose로 옮기고, 주행 중에는 모든 모션을 끈다.

**Architecture:** 화면은 Compose 단일 액티비티이고, Navigation Compose로 홈·여행 앨범·지금(기록 상태)·설정을 오간다. 데이터는 Room(여행, 순간, 위치 샘플)을 ViewModel이 Flow로 읽는다. 주행 제한 상태는 `CarUxRestrictionsManager`에서 읽어 `LocalMotion` CompositionLocal로 내려보낸다. 모든 애니메이션은 이 값을 확인하고, 제한 중이면 즉시 최종 상태를 보여 준다. 사진과 AI 기록문은 2b단계에서 붙이므로, 2a에서는 사진 자리와 규칙 기반 기록문을 쓴다.

**Tech Stack:** 1단계와 같다(Kotlin 2.4.21, AGP 9.4.1, Compose BOM 2026.09.00, Room 2.8.5, KSP 2.3.12). 추가: `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `com.google.zxing:core`(QR)

## Global Constraints
- 1단계 Global Constraints는 그대로 유지한다: `yeoro/docs/superpowers/plans/2026-10-26-yeoro-stage1-car-core.md`, `.superpowers/sdd/global-constraints.md`
- **주행 중(UX 제한 활성)에는 애니메이션, 자동 스크롤, 동영상을 쓰지 않는다.** 화면은 정적이고 핵심 정보만 크게 보여 준다. 제한이 풀린 P단에서만 모션을 쓴다.
- 디자인 기준은 Pleos 디자인 가이드다.
  - Asta Sans를 쓰고, 라이트·다크는 시스템을 따른다.
  - 본문은 30sp 이상, 터치 영역은 최소 76dp다.
  - 한눈에(1~2초) 읽혀야 한다.
  - 가로·세로와 분할 창(폭 852px 수준)에서 모두 깨지지 않아야 한다.
- 시각 언어는 시안을 따른다.
  - 앨범과 회상 화면에만 '해 질 녘' 남색 바탕(#0E1220)과 노을 앰버(#FFB46A) 포인트를 쓴다.
  - 기록문과 앨범 제목은 Gowun Batang(OFL, Google Fonts) 세리프로 쓴다.
  - 나머지 화면은 1단계 Pleos 테마 토큰을 쓴다.
- 기록문은 사실 기반으로 쓴다(동화체 금지). 2a는 규칙 기반 템플릿이고, 2b에서 AI로 바꾼다.
- 혜택, 공공데이터, 예약은 2a에서 "예시" 표시를 단 고정 데이터로 보여 준다. 실제 연동은 2b에서 한다.
- 사용자 문구는 한국어로 쓴다.

## File Structure (신규/변경)
```
app/src/main/java/ai/yeoro/
├── data/YeoroDb.kt            # v3: LocationSampleEntity, MomentEntity.lat/lon, TripEntity.destLat/destLon/title
├── data/TripRepository.kt     # 여행·순간·위치 조회, 방문 횟수 계산, 확인 안 한 여행 처리
├── settings/Settings.kt       # SharedPreferences: home, consent, 가족 이름
├── ux/DrivingRestriction.kt   # CarUxRestrictionsManager → StateFlow<Boolean>
├── ux/Motion.kt               # LocalMotion, motion-aware 애니메이션 헬퍼
├── ui/YeoroNav.kt             # NavHost, 하단/측면 내비
├── ui/theme/                  # Theme.kt 이동 + AlbumTheme(남색·앰버·Gowun Batang)
├── ui/live/LiveScreen.kt      # 지금: 일상/후보/기록 중(정적)/출발 카드/도착 인증
├── ui/live/DepartureCard.kt   # 좌석이 하나씩 켜지는 출발 연출
├── ui/live/ArrivalCard.kt     # 방문 인증 스탬프, QR, 30초 카드
├── ui/album/TripAlbumScreen.kt# 공개 → 표지 → 장 넘기기
├── ui/album/RouteMap.kt       # 경로 Canvas, 그리기·핀 드롭
├── ui/album/Chapters.kt       # 챕터 구성, 규칙 기반 기록문
├── ui/home/HomeScreen.kt      # 책장, 확인 안 한 여행, 가족 지도, 통계
├── ui/settings/SettingsScreen.kt
└── service/RecorderService.kt # 위치 샘플 기록, 순간 좌표, 동의 이벤트
res/font/gowun_batang_*.ttf, res/mipmap-*/ic_launcher*
```

---

### Task 1: 데이터 v3 (위치 샘플, 순간 좌표, 목적지 좌표)

**Files:** Modify `data/YeoroDb.kt`, `service/RecorderService.kt`. Create `data/TripRepository.kt`. Test `app/src/test/java/ai/yeoro/data/YeoroDaoV3Test.kt`, `MigrationTest`(2→3)

**Interfaces (Produces):**
- `@Entity LocationSampleEntity(id, tripId: Long?, timeMs, lat, lon)` (FK cascade, tripId null = 버퍼)
- `MomentEntity`에 `lat: Double?`, `lon: Double?` 추가
- `TripEntity`에 `destLat: Double?`, `destLon: Double?`, `title: String?` 추가
- DAO:
  - `insertSample(s)`, `samples(tripId): Flow<List<LocationSampleEntity>>`
  - `attachBuffer`가 위치 샘플도 함께 붙인다(같은 sinceMs 규칙)
  - `purgeBuffer`는 위치 샘플도 같은 cutoff로 지운다
  - `deleteBuffer`는 둘 다 지운다
  - `tripsNear(lat, lon)`: 반경 계산은 Kotlin에서 한다
- `class TripRepository(dao)`:
  - `trips()`, `trip(id)`, `moments(id)`, `samples(id)`
  - `visitCount(lat, lon, radiusM = 500, beforeMs)`: 같은 목적지에 이전에 끝난 여행 수 + 1
  - `confirmUnconfirmed(id)`: state를 ALBUM_READY로 바꾼다
  - `deleteTrip(id)`
- Migration 2→3. 스키마 3.json을 커밋한다.

**동작:**
- RecorderService:
  - 엔진 상태가 CANDIDATE, TRIP, STAYING일 때 위치를 30초마다(또는 300m 이상 움직이면) `insertSample`한다. tripId 규칙은 순간과 같다.
  - `RecordMoment` 때 현재 위치를 moment의 lat/lon에 넣는다.
  - `StartTrip` 때 목적지 좌표를 trip에 넣는다(NaviDestination 또는 debugDestination).

- [ ] TDD: DAO 테스트 먼저 작성
  - 샘플 attach, purge, delete
  - cascade 삭제
  - visitCount: 같은 목적지 400m 안에서 끝난 여행 1개 → 2, 600m 밖 → 1
  - 2→3 마이그레이션이 기존 행을 보존하는지
- [ ] 구현, `./gradlew :app:testDebugUnitTest`, 커밋

### Task 2: 주행 제한과 모션 게이트

**Files:** Create `ux/DrivingRestriction.kt`, `ux/Motion.kt`. Test `app/src/test/java/ai/yeoro/ux/MotionTest.kt`

**Interfaces (Produces):**
- `class DrivingRestriction(context) { val restricted: StateFlow<Boolean>; start(); stop() }`
  - `Car.createCar` → `Car.CAR_UX_RESTRICTION_SERVICE` → `CarUxRestrictionsManager.registerListener`
  - `isRequiresDistractionOptimization`이면 restricted=true
  - 실패하면 기어 기반 폴백: 기어가 P가 아니면 restricted로 본다(VehicleSource 사용)
- `val LocalMotion = staticCompositionLocalOf { true }` (true = 모션 허용)
- 헬퍼:
  - `@Composable fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T>`: 허용이면 spec, 아니면 `snap()`
  - `@Composable fun rememberMotionProgress(key: Any?, durationMs: Int, delayMs: Int = 0): State<Float>`: 허용이면 0→1 tween, 아니면 바로 1
- 순수 함수 `fun motionAllowed(restricted: Boolean, reducedMotionSetting: Boolean): Boolean` + 테스트
  - 시스템 애니메이터 배율이 0이어도 끈다(`Settings.Global.ANIMATOR_DURATION_SCALE`)

- [ ] TDD(순수 함수) → 구현 → 에뮬레이터에서 `vc.sh gear Drive`로 restricted가 true로 바뀌는지 로그 확인 → 커밋

### Task 3: 앱 골격과 내비게이션

**Files:** Create `ui/YeoroNav.kt`, `ui/theme/AlbumTheme.kt`, ViewModel들(`ui/*/…ViewModel.kt`). Modify `ui/MainActivity.kt`, `ui/Theme.kt` → `ui/theme/Theme.kt`. Add `res/font/gowun_batang_regular.ttf`, `gowun_batang_bold.ttf`(https://github.com/google/fonts/tree/main/ofl/gowunbatang)

**동작:**
- 목적지 4개: `live`(지금, 시작 화면), `home`(우리 가족 여행), `trip/{id}`(앨범), `settings`
- 탐색 레일: 폭이 600dp 이상이면 왼쪽 세로 레일, 좁으면(분할 창 세로) 아래쪽 바. 아이콘 + 한국어 라벨, 터치 영역 76dp 이상
- `MainActivity`가 `DrivingRestriction`을 시작하고 `CompositionLocalProvider(LocalMotion provides motionAllowed(...))`로 감싼다
- AlbumTheme: 남색 바탕 `#0E1220`, 표면 `rgba(255,255,255,.06)`, 선 `rgba(214,222,255,.12)`, 텍스트 `#EEF0F7`, 보조 `#9AA1B8`, 앰버 `#FFB46A`, 코랄 `#FF8A7A`. 제목·기록문은 Gowun Batang
- 1단계 StatusScreen과 EngineDebug는 `live` 안의 디버그 영역으로 옮긴다(debuggable일 때만)

- [ ] 구현 → 에뮬레이터 스크린샷(가로 전체, 그리고 `wm size 1280x1440`으로 분할 창 폭 흉내) → 커밋

### Task 4: 지금 화면 (일상, 출발 카드, 기록 중)

**Files:** Create `ui/live/LiveScreen.kt`, `ui/live/DepartureCard.kt`, `ui/live/LiveViewModel.kt`. Modify `YeoroApp.kt`(EngineBus에 `pendingAsk: StateFlow<Action?>`), `service/RecorderService.kt`, `service/Notifier.kt`

**동작(시안 장면 2~4 기준):**
- **일상(DAILY):** 정적. "지금은 일상이에요"와 다음 회상 카드 자리(2b 전까지 숨김)
- **출발 카드:**
  - AskBeforeDeparture가 대기 중이고 앱이 보이면 알림 대신 앱 안 카드를 띄운다.
  - 카드 내용: 목적지, 거리, 탑승 좌석 4칸, [오늘은 그냥]·[여행 기록 시작]
  - 수락하면 좌석이 360ms 간격으로 하나씩 켜지고, 미니 경로 선이 그려진다(모션 허용 시).
  - 알림은 계속 보낸다. 알림 스타일은 BigText, 카테고리 RECOMMENDATION, 앱 아이콘을 쓴다.
- **소급 질문:** 같은 방식의 카드. 문구는 "오늘, 여행이었나요?"
- **기록 중(TRIP / STAYING / CANDIDATE):**
  - 무조건 정적이다.
  - "● 기록 중", 큰 숫자 "오늘의 순간 N", 최근 순간 3개(시각, 아이콘, 문구, 출처)
  - 하단에 "주행 중 화면 원칙" 안내
- 순간 문구 매핑(한국어): RAIN_START "비가 오기 시작했어요"(와이퍼), RAIN_STOP "비가 그쳤어요", WINDOWS_OPEN "창문 N개가 열렸어요"(창문), STOP "N분 쉬어 갔어요"(도어·P단), COMPANION_JOINED "함께하는 사람이 늘었어요"(좌석)
- 아이콘: 시안의 SVG 경로를 `ImageVector`로 옮긴다(seat, stop, rain, sun, window, flag, trunk, clock, user).

- [ ] 구현 → 에뮬레이터 상황 A로 출발 카드 확인. 좌석 애니메이션은 P에서만 돌고, D에서는 정적이어야 한다. 스크린샷 2장 → 커밋

### Task 5: 도착 인증 카드

**Files:** Create `ui/live/ArrivalCard.kt`. Modify `LiveViewModel`. Add `com.google.zxing:core`

**동작(시안 장면 5):**
- 여행 중 내비가 도착하거나, 목적지 300m 안에서 P단으로 2분 이상 서 있으면 도착으로 본다.
- 도착하면 앱 안 카드를 띄운다.
  - "○○, N번째 방문이에요"(`visitCount`)
  - 스탬프: 원형 테두리 + 숫자. 등장 시 scale 2.2→1, 회전 −20°→−8° 스프링(모션 허용 시)
  - 단골 단계 막대 3칸(1회 스탬프, 2회 그때 vs 지금, 3회 단골)
  - 받은 혜택 목록. 고정 예시 데이터이고 "예시" 태그를 단다.
  - QR 카드: zxing으로 `https://yeoro.app/u/{tripId}`를 인코딩한다(2b에서 실제 주소로 바꾼다).
  - "이 카드는 30초 뒤 사라져요"
- 30초 뒤, 또는 기어가 P를 벗어나면 닫는다.

- [ ] 구현 → debug receiver에 `arrive` extra를 추가해 강제 도착 → 스크린샷 → 커밋

### Task 6: 앨범 (공개, 표지, 장 넘기기)

**Files:** Create `ui/album/TripAlbumScreen.kt`, `RouteMap.kt`, `Chapters.kt`, `TripAlbumViewModel.kt`. Test `app/src/test/java/ai/yeoro/ui/album/ChaptersTest.kt`

**동작(시안 장면 6, 8, 9, 10):**
- **챕터 구성(순수 함수, 테스트):**
  - 출발(첫 샘플) → STOP 순간마다 하나 → 도착(목적지 또는 마지막 정차)
  - 각 장: 이름(목적지명, "휴게소" 등 일반 이름, 없으면 "N번째 쉼"), 시각, 머문 시간
  - 장에 들어가는 순간: 해당 구간에 일어난 순간들
- **규칙 기반 기록문(순수 함수, 테스트):** 사실 문장을 이어 붙인다.
  - 예: "08시 52분, 네 자리가 모두 찼습니다." / "10시 58분부터 와이퍼가 움직였습니다." / "13시 02분, 창문 네 개가 내려갔습니다."
  - 제목 규칙: 비 시작과 그침이 모두 있으면 "비가 그친 오후, {목적지}로", 창문 순간만 있으면 "창문을 연 날, {목적지}", 둘 다 없으면 "{목적지}까지 {거리}km"
- **경로 지도(Canvas):**
  - 위치 샘플을 화면에 맞게 투영한다(등장방형, 여백 8%).
  - 남색 바탕에 은은한 등고선 장식(노이즈 기반 원 몇 겹). 경로는 앰버 4dp + 흐린 글로우 14dp
  - `PathMeasure`로 2.6초 동안 경로를 그린다. 그다음 정차 핀이 600ms 간격으로 떨어진다(스프링). 장 이름 라벨을 붙인다.
- **공개 화면:**
  - 왼쪽에 지도, 오른쪽에 날짜 eyebrow
  - 제목은 Gowun Batang 70sp로 85ms마다 한 글자씩 타이핑한다.
  - 통계 4칸: km 숫자 증가, 순간 수, 소요 시간, 탑승 인원
  - "그날의 바깥"은 2a에서 예시 칩으로 보여 준다.
- **표지:** 대표 이미지 자리(2b 전까지는 시간대·날씨에 따른 그라디언트 일러스트), 제목 116sp 세리프, 메타 줄. 등장할 때 1.08→1 줌 6초
- **장 넘기기:**
  - `HorizontalPager`로 장마다 한 페이지
  - 넘길 때 graphicsLayer로 rotationY ±12°, 투명도 전환
  - 콜라주 사진 자리 2~5칸은 기울기와 깊이가 다르다. 터치 드래그 위치에 따라 패럴랙스가 생긴다(모션 허용 시).
  - 오른쪽: 기록문, "차가 기억한 순간" 칩(실선), "그날의 바깥" 칩(점선, 예시)
  - 하단에 장 트랙(점과 이름)
- 모든 모션은 `LocalMotion`이 false면 최종 상태로 즉시 보인다.

- [ ] TDD(챕터, 기록문) → 구현 → debug receiver `seed_trip` extra로 샘플 여행 1개를 DB에 넣는다(강릉 경로 좌표 30개, STOP 2개, RAIN_START, RAIN_STOP, WINDOWS_OPEN) → 공개, 표지, 장 스크린샷 → 커밋

### Task 7: 홈 (책장, 확인 안 한 여행, 가족 지도)

**Files:** Create `ui/home/HomeScreen.kt`, `HomeViewModel.kt`

**동작(시안 장면 12):**
- 제목 "우리 가족 여행"
- 탭: 여행 앨범 / 정기 방문(2a에서는 같은 목적지 2회 이상인 여행 묶음)
- **책장:** 4열 그리드. 표지 그라디언트 위에 제목(세리프)과 날짜·장소. 호버나 누름 시 rotationY −12°, 새 앨범에는 "새 앨범" 배지
- **확인하지 않은 여행(UNCONFIRMED):** 맨 위 카드에 "이날, 여행이었나요?"와 [남기기]·[지우기]
- **오른쪽 패널:** 우리 가족 지도(방문지를 점으로 찍고 방문 횟수를 숫자로 표시), 올해 km·간 곳·여행 수, 단골 단계(같은 목적지 방문 횟수)
- 빈 상태: "아직 여행이 없어요. 다음 가족 나들이부터 조용히 기록할게요."

- [ ] 구현 → seed 여행 2~3개로 스크린샷 → 커밋

### Task 8: 설정 (집 위치, 동의, 가족)

**Files:** Create `settings/Settings.kt`, `ui/settings/SettingsScreen.kt`. Modify `RecorderService.kt`

**동작:**
- 집 위치: [지금 위치를 집으로] / [다시 정하기]. 기존 "첫 위치=집" 자동 설정은 집이 없을 때만 쓰고, 설정 화면에 "자동으로 정해졌어요"라고 표시한다.
- 여로 기록 동의 스위치(기본 꺼짐)
  - 처음 실행할 때 동의 화면을 보여 준다: 무엇을 기록하는지, 24시간 삭제, 얼굴 인식 없음
  - 변경하면 `Event.ProfileChanged(consented)`를 dispatch한다. 서비스 시작 때 첫 Tick 전에 현재 값을 보낸다.
- 가족 이름(좌석별, 선택): 출발 카드와 기록문에 쓴다. 비어 있으면 "운전석" 같은 좌석 이름을 쓴다.
- 앱 아이콘: 앰버→코랄→보라 그라디언트 둥근 사각형 안에 흰 지도 핀(adaptive icon)

- [ ] 구현 → 동의 꺼짐 상태에서 상황 A를 돌리면 아무것도 안 뜨는지(scenario_a는 동의 켜기 extra 추가) → 커밋

### Task 9: 통합 시연 경로와 화면 회귀

**Files:** Modify `scripts/` (scenario_demo.sh), README

**동작:**
- `scripts/scenario_demo.sh`: 동의 켜기 → 상황 A 출발 카드 → 수락 → 주행(정적 화면 확인) → 비, 창문 → 도착 인증 → 앨범 공개까지 한 번에 재연한다(debug extra로 시간 압축). 발표와 영상 촬영용이다.
- 장면마다 스크린샷을 `docs/screens/2a/`에 저장하고 README에 표로 정리한다.

- [ ] 실행 → 스크린샷 → 커밋

## Self-Review
- 시안 장면 대응:
  - 2 출발 제안 → Task 4
  - 3 기록 시작, 좌석 → Task 4
  - 4 주행 정적 → Task 4
  - 5 도착 인증 → Task 5
  - 6 공개 → Task 6
  - 8 표지 → Task 6
  - 9 장 넘기기 → Task 6
  - 12 홈 → Task 7
  - 7 사진 정리, 10 그때 vs 지금, 11 다시 가기, 1 회상 알림 → 2b(사진, 공공데이터, 예약 필요)
- 1단계 남은 과제:
  - 동의 연결 → Task 8
  - 집 설정 UI → Task 8
  - UNCONFIRMED UI → Task 7
  - 후보 영속화는 2b 이후(로드맵)
- 모션 규칙: 모든 애니메이션 Task가 `LocalMotion`을 거치도록 명시했다.
