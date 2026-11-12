# 여로 1단계: 차량 코어 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pleos Connect 에뮬레이터에서 차량 신호를 받아 여행 여부를 판단하고, 순간을 기록하고, P단에서만 알림을 띄우는 여로 앱의 핵심을 만든다.

**Architecture:** 판단 로직은 Android에 의존하지 않는 순수 Kotlin `engine` 모듈에 둔다. 상황 A~L을 JVM 단위 테스트로 재연할 수 있게 하기 위해서다. `app` 모듈은 Pleos Vehicle SDK, NaviHelper, AAOS CarPropertyManager 값을 `Snapshot`과 `Event`로 바꿔 엔진에 넣는다. 엔진이 돌려준 `Action`은 포그라운드 서비스가 받아 저장(Room)과 알림으로 실행한다. 화면은 Compose로 만들고 Pleos 디자인 가이드(Asta Sans, 라이트·다크 토큰)를 따른다.

**Tech Stack:** Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.2, Compose BOM 2026.09.00, Room, JUnit 4, Pleos SDK(Vehicle 2.3.2, NaviHelper 3.0.5), Android Automotive `android.car`

## Global Constraints
- minSdk 28, targetSdk 34, compileSdk 37 (Compose BOM 요구)
- 패키지 `ai.yeoro`. 엔진 패키지는 `ai.yeoro.engine`
- Pleos Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/`
- 화면 알림은 기어 P에서만 띄운다. 먼저 거는 제안은 하루 1번까지다(도착·앨범 알림은 횟수에서 뺀다).
- 점수 기준: 70 이상 = 확신, 40~69 = 후보, 40 미만 = 일상
- 일상 기록은 24시간 뒤 삭제한다.
- 기록에 동의하지 않은 프로필이면 판단도 기록도 하지 않는다.
- 사용자에게 보이는 문구는 한국어로 쓴다.
- 운전 교대 감지는 좌석 착석 신호만으로는 할 수 없으므로 1단계에서 제외한다(로드맵).

## File Structure
```
yeoro/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew…
├── engine/                                   # 순수 Kotlin (JVM)
│   ├── build.gradle.kts
│   └── src/main/kotlin/ai/yeoro/engine/
│       ├── Model.kt            # Gear, Seat, PlaceKind, Destination, Snapshot, CalendarInfo
│       ├── TripScorer.kt       # 점수표
│       ├── MomentDetector.kt   # 순간 기록 규칙
│       ├── NotificationPolicy.kt
│       └── TripEngine.kt       # 상태 흐름 + Event → Action
│   └── src/test/kotlin/ai/yeoro/engine/
│       ├── TripScorerTest.kt, MomentDetectorTest.kt, NotificationPolicyTest.kt
│       └── ScenarioTest.kt     # 상황 A~L 재연
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/font/asta_sans.ttf, res/values/strings.xml
│       └── java/ai/yeoro/
│           ├── YeoroApp.kt
│           ├── vehicle/VehicleSource.kt   # Pleos Vehicle SDK + CarPropertyManager → Flow<Snapshot>
│           ├── vehicle/NaviSource.kt      # NaviHelper → Flow<NaviEvent>
│           ├── data/YeoroDb.kt            # Room: trips, moments
│           ├── service/RecorderService.kt # 엔진 실행, Action 처리
│           ├── service/Notifier.kt        # P단 알림
│           └── ui/ (Theme.kt, MainActivity.kt, StatusScreen.kt, EngineDebug.kt)
└── scripts/vc.sh, scripts/scenario_a.sh, scripts/scenario_b.sh, scripts/scenario_c.sh
```

---

### Task 1: 저장소와 빌드 뼈대

**Files:**
- Create: `yeoro/settings.gradle.kts`, `yeoro/build.gradle.kts`, `yeoro/gradle.properties`, `yeoro/gradle/libs.versions.toml`, `yeoro/.gitignore`, `yeoro/engine/build.gradle.kts`, `yeoro/app/build.gradle.kts`, `yeoro/app/src/main/AndroidManifest.xml`, `yeoro/README.md`
- Copy: `pleos-starter/gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `local.properties`, `scripts/vc.sh`

**Interfaces:**
- Produces: `./gradlew :engine:test` (JVM 테스트), `./gradlew :app:assembleDebug`

- [ ] **Step 1: 래퍼 복사와 설정 파일 작성**

```bash
cd ~/projects/hyundai && mkdir -p yeoro/gradle yeoro/scripts yeoro/engine/src/main/kotlin/ai/yeoro/engine yeoro/engine/src/test/kotlin/ai/yeoro/engine yeoro/app/src/main/java/ai/yeoro
cp -R pleos-starter/gradlew pleos-starter/gradlew.bat pleos-starter/gradle/wrapper pleos-starter/local.properties yeoro/ ; mv yeoro/wrapper yeoro/gradle/wrapper
cp pleos-starter/scripts/vc.sh yeoro/scripts/vc.sh
```

`yeoro/settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "yeoro"
include(":engine", ":app")
```

`yeoro/gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.21"
ksp = "2.4.21-2.0.4"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
coreKtx = "1.19.1"
lifecycle = "2.10.0"
room = "2.8.4"
coroutines = "1.11.0"
junit = "4.13.2"
pleosVehicle = "2.3.2"
pleosNavi = "3.0.5"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
androidx-lifecycle-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
pleos-vehicle = { group = "ai.pleos.playground", name = "Vehicle", version.ref = "pleosVehicle" }
pleos-navihelper = { group = "ai.pleos.playground", name = "NaviHelper", version.ref = "pleosNavi" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```
(Room·lifecycle·coroutines·ksp 버전은 Step 3 빌드에서 해석이 안 되면 `https://dl.google.com/android/maven2/.../maven-metadata.xml`과 Maven Central의 최신 안정판으로 맞춘다.)

`yeoro/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

`yeoro/gradle.properties`:
```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
```

`yeoro/engine/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { testImplementation(libs.junit) }
```

`yeoro/app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
android {
    namespace = "ai.yeoro"
    compileSdk = 37
    defaultConfig { applicationId = "ai.yeoro"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true }
    useLibrary("android.car")
}
dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
}
```

`yeoro/app/src/main/AndroidManifest.xml` (Task 7·8에서 서비스·액티비티를 채운다):
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.type.automotive" android:required="true" />
    <application android:label="여로" android:theme="@android:style/Theme.DeviceDefault.NoActionBar" />
</manifest>
```

`yeoro/.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
.DS_Store
.kotlin/
*.jks
keystore.properties
.env*
```

- [ ] **Step 2: 엔진 모듈 동작 확인용 빈 테스트**

`yeoro/engine/src/test/kotlin/ai/yeoro/engine/SmokeTest.kt`:
```kotlin
package ai.yeoro.engine
import org.junit.Assert.assertTrue
import org.junit.Test
class SmokeTest { @Test fun builds() = assertTrue(true) }
```

- [ ] **Step 3: 빌드**

Run: `cd yeoro && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :engine:test :app:assembleDebug -q`
Expected: 에러 없이 끝남. `app/build/outputs/apk/debug/app-debug.apk` 생성

- [ ] **Step 4: git과 GitHub 비공개 저장소**

```bash
cd yeoro && git init -q && git add -A && git -c user.email=<email> -c user.name=<name> commit -qm "chore: scaffold yeoro (engine + app)"
gh repo create msh8484-pixel/yeoro --private --source=. --push
```
Expected: `https://github.com/msh8484-pixel/yeoro` 생성, main 푸시

---

### Task 2: 엔진 모델과 여행 점수

**Files:**
- Create: `engine/src/main/kotlin/ai/yeoro/engine/Model.kt`, `engine/src/main/kotlin/ai/yeoro/engine/TripScorer.kt`
- Test: `engine/src/test/kotlin/ai/yeoro/engine/TripScorerTest.kt`

**Interfaces:**
- Produces:
  - `enum Gear { P, R, N, D, UNKNOWN }`
  - `enum Seat { DRIVER, PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT }`
  - `enum PlaceKind { TOURIST, LODGING, CAMPING, WORK, OTHER }`
  - `data class Destination(name: String, distanceKm: Double, kind: PlaceKind, frequent: Boolean = false, regular: Boolean = false, visits: Int = 0)`
  - `data class CalendarInfo(weekend: Boolean, holiday: Boolean, hour: Int)` with `val commuteHour: Boolean`
  - `data class Snapshot(timeMs: Long, gear: Gear, speedKmh: Float, seats: Set<Seat>, trunkOpen: Boolean, wiperOn: Boolean, openWindows: Int, doorOpen: Boolean, homeDistanceKm: Double)`
  - `data class ScoreInput(destination: Destination?, seats: Set<Seat>, trunkOpens: Int, calendar: CalendarInfo, newRoute: Boolean, homeDistanceKm: Double, parkedAwayMin: Int)`
  - `data class ScoredSignal(label: String, points: Int, source: String)`
  - `data class ScoreResult(signals: List<ScoredSignal>)` with `val total: Int`, `val band: Band`
  - `enum Band { CONFIDENT, CANDIDATE, DAILY }`
  - `object TripScorer { fun score(input: ScoreInput, thresholdBoost: Int = 0): ScoreResult }`

- [ ] **Step 1: 실패하는 테스트 작성**

`TripScorerTest.kt`:
```kotlin
package ai.yeoro.engine
import org.junit.Assert.assertEquals
import org.junit.Test

class TripScorerTest {
    private val sat = CalendarInfo(weekend = true, holiday = false, hour = 8)
    private val tueMorning = CalendarInfo(weekend = false, holiday = false, hour = 7)
    private val family = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)

    @Test fun plannedFamilyTrip_isConfident100() {
        val r = TripScorer.score(ScoreInput(Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST), family, 2, sat, false, 0.0, 0))
        assertEquals(100, r.total); assertEquals(Band.CONFIDENT, r.band)
    }
    @Test fun spontaneousDrive_startsAsCandidate45_thenRisesTo80() {
        val seats = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        val start = TripScorer.score(ScoreInput(null, seats, 1, sat, true, 0.0, 0))
        assertEquals(45, start.total); assertEquals(Band.CANDIDATE, start.band)
        val far = TripScorer.score(ScoreInput(null, seats, 1, sat, true, 86.0, 0))
        assertEquals(60, far.total)
        val parked = TripScorer.score(ScoreInput(null, seats, 1, sat, true, 86.0, 60))
        assertEquals(80, parked.total); assertEquals(Band.CONFIDENT, parked.band)
    }
    @Test fun commute_isZeroDaily() {
        val r = TripScorer.score(ScoreInput(Destination("회사", 9.0, PlaceKind.WORK, frequent = true), setOf(Seat.DRIVER), 0, tueMorning, false, 0.0, 0))
        assertEquals(0, r.total); assertEquals(Band.DAILY, r.band)
    }
    @Test fun soloBusinessTrip_isDaily() {
        val r = TripScorer.score(ScoreInput(Destination("부산 벡스코", 392.0, PlaceKind.WORK), setOf(Seat.DRIVER), 0, CalendarInfo(false, false, 6), false, 0.0, 0))
        assertEquals(Band.DAILY, r.band)
    }
    @Test fun grandmaHoliday_isConfidentAndCapped() {
        val r = TripScorer.score(ScoreInput(Destination("할머니댁", 142.0, PlaceKind.OTHER, regular = true), family, 2, CalendarInfo(false, true, 7), false, 0.0, 0))
        assertEquals(100, r.total)
    }
    @Test fun thresholdBoost_raisesTheBar() {
        val r = TripScorer.score(ScoreInput(Destination("가평", 60.0, PlaceKind.TOURIST), family, 0, sat, false, 0.0, 0), thresholdBoost = 10)
        assertEquals(75, r.total); assertEquals(Band.CANDIDATE, r.band)
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :engine:test --tests ai.yeoro.engine.TripScorerTest`
Expected: 컴파일 실패 (`ScoreInput` 없음)

- [ ] **Step 3: 구현**

`Model.kt`:
```kotlin
package ai.yeoro.engine

enum class Gear { P, R, N, D, UNKNOWN }
enum class Seat { DRIVER, PASSENGER, REAR_LEFT, REAR_CENTER, REAR_RIGHT;
    val rear: Boolean get() = this == REAR_LEFT || this == REAR_CENTER || this == REAR_RIGHT }
enum class PlaceKind { TOURIST, LODGING, CAMPING, WORK, OTHER }

data class Destination(
    val name: String, val distanceKm: Double, val kind: PlaceKind,
    val frequent: Boolean = false, val regular: Boolean = false, val visits: Int = 0,
)

data class CalendarInfo(val weekend: Boolean, val holiday: Boolean, val hour: Int) {
    val commuteHour: Boolean get() = !weekend && !holiday && (hour in 6..9 || hour in 17..19)
}

data class Snapshot(
    val timeMs: Long, val gear: Gear, val speedKmh: Float, val seats: Set<Seat>,
    val trunkOpen: Boolean, val wiperOn: Boolean, val openWindows: Int, val doorOpen: Boolean,
    val homeDistanceKm: Double,
)
```

`TripScorer.kt`:
```kotlin
package ai.yeoro.engine

data class ScoreInput(
    val destination: Destination?, val seats: Set<Seat>, val trunkOpens: Int, val calendar: CalendarInfo,
    val newRoute: Boolean, val homeDistanceKm: Double, val parkedAwayMin: Int,
)
data class ScoredSignal(val label: String, val points: Int, val source: String)
enum class Band { CONFIDENT, CANDIDATE, DAILY }

data class ScoreResult(val signals: List<ScoredSignal>, val thresholdBoost: Int = 0) {
    val total: Int get() = signals.sumOf { it.points }.coerceIn(0, 100)
    val band: Band get() = when {
        total >= 70 + thresholdBoost -> Band.CONFIDENT
        total >= 40 -> Band.CANDIDATE
        else -> Band.DAILY
    }
}

object TripScorer {
    fun score(input: ScoreInput, thresholdBoost: Int = 0): ScoreResult {
        val s = mutableListOf<ScoredSignal>()
        input.destination?.let { d ->
            val km = d.distanceKm.toInt()
            when {
                d.distanceKm >= 150 -> s += ScoredSignal("목적지 ${km}km", 35, "내비")
                d.distanceKm >= 50 -> s += ScoredSignal("목적지 ${km}km", 25, "내비")
            }
            when (d.kind) {
                PlaceKind.TOURIST, PlaceKind.LODGING, PlaceKind.CAMPING -> s += ScoredSignal("목적지 관광·숙박지", 20, "TourAPI")
                PlaceKind.WORK -> s += ScoredSignal("목적지 업무 장소", -20, "TourAPI")
                PlaceKind.OTHER -> Unit
            }
            if (d.frequent) s += ScoredSignal("자주 가는 곳", -40, "여로 기록")
            if (d.regular) s += ScoredSignal("정기 방문 장소", 30, "여로 기록")
        }
        val rear = input.seats.count { it.rear }
        when {
            rear >= 2 -> s += ScoredSignal("뒷좌석 ${rear}명", 20, "좌석")
            rear == 1 -> s += ScoredSignal("뒷좌석 1명", 10, "좌석")
        }
        if (input.seats.size <= 1) s += ScoredSignal("혼자 탑승", -15, "좌석")
        if (input.trunkOpens > 0) s += ScoredSignal("출발 전 트렁크 ${input.trunkOpens}회", 15, "트렁크")
        when {
            input.calendar.holiday -> s += ScoredSignal("연휴", 15, "천문연")
            input.calendar.weekend -> s += ScoredSignal("주말", 10, "천문연")
        }
        if (input.calendar.commuteHour) s += ScoredSignal("평일 출퇴근 시간", -10, "시계")
        if (input.newRoute) s += ScoredSignal("처음 가는 방향", 10, "여로 기록")
        if (input.homeDistanceKm >= 80) s += ScoredSignal("집에서 ${input.homeDistanceKm.toInt()}km", 15, "위치")
        if (input.parkedAwayMin >= 60) s += ScoredSignal("낯선 곳 P단 ${input.parkedAwayMin / 60}시간", 20, "기어·위치")
        return ScoreResult(s, thresholdBoost)
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :engine:test --tests ai.yeoro.engine.TripScorerTest`
Expected: 6 tests PASS (A=100, B=45→60→80, C=0, D=DAILY, E=100, boost=75 후보)

- [ ] **Step 5: 커밋**

```bash
git add engine && git commit -qm "feat(engine): trip scorer with situation table"
```

---

### Task 3: 순간 감지

**Files:**
- Create: `engine/src/main/kotlin/ai/yeoro/engine/MomentDetector.kt`
- Test: `engine/src/test/kotlin/ai/yeoro/engine/MomentDetectorTest.kt`

**Interfaces:**
- Consumes: `Snapshot`, `Seat`, `Gear` (Task 2)
- Produces:
  - `enum MomentType { RAIN_START, RAIN_STOP, WINDOWS_OPEN, STOP, COMPANION_JOINED }`
  - `data class Moment(type: MomentType, timeMs: Long, detail: String)`
  - `class MomentDetector { fun feed(s: Snapshot): List<Moment>; fun reset() }`

규칙:
- 비 시작: 와이퍼가 30분 이상 꺼져 있었고(첫 스냅샷 이전은 꺼진 것으로 본다) 3분 이상 연속 작동
- 비 그침: 비 시작 뒤 와이퍼가 10분 이상 연속으로 꺼짐
- 창문: 열린 창문 수가 3 미만에서 3 이상으로 바뀜
- 정차: P단에서 문이 한 번이라도 열렸고, P단이 10분 이상 이어진 뒤 P단을 벗어남. detail은 분 단위 시간
- 동행자 합류: 정차 뒤 출발할 때의 착석 수가 정차 전보다 많음

- [ ] **Step 1: 실패하는 테스트**

```kotlin
package ai.yeoro.engine
import org.junit.Assert.assertEquals
import org.junit.Test

class MomentDetectorTest {
    private val min = 60_000L
    private val four = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)
    private fun snap(t: Long, gear: Gear = Gear.D, wiper: Boolean = false, windows: Int = 0, door: Boolean = false, seats: Set<Seat> = four) =
        Snapshot(t * min, gear, if (gear == Gear.D) 90f else 0f, seats, false, wiper, windows, door, 50.0)

    @Test fun rainNeedsThreeMinutesAfterLongDryPeriod() {
        val d = MomentDetector()
        val out = (0L..40L).flatMap { d.feed(snap(it, wiper = it >= 35)) }
        assertEquals(listOf(MomentType.RAIN_START), out.map { it.type })
        assertEquals(38 * min, out.first().timeMs)
    }
    @Test fun shortWiperFlickIsIgnored() {
        val d = MomentDetector()
        val out = (0L..40L).flatMap { d.feed(snap(it, wiper = it in 35..36)) }
        assertEquals(emptyList<MomentType>(), out.map { it.type })
    }
    @Test fun rainStopsAfterTenDryMinutes() {
        val d = MomentDetector()
        val out = (0L..60L).flatMap { d.feed(snap(it, wiper = it in 31..40)) }
        assertEquals(listOf(MomentType.RAIN_START, MomentType.RAIN_STOP), out.map { it.type })
    }
    @Test fun threeWindowsOpenAtOnce() {
        val d = MomentDetector()
        val out = listOf(snap(0), snap(1, windows = 1), snap(2, windows = 4), snap(3, windows = 4)).flatMap(d::feed)
        assertEquals(listOf(MomentType.WINDOWS_OPEN), out.map { it.type })
    }
    @Test fun stopCountsOnlyIfTenMinutesWithDoor() {
        val d = MomentDetector()
        val seq = listOf(snap(0)) + (1L..38L).map { snap(it, Gear.P, door = it == 2L) } + snap(39)
        val out = seq.flatMap(d::feed)
        assertEquals(listOf(MomentType.STOP), out.map { it.type }); assertEquals("38", out.first().detail)
        val short = MomentDetector()
        val out2 = (listOf(snap(0)) + (1L..6L).map { snap(it, Gear.P, door = true) } + snap(7)).flatMap(short::feed)
        assertEquals(emptyList<MomentType>(), out2.map { it.type })
    }
    @Test fun companionJoinsAfterStop() {
        val d = MomentDetector()
        val five = four + Seat.REAR_CENTER
        val seq = listOf(snap(0)) + (1L..15L).map { snap(it, Gear.P, door = true) } + snap(16, seats = five)
        val out = seq.flatMap(d::feed).map { it.type }
        assertEquals(listOf(MomentType.STOP, MomentType.COMPANION_JOINED), out)
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :engine:test --tests ai.yeoro.engine.MomentDetectorTest` → 컴파일 실패

- [ ] **Step 3: 구현**

```kotlin
package ai.yeoro.engine

enum class MomentType { RAIN_START, RAIN_STOP, WINDOWS_OPEN, STOP, COMPANION_JOINED }
data class Moment(val type: MomentType, val timeMs: Long, val detail: String = "")

class MomentDetector {
    private val min = 60_000L
    private var wiperOnSince: Long? = null
    private var wiperOffSince: Long? = null
    private var dryBeforeOnMs = Long.MAX_VALUE
    private var raining = false
    private var lastWindows = 0
    private var parkedSince: Long? = null
    private var doorOpenedWhileParked = false
    private var seatsBeforeStop = 0
    private var started = false

    fun reset() { wiperOnSince = null; wiperOffSince = null; dryBeforeOnMs = Long.MAX_VALUE; raining = false; lastWindows = 0; parkedSince = null; doorOpenedWhileParked = false; seatsBeforeStop = 0; started = false }

    fun feed(s: Snapshot): List<Moment> {
        val out = mutableListOf<Moment>()
        if (!started) { started = true; if (!s.wiperOn) wiperOffSince = Long.MIN_VALUE / 2; lastWindows = s.openWindows }
        // 비
        if (s.wiperOn) {
            if (wiperOnSince == null) { dryBeforeOnMs = wiperOffSince?.let { s.timeMs - it } ?: 0L; wiperOnSince = s.timeMs; wiperOffSince = null }
            if (!raining && dryBeforeOnMs >= 30 * min && s.timeMs - wiperOnSince!! >= 3 * min) { raining = true; out += Moment(MomentType.RAIN_START, s.timeMs) }
        } else {
            if (wiperOffSince == null) { wiperOffSince = s.timeMs; wiperOnSince = null }
            if (raining && s.timeMs - wiperOffSince!! >= 10 * min) { raining = false; out += Moment(MomentType.RAIN_STOP, s.timeMs) }
        }
        // 창문
        if (lastWindows < 3 && s.openWindows >= 3) out += Moment(MomentType.WINDOWS_OPEN, s.timeMs, s.openWindows.toString())
        lastWindows = s.openWindows
        // 정차와 동행자
        if (s.gear == Gear.P) {
            if (parkedSince == null) { parkedSince = s.timeMs; doorOpenedWhileParked = false }
            if (s.doorOpen) doorOpenedWhileParked = true
        } else if (parkedSince != null) {
            val mins = ((s.timeMs - parkedSince!!) / min).toInt()
            if (doorOpenedWhileParked && mins >= 10) {
                out += Moment(MomentType.STOP, parkedSince!!, mins.toString())
                if (seatsBeforeStop in 1 until s.seats.size) out += Moment(MomentType.COMPANION_JOINED, s.timeMs, "${seatsBeforeStop}→${s.seats.size}")
            }
            parkedSince = null
        }
        if (s.gear != Gear.P) seatsBeforeStop = s.seats.size
        return out
    }
}
```

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :engine:test --tests ai.yeoro.engine.MomentDetectorTest` → 6 PASS

- [ ] **Step 5: 커밋** — `git add engine && git commit -qm "feat(engine): moment detector rules"`

---

### Task 4: 알림 정책과 여행 상태 엔진, 상황 재연 테스트

**Files:**
- Create: `engine/src/main/kotlin/ai/yeoro/engine/NotificationPolicy.kt`, `engine/src/main/kotlin/ai/yeoro/engine/TripEngine.kt`
- Test: `engine/src/test/kotlin/ai/yeoro/engine/NotificationPolicyTest.kt`, `engine/src/test/kotlin/ai/yeoro/engine/ScenarioTest.kt`

**Interfaces:**
- Consumes: Task 2·3 전부
- Produces:
  - `class NotificationPolicy { fun canPropose(dayKey: String, gear: Gear): Boolean; fun recordProposal(dayKey: String); fun recordDecline(kind: String); fun boostFor(kind: String): Int }`
  - `enum TripState { OFF, DAILY, CANDIDATE, TRIP, STAYING, ALBUM_READY }`
  - `sealed interface Event`: `ProfileChanged(consented: Boolean)`, `DestinationSet(destination: Destination?)`, `Tick(snapshot: Snapshot, calendar: CalendarInfo, dayKey: String, newRoute: Boolean)`, `Answer(accepted: Boolean)`
  - `sealed interface Action`: `AskBeforeDeparture(score: ScoreResult)`, `AskRetroactive(score: ScoreResult, sinceMs: Long)`, `StartTrip(sinceMs: Long, retroactive: Boolean)`, `RecordMoment(moment: Moment)`, `TripClosed(endMs: Long, notifyOnPhoneAtHour: Int?)`, `Discard`, `ScoreUpdated(score: ScoreResult, state: TripState)`
  - `class TripEngine(policy: NotificationPolicy = NotificationPolicy()) { val state: TripState; fun handle(e: Event): List<Action> }`

동작:
- OFF(동의 없음): 모든 이벤트에 빈 목록
- 출발 전 P: 트렁크가 닫힘→열림으로 바뀔 때마다 횟수를 센다. 점수가 확신이고 정책이 허락하면 `AskBeforeDeparture`를 한 번 낸다.
- 출발(P→D): 수락했으면 TRIP + `StartTrip(retroactive=false)`, 거절했으면 DAILY. 묻지 않았거나 답이 없고 점수가 40 이상이면 CANDIDATE, 아니면 DAILY
- CANDIDATE: 집에서 3km 넘게 떨어진 곳의 P단 시간을 분으로 센다. 점수가 확신이 되고 P단이며 정책이 허락하면 `AskRetroactive(sinceMs=출발 시각)`. 수락하면 TRIP + `StartTrip(retroactive=true)`, 거절하면 `Discard` + DAILY
- TRIP/STAYING: 감지기 결과를 `RecordMoment`로 낸다.
  - 집에서 3km 넘는 곳에서 20~6시 사이 P단 4시간 이상이면 STAYING. 다음 출발 때 TRIP으로 돌아가고 새로 묻지 않는다.
  - 집 1km 안에서 P단 120분이면 `TripClosed`. 도착이 22시 이후이면 `notifyOnPhoneAtHour=8`이고, 상태는 ALBUM_READY
- 모든 Tick에서 `ScoreUpdated`를 낸다(디버그 화면용).

- [ ] **Step 1: 정책 테스트**

```kotlin
package ai.yeoro.engine
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun onlyInParkAndOncePerDay() {
        val p = NotificationPolicy()
        assertFalse(p.canPropose("2026-10-24", Gear.D))
        assertTrue(p.canPropose("2026-10-24", Gear.P))
        p.recordProposal("2026-10-24")
        assertFalse(p.canPropose("2026-10-24", Gear.P))
        assertTrue(p.canPropose("2026-10-25", Gear.P))
    }
    @Test fun threeDeclinesRaiseThreshold() {
        val p = NotificationPolicy()
        repeat(2) { p.recordDecline("departure") }; assertEquals(0, p.boostFor("departure"))
        p.recordDecline("departure"); assertEquals(10, p.boostFor("departure"))
    }
}
```

- [ ] **Step 2: 상황 재연 테스트** (`ScenarioTest.kt`)

```kotlin
package ai.yeoro.engine
import org.junit.Assert.*
import org.junit.Test

class ScenarioTest {
    private val min = 60_000L
    private val family = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_RIGHT)
    private val sat = CalendarInfo(weekend = true, holiday = false, hour = 8)
    private fun tick(t: Long, gear: Gear, seats: Set<Seat> = family, trunk: Boolean = false, home: Double = 0.0, cal: CalendarInfo = sat, door: Boolean = false, wiper: Boolean = false, day: String = "d1", newRoute: Boolean = false) =
        Event.Tick(Snapshot(t * min, gear, if (gear == Gear.D) 90f else 0f, seats, trunk, wiper, 0, door, home), cal, day, newRoute)
    private fun List<Action>.has(c: Class<*>) = any { c.isInstance(it) }

    @Test fun A_plannedTrip_asksBeforeDeparture_thenStarts() {
        val e = TripEngine()
        e.handle(tick(0, Gear.P)); e.handle(tick(1, Gear.P, trunk = true)); e.handle(tick(2, Gear.P))
        e.handle(tick(3, Gear.P, trunk = true)); e.handle(tick(4, Gear.P))
        val ask = e.handle(Event.DestinationSet(Destination("강릉 경포해변", 238.0, PlaceKind.TOURIST)))
        assertTrue(ask.has(Action.AskBeforeDeparture::class.java))
        e.handle(Event.Answer(true))
        val go = e.handle(tick(6, Gear.D, home = 2.0))
        assertTrue(go.has(Action.StartTrip::class.java)); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun B_spontaneous_silentThenRetroactiveAsk() {
        val e = TripEngine(); val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        val go = e.handle(tick(3, Gear.D, three, home = 1.0, newRoute = true))
        assertFalse(go.has(Action.AskBeforeDeparture::class.java)); assertEquals(TripState.CANDIDATE, e.state)
        (4L..80L).forEach { e.handle(tick(it, Gear.D, three, home = 86.0, newRoute = true)) }
        val asks = (81L..145L).flatMap { e.handle(tick(it, Gear.P, three, home = 86.0, newRoute = true)) }
        val ask = asks.filterIsInstance<Action.AskRetroactive>().single()
        assertEquals(3 * min, ask.sinceMs)
        val acc = e.handle(Event.Answer(true))
        assertTrue(acc.any { it is Action.StartTrip && it.retroactive }); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun B_declined_discards() {
        val e = TripEngine(); val three = setOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT)
        e.handle(tick(0, Gear.P, three)); e.handle(tick(1, Gear.P, three, trunk = true)); e.handle(tick(2, Gear.P, three))
        e.handle(tick(3, Gear.D, three, newRoute = true)); (4L..80L).forEach { e.handle(tick(it, Gear.D, three, home = 86.0, newRoute = true)) }
        (81L..145L).forEach { e.handle(tick(it, Gear.P, three, home = 86.0, newRoute = true)) }
        assertTrue(e.handle(Event.Answer(false)).has(Action.Discard::class.java)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun C_commute_neverAsks() {
        val e = TripEngine(); val solo = setOf(Seat.DRIVER); val tue = CalendarInfo(false, false, 7)
        e.handle(tick(0, Gear.P, solo, cal = tue))
        val a = e.handle(Event.DestinationSet(Destination("회사", 9.0, PlaceKind.WORK, frequent = true)))
        val b = (1L..30L).flatMap { e.handle(tick(it, Gear.D, solo, cal = tue)) }
        assertFalse((a + b).has(Action.AskBeforeDeparture::class.java)); assertEquals(TripState.DAILY, e.state)
    }
    @Test fun F_overnightStay_continuesWithoutAsking() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D))
        val night = CalendarInfo(true, false, 22)
        (2L..250L).forEach { e.handle(tick(it, Gear.P, home = 238.0, cal = night)) }
        assertEquals(TripState.STAYING, e.state)
        val next = e.handle(tick(251, Gear.D, home = 238.0, cal = CalendarInfo(false, false, 9), day = "d2"))
        assertFalse(next.has(Action.AskBeforeDeparture::class.java)); assertEquals(TripState.TRIP, e.state)
    }
    @Test fun J_lateReturn_notifiesOnPhoneNextMorning() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true)); e.handle(tick(1, Gear.D, home = 10.0))
        val late = CalendarInfo(false, false, 22)
        val out = (2L..125L).flatMap { e.handle(tick(it, Gear.P, home = 0.2, cal = late)) }
        val closed = out.filterIsInstance<Action.TripClosed>().single()
        assertEquals(8, closed.notifyOnPhoneAtHour); assertEquals(TripState.ALBUM_READY, e.state)
    }
    @Test fun L_guestProfile_doesNothing() {
        val e = TripEngine(); e.handle(Event.ProfileChanged(consented = false))
        val out = e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))) + e.handle(tick(0, Gear.P))
        assertTrue(out.isEmpty()); assertEquals(TripState.OFF, e.state)
    }
    @Test fun I_rainDuringTrip_isRecorded() {
        val e = TripEngine(); e.handle(tick(0, Gear.P)); e.handle(Event.DestinationSet(Destination("강릉", 238.0, PlaceKind.TOURIST))); e.handle(Event.Answer(true))
        val out = (1L..45L).flatMap { e.handle(tick(it, Gear.D, home = 50.0, wiper = it >= 40)) }
        assertTrue(out.any { it is Action.RecordMoment && it.moment.type == MomentType.RAIN_START })
    }
}
```

- [ ] **Step 3: 실패 확인** — Run: `./gradlew :engine:test` → 컴파일 실패

- [ ] **Step 4: 구현**

`NotificationPolicy.kt`:
```kotlin
package ai.yeoro.engine

class NotificationPolicy {
    private var lastProposalDay: String? = null
    private val declines = mutableMapOf<String, Int>()
    fun canPropose(dayKey: String, gear: Gear): Boolean = gear == Gear.P && lastProposalDay != dayKey
    fun recordProposal(dayKey: String) { lastProposalDay = dayKey }
    fun recordDecline(kind: String) { declines[kind] = (declines[kind] ?: 0) + 1 }
    fun boostFor(kind: String): Int = if ((declines[kind] ?: 0) >= 3) 10 else 0
}
```

`TripEngine.kt`:
```kotlin
package ai.yeoro.engine

enum class TripState { OFF, DAILY, CANDIDATE, TRIP, STAYING, ALBUM_READY }

sealed interface Event {
    data class ProfileChanged(val consented: Boolean) : Event
    data class DestinationSet(val destination: Destination?) : Event
    data class Tick(val snapshot: Snapshot, val calendar: CalendarInfo, val dayKey: String, val newRoute: Boolean = false) : Event
    data class Answer(val accepted: Boolean) : Event
}

sealed interface Action {
    data class AskBeforeDeparture(val score: ScoreResult) : Action
    data class AskRetroactive(val score: ScoreResult, val sinceMs: Long) : Action
    data class StartTrip(val sinceMs: Long, val retroactive: Boolean) : Action
    data class RecordMoment(val moment: Moment) : Action
    data class TripClosed(val endMs: Long, val notifyOnPhoneAtHour: Int?) : Action
    data object Discard : Action
    data class ScoreUpdated(val score: ScoreResult, val state: TripState) : Action
}

class TripEngine(private val policy: NotificationPolicy = NotificationPolicy()) {
    private val min = 60_000L
    var state: TripState = TripState.DAILY; private set
    private var consented = true
    private var destination: Destination? = null
    private var trunkOpens = 0
    private var lastTrunk = false
    private var pending: String? = null          // "departure" | "retro"
    private var accepted: Boolean? = null
    private var departedAt: Long? = null
    private var awayParkSince: Long? = null
    private var homeParkSince: Long? = null
    private var last: Event.Tick? = null
    private val detector = MomentDetector()

    fun handle(e: Event): List<Action> {
        if (e is Event.ProfileChanged) { consented = e.consented; state = if (consented) TripState.DAILY else TripState.OFF; return emptyList() }
        if (!consented) return emptyList()
        return when (e) {
            is Event.DestinationSet -> { destination = e.destination; last?.let { maybeAskBeforeDeparture(it) } ?: emptyList() }
            is Event.Answer -> answer(e.accepted)
            is Event.Tick -> tick(e)
            is Event.ProfileChanged -> emptyList()
        }
    }

    private fun scoreOf(t: Event.Tick, kind: String): ScoreResult {
        val s = t.snapshot
        val parkedAway = awayParkSince?.let { ((s.timeMs - it) / min).toInt() } ?: 0
        return TripScorer.score(ScoreInput(destination, s.seats, trunkOpens, t.calendar, t.newRoute, s.homeDistanceKm, parkedAway), policy.boostFor(kind))
    }

    private fun maybeAskBeforeDeparture(t: Event.Tick): List<Action> {
        if (departedAt != null || pending != null || accepted != null) return emptyList()
        val sc = scoreOf(t, "departure")
        if (sc.band == Band.CONFIDENT && policy.canPropose(t.dayKey, t.snapshot.gear)) {
            policy.recordProposal(t.dayKey); pending = "departure"; return listOf(Action.AskBeforeDeparture(sc))
        }
        return emptyList()
    }

    private fun answer(yes: Boolean): List<Action> {
        val kind = pending ?: return emptyList(); pending = null
        if (!yes) policy.recordDecline(kind)
        return when (kind) {
            "departure" -> { accepted = yes; emptyList() }
            else -> if (yes) { state = TripState.TRIP; listOf(Action.StartTrip(departedAt ?: 0L, retroactive = true)) }
                    else { state = TripState.DAILY; listOf(Action.Discard) }
        }
    }

    private fun tick(t: Event.Tick): List<Action> {
        val out = mutableListOf<Action>()
        val s = t.snapshot; val prev = last?.snapshot; last = t
        // 출발 전 트렁크
        if (departedAt == null && s.trunkOpen && !lastTrunk) trunkOpens++
        lastTrunk = s.trunkOpen
        if (departedAt == null && s.gear == Gear.P) out += maybeAskBeforeDeparture(t)
        // 출발 또는 숙박 후 재출발
        val leavingPark = prev != null && prev.gear == Gear.P && s.gear != Gear.P
        if (leavingPark && departedAt == null) {
            departedAt = s.timeMs
            val sc = scoreOf(t, "departure")
            state = when {
                accepted == true -> TripState.TRIP.also { out += Action.StartTrip(s.timeMs, false) }
                accepted == false -> TripState.DAILY
                sc.band != Band.DAILY -> TripState.CANDIDATE
                else -> TripState.DAILY
            }
            pending = null
        } else if (leavingPark && state == TripState.STAYING) state = TripState.TRIP
        // P단 시간 계산
        if (s.gear == Gear.P && s.homeDistanceKm > 3) { if (awayParkSince == null) awayParkSince = s.timeMs } else awayParkSince = null
        if (s.gear == Gear.P && s.homeDistanceKm <= 1) { if (homeParkSince == null) homeParkSince = s.timeMs } else homeParkSince = null
        // 순간
        if (state == TripState.TRIP || state == TripState.STAYING || state == TripState.CANDIDATE) detector.feed(s).forEach { out += Action.RecordMoment(it) }
        // 후보 → 소급 질문
        val sc = scoreOf(t, "retro")
        if (state == TripState.CANDIDATE && pending == null && sc.band == Band.CONFIDENT && policy.canPropose(t.dayKey, s.gear)) {
            policy.recordProposal(t.dayKey); pending = "retro"; out += Action.AskRetroactive(sc, departedAt ?: s.timeMs)
        }
        // 숙박
        val night = t.calendar.hour >= 20 || t.calendar.hour < 6
        if (state == TripState.TRIP && night && awayParkSince?.let { s.timeMs - it >= 240 * min } == true) state = TripState.STAYING
        // 귀가
        if ((state == TripState.TRIP || state == TripState.STAYING) && homeParkSince?.let { s.timeMs - it >= 120 * min } == true) {
            state = TripState.ALBUM_READY
            out += Action.TripClosed(s.timeMs, if (t.calendar.hour >= 22 || t.calendar.hour < 6) 8 else null)
        }
        out += Action.ScoreUpdated(sc, state)
        return out
    }
}
```

- [ ] **Step 5: 통과 확인** — Run: `./gradlew :engine:test` → 전부 PASS (Scorer 6, Detector 6, Policy 2, Scenario 8). 실패하면 테스트 기대값이 아니라 엔진 규칙 쪽을 이 계획의 "동작" 목록에 맞게 고친다.

- [ ] **Step 6: 커밋** — `git add engine && git commit -qm "feat(engine): trip state engine, notification policy, scenario tests A-L" && git push`

---

### Task 5: 차량·내비 신호 어댑터

**Files:**
- Create: `app/src/main/java/ai/yeoro/vehicle/VehicleSource.kt`, `app/src/main/java/ai/yeoro/vehicle/NaviSource.kt`
- Modify: `app/src/main/AndroidManifest.xml` (권한)

**Interfaces:**
- Consumes: 엔진 `Snapshot`, `Gear`, `Seat`, `Destination`, `PlaceKind`
- Produces:
  - `class VehicleSource(context: Context) { val state: StateFlow<RawVehicle>; fun start(); fun stop() }`
  - `data class RawVehicle(gear: Gear, speedKmh: Float, seats: Set<Seat>, trunkOpen: Boolean, wiperOn: Boolean, openWindows: Int, doorOpen: Boolean)`
  - `class NaviSource(context: Context) { val destination: StateFlow<NaviDestination?>; val location: StateFlow<Pair<Double, Double>?>; fun start(); fun stop() }`
  - `data class NaviDestination(name: String, lat: Double, lon: Double, remainMeters: Int)`

SDK 매핑(직접 확인한 시그니처):
- 기어: `vehicle.getGear().registerGearSelection(GearSelectionListener { onSelected(VehicleGear?) ; onFailed })`
- 좌석: `getSeat().registerSeatOccupancy(SeatArea(SeatValue.X), SeatOccupancyListener { onSeatOccupancyUpdated(Boolean?) })` (ROW_1_LEFT/RIGHT, ROW_2_LEFT/CENTER/RIGHT)
- 트렁크: `getDoor().registerDoorPosition(DoorArea(DoorValue.REAR), DoorPositionListener { onPositionUpdated(Int?) })`, 0보다 크면 열림
- 도어: `DoorValue.ROW_1_LEFT..ROW_2_RIGHT` 같은 리스너, 하나라도 0보다 크면 `doorOpen`
- 와이퍼: `getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), WiperStateListener { onWiperStateUpdated(WiperState) })`, ON이면 true
- 창문: `getWindow().registerWindowPosition(WindowArea(WindowValue.ROW_1_LEFT..ROW_2_RIGHT), WindowPositionListener { onWindowPositionUpdated(Int?) })`, 0보다 크면 열림
- 속도: `CarPropertyManager.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, SENSOR_RATE_ONCHANGE)`. m/s를 받아 3.6을 곱한다.
- 내비: `NaviHelper(context).initialize({ addListener(listener); getDestinationInfo() }, {})`
  - `onDestinationInfo(DestinationInfo)`에서 name, latitude, longitude, remainDistance를 쓴다.
  - `onDestinationChanged`에서 `getDestinationInfo()`를 다시 부른다.
  - `onCurrentLocationInfo(CurrentLocationInfo)`에서 latitude, longitude를 쓴다. 30초마다 `getCurrentLocationInfo()`로 폴링한다.
  - `onDestinationArrived`에서 destination을 null로 둔다.

- [ ] **Step 1: 매니페스트 권한 추가**

```xml
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />
<uses-permission android:name="pleos.car.permission.CAR_SEATS" />
<uses-permission android:name="pleos.car.permission.CAR_DOORS" />
<uses-permission android:name="pleos.car.permission.CAR_WINDOWS" />
<uses-permission android:name="pleos.car.permission.CAR_INFO" />
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />
<uses-permission android:name="android.car.permission.CAR_SPEED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
(와이퍼 권한 상수가 `CAR_DRIVE`가 아닐 경우 Step 4 로그의 `ERROR_SECURITY_PERMISSION`으로 확인하고 `PleosPermission` 상수 목록 중 맞는 것을 추가한다: `CAR_LIGHTS` 또는 `CAR_SAFETY` 후보)

- [ ] **Step 2: VehicleSource 구현**

```kotlin
package ai.yeoro.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.*
import ai.yeoro.engine.Gear
import ai.yeoro.engine.Seat
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class RawVehicle(
    val gear: Gear = Gear.UNKNOWN, val speedKmh: Float = 0f, val seats: Set<Seat> = emptySet(),
    val trunkOpen: Boolean = false, val wiperOn: Boolean = false, val openWindows: Int = 0, val doorOpen: Boolean = false,
)

class VehicleSource(private val context: Context) {
    private val tag = "YeoroVehicle"
    private val _state = MutableStateFlow(RawVehicle())
    val state: StateFlow<RawVehicle> = _state
    private lateinit var vehicle: Vehicle
    private var car: Car? = null
    private val windows = mutableMapOf<WindowValue, Boolean>()
    private val doors = mutableMapOf<DoorValue, Boolean>()
    private val cleanups = mutableListOf<() -> Unit>()

    private fun fail(what: String) = { e: Exception -> Log.w(tag, "$what 실패", e) }

    fun start() {
        vehicle = Vehicle(context.applicationContext); vehicle.initialize()
        val gearL = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { _state.update { it.copy(gear = gear.toGear()) } }
            override fun onFailed(e: Exception) { fail("기어")(e) }
        }
        vehicle.getGear().registerGearSelection(gearL); cleanups += { vehicle.getGear().unregisterGearSelection(gearL) }

        val seatMap = mapOf(SeatValue.ROW_1_LEFT to Seat.DRIVER, SeatValue.ROW_1_RIGHT to Seat.PASSENGER,
            SeatValue.ROW_2_LEFT to Seat.REAR_LEFT, SeatValue.ROW_2_CENTER to Seat.REAR_CENTER, SeatValue.ROW_2_RIGHT to Seat.REAR_RIGHT)
        seatMap.forEach { (v, seat) ->
            val area = SeatArea(v)
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) { _state.update { if (value == true) it.copy(seats = it.seats + seat) else it.copy(seats = it.seats - seat) } }
                override fun onFailed(e: Exception) { fail("좌석 $v")(e) }
            }
            vehicle.getSeat().registerSeatOccupancy(area, l); cleanups += { vehicle.getSeat().unregisterSeatOccupancy(area, l) }
        }

        (listOf(DoorValue.REAR) + listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)).forEach { v ->
            val area = DoorArea(v)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(value: Int?) {
                    val open = (value ?: 0) > 0
                    if (v == DoorValue.REAR) _state.update { it.copy(trunkOpen = open) }
                    else { doors[v] = open; _state.update { it.copy(doorOpen = doors.values.any { o -> o }) } }
                }
                override fun onFailed(e: Exception) { fail("도어 $v")(e) }
            }
            vehicle.getDoor().registerDoorPosition(area, l); cleanups += { vehicle.getDoor().unregisterDoorPosition(area, l) }
        }

        val wArea = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiperL = object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState) { _state.update { it.copy(wiperOn = value == WiperState.ON) } }
            override fun onFailed(e: Exception) { fail("와이퍼")(e) }
        }
        vehicle.getWiper().registerWiperState(wArea, wiperL); cleanups += { vehicle.getWiper().unregisterWiperState(wArea, wiperL) }

        listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).forEach { v ->
            val area = WindowArea(v)
            val l = object : WindowPositionListener {
                override fun onWindowPositionUpdated(value: Int?) { windows[v] = (value ?: 0) > 0; _state.update { it.copy(openWindows = windows.values.count { o -> o }) } }
                override fun onFailed(e: Exception) { fail("창문 $v")(e) }
            }
            vehicle.getWindow().registerWindowPosition(area, l); cleanups += { vehicle.getWindow().unregisterWindowPosition(area, l) }
        }

        car = Car.createCar(context)
        val pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) { (v.value as? Float)?.let { mps -> _state.update { it.copy(speedKmh = mps * 3.6f) } } }
            override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(tag, "속도 오류 $propertyId") }
        }
        runCatching { pm?.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
            .onFailure { Log.w(tag, "속도 구독 실패(권한?)", it) }
        cleanups += { pm?.unregisterCallback(speedCb) }
    }

    fun stop() { cleanups.forEach { runCatching { it() } }; cleanups.clear(); runCatching { vehicle.release() }; car?.disconnect() }
}

private fun VehicleGear?.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.P; VehicleGear.GEAR_REVERSE -> Gear.R; VehicleGear.GEAR_NEUTRAL -> Gear.N
    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.D
}
```

- [ ] **Step 3: NaviSource 구현**

```kotlin
package ai.yeoro.vehicle

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NaviDestination(val name: String, val lat: Double, val lon: Double, val remainMeters: Int)

class NaviSource(private val context: Context) {
    private val _destination = MutableStateFlow<NaviDestination?>(null)
    val destination: StateFlow<NaviDestination?> = _destination
    private val _location = MutableStateFlow<Pair<Double, Double>?>(null)
    val location: StateFlow<Pair<Double, Double>?> = _location
    private lateinit var navi: NaviHelper
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable { override fun run() { runCatching { navi.getCurrentLocationInfo() }; handler.postDelayed(this, 30_000) } }

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) { _destination.value = NaviDestination(info.name, info.latitude, info.longitude, info.remainDistance) }
        override fun onDestinationChanged() { navi.getDestinationInfo() }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { _location.value = info.latitude to info.longitude }
        override fun onDestinationArrived(info: DestinationArrivedInfo) { _destination.value = null }
        override fun onRouteCancelled() { _destination.value = null }
    }

    fun start() {
        navi = NaviHelper(context.applicationContext)
        navi.initialize({ navi.addListener(listener); navi.getDestinationInfo(); handler.post(poll) }, { Log.w("YeoroNavi", "NaviHelper 초기화 실패", it) })
    }
    fun stop() { handler.removeCallbacks(poll); runCatching { navi.removeListener(listener); navi.release() } }
}
```

- [ ] **Step 4: 에뮬레이터 신호 확인 (임시 로그)**

`ai.yeoro.YeoroApp`(Task 7에서 정식으로 쓴다)을 만들기 전에 `MainActivity`에서 임시로 확인한다. Task 8의 MainActivity가 생기기 전이라면 아래 임시 액티비티를 쓰고, 확인한 뒤 지운다.
```kotlin
// app/src/main/java/ai/yeoro/ProbeActivity.kt (임시)
package ai.yeoro
import ai.yeoro.vehicle.NaviSource
import ai.yeoro.vehicle.VehicleSource
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
class ProbeActivity : ComponentActivity() {
    private val v by lazy { VehicleSource(this) }; private val n by lazy { NaviSource(this) }
    override fun onCreate(b: Bundle?) { super.onCreate(b); v.start(); n.start()
        lifecycleScope.launch { v.state.collect { Log.i("YeoroProbe", "$it") } }
        lifecycleScope.launch { n.destination.collect { Log.i("YeoroProbe", "dest=$it") } } }
    override fun onDestroy() { v.stop(); n.stop(); super.onDestroy() }
}
```
매니페스트 application 안에 `<activity android:name=".ProbeActivity" android:exported="true"><meta-data android:name="distractionOptimized" android:value="true"/><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>`를 넣는다.

Run:
```bash
./gradlew :app:installDebug -q && adb shell pm grant ai.yeoro android.car.permission.CAR_SPEED; adb shell am start -n ai.yeoro/.ProbeActivity; adb logcat -c
scripts/vc.sh gear Drive; scripts/vc.sh speed 80; scripts/vc.sh door fl open; sleep 3; adb logcat -d -s YeoroProbe YeoroVehicle | tail -20
```
Expected: `RawVehicle(gear=D, speedKmh≈80, …, doorOpen=true)`. 좌석·트렁크·와이퍼·창문은 Virtual Console의 `seat-occupancy`, `trunk-position`, `windshield-wipers-switch`, `window-position` 카드를 HTTP API로 바꿔 확인한다(`curl -s 127.0.0.1:8088/vehicle/catalog`로 write_endpoint와 필드를 찾아 `vc.sh`에 명령을 추가). 실패하는 신호는 `YeoroVehicle` 경고 로그로 권한 문제인지 확인하고, 확인 결과를 `README.md`의 "신호 확인 결과" 표에 기록한다.

- [ ] **Step 5: 커밋** — `git add app scripts README.md && git commit -qm "feat(app): vehicle and navi signal adapters" && git push`

---

### Task 6: 저장소(Room)와 24시간 삭제

**Files:**
- Create: `app/src/main/java/ai/yeoro/data/YeoroDb.kt`

**Interfaces:**
- Produces:
  - `@Entity TripEntity(id: Long, startMs: Long, endMs: Long?, retroactive: Boolean, destination: String?, state: String)`
  - `@Entity MomentEntity(id: Long, tripId: Long?, timeMs: Long, type: String, detail: String)` (tripId null = 일상 버퍼)
  - `@Dao YeoroDao`: `insertTrip(t): Long`, `updateTrip(t)`, `insertMoment(m)`, `attachBuffer(tripId, sinceMs)`, `purgeBuffer(beforeMs)`, `deleteBuffer()`, `trips(): Flow<List<TripEntity>>`, `moments(tripId): Flow<List<MomentEntity>>`, `openTrip(): TripEntity?`
  - `YeoroDb.get(context): YeoroDb`

- [ ] **Step 1: 구현**

```kotlin
package ai.yeoro.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startMs: Long, val endMs: Long? = null,
                      val retroactive: Boolean = false, val destination: String? = null, val state: String = "TRIP")

@Entity(tableName = "moments", indices = [Index("tripId")])
data class MomentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val tripId: Long?, val timeMs: Long, val type: String, val detail: String)

@Dao
interface YeoroDao {
    @Insert suspend fun insertTrip(t: TripEntity): Long
    @Update suspend fun updateTrip(t: TripEntity)
    @Insert suspend fun insertMoment(m: MomentEntity)
    @Query("UPDATE moments SET tripId = :tripId WHERE tripId IS NULL AND timeMs >= :sinceMs") suspend fun attachBuffer(tripId: Long, sinceMs: Long)
    @Query("DELETE FROM moments WHERE tripId IS NULL AND timeMs < :beforeMs") suspend fun purgeBuffer(beforeMs: Long)
    @Query("DELETE FROM moments WHERE tripId IS NULL") suspend fun deleteBuffer()
    @Query("SELECT * FROM trips ORDER BY startMs DESC") fun trips(): Flow<List<TripEntity>>
    @Query("SELECT * FROM moments WHERE tripId = :tripId ORDER BY timeMs") fun moments(tripId: Long): Flow<List<MomentEntity>>
    @Query("SELECT * FROM trips WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1") suspend fun openTrip(): TripEntity?
}

@Database(entities = [TripEntity::class, MomentEntity::class], version = 1, exportSchema = false)
abstract class YeoroDb : RoomDatabase() {
    abstract fun dao(): YeoroDao
    companion object {
        @Volatile private var inst: YeoroDb? = null
        fun get(c: Context): YeoroDb = inst ?: synchronized(this) { inst ?: Room.databaseBuilder(c.applicationContext, YeoroDb::class.java, "yeoro.db").build().also { inst = it } }
    }
}
```

- [ ] **Step 2: 빌드 확인** — Run: `./gradlew :app:assembleDebug -q` → 성공(Room KSP 생성 포함)

- [ ] **Step 3: 커밋** — `git add app && git commit -qm "feat(app): room storage for trips and moments" && git push`

---

### Task 7: 기록 서비스와 P단 알림

**Files:**
- Create: `app/src/main/java/ai/yeoro/YeoroApp.kt`, `app/src/main/java/ai/yeoro/service/RecorderService.kt`, `app/src/main/java/ai/yeoro/service/Notifier.kt`, `app/src/main/java/ai/yeoro/service/AnswerReceiver.kt`, `app/src/main/java/ai/yeoro/geo/Geo.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `VehicleSource`, `NaviSource`, `YeoroDb`, 엔진 전체
- Produces:
  - `object EngineBus { val engine: TripEngine; val lastScore: MutableStateFlow<Action.ScoreUpdated?>; val home: MutableStateFlow<Pair<Double,Double>?>; fun answer(accepted: Boolean) }`
  - `RecorderService`: 1초마다 Tick을 엔진에 넣고 Action을 처리한다.
  - `Notifier.ask(context, kind, title, body)`: 수락·거절 버튼이 있는 알림. 버튼은 `AnswerReceiver`로 간다.

- [ ] **Step 1: 거리 계산과 버스**

`geo/Geo.kt`:
```kotlin
package ai.yeoro.geo
import kotlin.math.*
fun haversineKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6371.0; val dLat = Math.toRadians(b.first - a.first); val dLon = Math.toRadians(b.second - a.second)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
```

`YeoroApp.kt`:
```kotlin
package ai.yeoro
import ai.yeoro.engine.Action
import ai.yeoro.engine.Event
import ai.yeoro.engine.TripEngine
import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

object EngineBus {
    val engine = TripEngine()
    val lastScore = MutableStateFlow<Action.ScoreUpdated?>(null)
    val home = MutableStateFlow<Pair<Double, Double>?>(null)
    val answers = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    fun answer(accepted: Boolean) { answers.tryEmit(accepted) }
}
class YeoroApp : Application()
```

- [ ] **Step 2: 알림**

`service/Notifier.kt`:
```kotlin
package ai.yeoro.service
import ai.yeoro.R
import android.app.*
import android.content.Context
import android.content.Intent

object Notifier {
    const val CH_ASK = "yeoro_ask"; const val CH_REC = "yeoro_rec"; const val ID_ASK = 11; const val ID_REC = 10
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "여행 제안", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REC, "기록 상태", NotificationManager.IMPORTANCE_MIN))
    }
    fun recording(c: Context, text: String): Notification = Notification.Builder(c, CH_REC)
        .setSmallIcon(R.drawable.ic_yeoro).setContentTitle("여로").setContentText(text).setOngoing(true).build()
    fun ask(c: Context, title: String, body: String, yes: String, no: String) {
        fun pi(accepted: Boolean) = PendingIntent.getBroadcast(c, if (accepted) 1 else 2,
            Intent(c, AnswerReceiver::class.java).putExtra("accepted", accepted), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CH_ASK).setSmallIcon(R.drawable.ic_yeoro).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setCategory(Notification.CATEGORY_RECOMMENDATION).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, no, pi(false)).build())
            .addAction(Notification.Action.Builder(null, yes, pi(true)).build()).build()
        c.getSystemService(NotificationManager::class.java).notify(ID_ASK, n)
    }
}
```

`service/AnswerReceiver.kt`:
```kotlin
package ai.yeoro.service
import ai.yeoro.EngineBus
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        EngineBus.answer(i.getBooleanExtra("accepted", false))
        c.getSystemService(NotificationManager::class.java).cancel(Notifier.ID_ASK)
    }
}
```

`res/drawable/ic_yeoro.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
  <path android:fillColor="#FFFFFFFF" android:pathData="M12,21s-7,-6 -7,-11a7,7 0,0 1,14 0c0,5 -7,11 -7,11zM12,12.5a2.5,2.5 0,1 0,0 -5a2.5,2.5 0,0 0,0 5z"/>
</vector>
```

- [ ] **Step 3: RecorderService**

```kotlin
package ai.yeoro.service

import ai.yeoro.EngineBus
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.YeoroDb
import ai.yeoro.engine.*
import ai.yeoro.geo.haversineKm
import ai.yeoro.vehicle.NaviSource
import ai.yeoro.vehicle.VehicleSource
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime

class RecorderService : LifecycleService() {
    private lateinit var vehicle: VehicleSource
    private lateinit var navi: NaviSource
    private val dao by lazy { YeoroDb.get(this).dao() }
    private var openTripId: Long? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        startForeground(Notifier.ID_REC, Notifier.recording(this, "조용히 기록 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        vehicle = VehicleSource(this).also { it.start() }
        navi = NaviSource(this).also { it.start() }
        lifecycleScope.launch { navi.destination.collect { d -> EngineBus.home.value?.let { home ->
            val dest = d?.let { Destination(it.name, haversineKm(home, it.lat to it.lon), PlaceKind.OTHER) }
            handle(EngineBus.engine.handle(Event.DestinationSet(dest))) } } }
        lifecycleScope.launch { EngineBus.answers.collect { handle(EngineBus.engine.handle(Event.Answer(it))) } }
        lifecycleScope.launch { while (true) { tick(); delay(1_000) } }
        lifecycleScope.launch { while (true) { dao.purgeBuffer(System.currentTimeMillis() - 24 * 3_600_000L); delay(3_600_000L) } }
    }

    private suspend fun tick() {
        val v = vehicle.state.value; val loc = navi.location.value
        if (EngineBus.home.value == null && loc != null) EngineBus.home.value = loc   // 첫 위치를 집으로 (설정 화면 생기기 전 임시)
        val homeKm = EngineBus.home.value?.let { h -> loc?.let { haversineKm(h, it) } } ?: 0.0
        val now = LocalDateTime.now()
        val cal = CalendarInfo(weekend = now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY, holiday = false, hour = now.hour)
        val snap = Snapshot(System.currentTimeMillis(), v.gear, v.speedKmh, v.seats, v.trunkOpen, v.wiperOn, v.openWindows, v.doorOpen, homeKm)
        handle(EngineBus.engine.handle(Event.Tick(snap, cal, now.toLocalDate().toString())))
    }

    private suspend fun handle(actions: List<Action>) = actions.forEach { a ->
        when (a) {
            is Action.ScoreUpdated -> EngineBus.lastScore.value = a
            is Action.AskBeforeDeparture -> Notifier.ask(this, "가족 여행 가시나요?", "여행 점수 ${a.score.total}. 출발하면 조용히 기록할게요.", "여행 기록 시작", "오늘은 그냥")
            is Action.AskRetroactive -> Notifier.ask(this, "오늘, 여행이었나요?", "출발할 때부터 기록해 뒀어요. 앨범으로 남길까요?", "앨범으로 남기기", "아니요, 지워 주세요")
            is Action.StartTrip -> { val id = dao.insertTrip(TripEntity(startMs = a.sinceMs, retroactive = a.retroactive, destination = navi.destination.value?.name)); openTripId = id; if (a.retroactive) dao.attachBuffer(id, a.sinceMs) }
            is Action.RecordMoment -> dao.insertMoment(MomentEntity(tripId = openTripId, timeMs = a.moment.timeMs, type = a.moment.type.name, detail = a.moment.detail))
            is Action.TripClosed -> { dao.openTrip()?.let { dao.updateTrip(it.copy(endMs = a.endMs, state = "ALBUM_READY")) }; openTripId = null }
            Action.Discard -> dao.deleteBuffer()
        }
    }

    override fun onDestroy() { vehicle.stop(); navi.stop(); super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { super.onStartCommand(intent, flags, startId); return START_STICKY }
}
```

- [ ] **Step 4: 매니페스트**

`<application>`에 `android:name=".YeoroApp"`을 넣고 아래를 추가한다.
```xml
<service android:name=".service.RecorderService" android:exported="false" android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="여행 중 차량 상태를 화면 없이 기록" />
</service>
<receiver android:name=".service.AnswerReceiver" android:exported="false" />
```

- [ ] **Step 5: 빌드 확인** — Run: `./gradlew :app:assembleDebug -q` → 성공

- [ ] **Step 6: 커밋** — `git add app && git commit -qm "feat(app): recorder foreground service and park-only prompts" && git push`

---

### Task 8: Pleos 테마와 첫 화면

**Files:**
- Create: `app/src/main/res/font/asta_sans.ttf`, `app/src/main/java/ai/yeoro/ui/Theme.kt`, `app/src/main/java/ai/yeoro/ui/MainActivity.kt`, `app/src/main/java/ai/yeoro/ui/StatusScreen.kt`, `app/src/main/java/ai/yeoro/ui/EngineDebug.kt`
- Delete: `app/src/main/java/ai/yeoro/ProbeActivity.kt` (매니페스트 항목도 삭제)
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `EngineBus.lastScore`, `YeoroDb.dao().trips()`
- Produces: 런처 액티비티 `ai.yeoro.ui.MainActivity`. 실행하면 `RecorderService`를 시작한다.

- [ ] **Step 1: 폰트**

```bash
curl -sL -o app/src/main/res/font/asta_sans.ttf "https://github.com/google/fonts/raw/main/ofl/astasans/AstaSans%5Bwght%5D.ttf" && file app/src/main/res/font/asta_sans.ttf
```
Expected: `TrueType Font data`. 받지 못하면 https://fonts.google.com/specimen/Asta+Sans 에서 받아 같은 경로에 둔다.

- [ ] **Step 2: 테마**(라이트·다크는 시스템 설정을 따른다)

```kotlin
package ai.yeoro.ui
import ai.yeoro.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Asta = FontFamily(Font(R.font.asta_sans, FontWeight.Normal), Font(R.font.asta_sans, FontWeight.SemiBold), Font(R.font.asta_sans, FontWeight.Bold))
val Dusk = Color(0xFFFFB46A)

private val Light = lightColorScheme(primary = Color(0xFF16181D), onPrimary = Color.White, secondary = Dusk, background = Color(0xFFF4F5F7), surface = Color.White, onSurface = Color(0xFF16181D), onSurfaceVariant = Color(0xFF6D727B))
private val Dark = darkColorScheme(primary = Color(0xFFEEF0F7), onPrimary = Color(0xFF0E1220), secondary = Dusk, background = Color(0xFF0E1220), surface = Color(0xFF161B2E), onSurface = Color(0xFFEEF0F7), onSurfaceVariant = Color(0xFF9AA1B8))

// Pleos 가이드: 최소 12sp, 줄 높이 1.5배 이상, 운전 중 한눈에 읽히는 크기
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = Asta, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 52.sp),
    titleLarge = TextStyle(fontFamily = Asta, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 40.sp),
    bodyLarge = TextStyle(fontFamily = Asta, fontSize = 22.sp, lineHeight = 34.sp),
    labelLarge = TextStyle(fontFamily = Asta, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
)

@Composable fun YeoroTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
```

- [ ] **Step 3: 화면**

`StatusScreen.kt`:
```kotlin
package ai.yeoro.ui
import ai.yeoro.EngineBus
import ai.yeoro.data.YeoroDb
import ai.yeoro.engine.TripState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun TripState.label() = when (this) {
    TripState.OFF -> "기록하지 않아요 (동의 안 한 프로필)"; TripState.DAILY -> "일상 · 아무것도 띄우지 않아요"
    TripState.CANDIDATE -> "여행일지도 몰라요 · 조용히 기록 중"; TripState.TRIP -> "여행 기록 중"
    TripState.STAYING -> "여행지에서 머무는 중"; TripState.ALBUM_READY -> "앨범을 만들 준비가 됐어요"
}

@Composable fun StatusScreen() {
    val score by EngineBus.lastScore.collectAsStateWithLifecycle()
    val trips by YeoroDb.get(LocalContext.current).dao().trips().collectAsStateWithLifecycle(emptyList())
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.padding(40.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("여로", style = MaterialTheme.typography.headlineLarge)
                Text(score?.state?.label() ?: "차량 신호를 기다리는 중", style = MaterialTheme.typography.titleLarge)
                Text("저장된 여행 ${trips.size}개", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EngineDebug(Modifier.weight(1f))
        }
    }
}
```

`EngineDebug.kt`:
```kotlin
package ai.yeoro.ui
import ai.yeoro.EngineBus
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun EngineDebug(modifier: Modifier = Modifier) {
    val u by EngineBus.lastScore.collectAsStateWithLifecycle()
    Card(modifier) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("판단 엔진", style = MaterialTheme.typography.labelLarge)
        u?.score?.signals?.forEach { Text("${it.label} (${it.source})  ${if (it.points > 0) "+" else ""}${it.points}", fontFamily = FontFamily.Monospace, fontSize = 18.sp) }
        Text("여행 점수 ${u?.score?.total ?: 0} · ${u?.state ?: "-"}", style = MaterialTheme.typography.titleLarge)
    } }
}
```

`MainActivity.kt`:
```kotlin
package ai.yeoro.ui
import ai.yeoro.service.RecorderService
import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf("android.car.permission.CAR_SPEED", Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, RecorderService::class.java))
        setContent { YeoroTheme { StatusScreen() } }
    }
}
```

매니페스트: ProbeActivity 항목을 지우고 다음을 넣는다.
```xml
<activity android:name=".ui.MainActivity" android:exported="true">
    <meta-data android:name="distractionOptimized" android:value="true" />
    <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
</activity>
```

- [ ] **Step 4: 실행 확인**

Run: `./gradlew :app:installDebug -q && adb shell pm grant ai.yeoro android.car.permission.CAR_SPEED && adb shell am start -n ai.yeoro/.ui.MainActivity && sleep 4 && adb exec-out screencap -p > /tmp/yeoro_status.png`
Expected: "일상 · 아무것도 띄우지 않아요" 또는 대기 문구와 판단 엔진 카드가 보인다.

- [ ] **Step 5: 커밋** — `git add -A && git commit -qm "feat(app): Pleos-themed status screen with engine debug" && git push`

---

### Task 9: 에뮬레이터 상황 재연 스크립트 (A·B·C)

**Files:**
- Modify: `scripts/vc.sh` (좌석·트렁크·와이퍼·창문 명령 추가, Task 5 Step 4에서 확인한 필드명 사용)
- Create: `scripts/scenario_a.sh`, `scripts/scenario_b.sh`, `scripts/scenario_c.sh`, `scripts/expect.sh`

**Interfaces:**
- Consumes: 앱 로그 태그 `YeoroEngine`. RecorderService의 `handle()`에서 Action마다 `Log.i("YeoroEngine", a.toString())`를 남기도록 이 Task에서 한 줄을 추가한다.

- [ ] **Step 1: 엔진 로그 한 줄 추가** (`RecorderService.handle` 맨 앞)
```kotlin
if (a !is Action.ScoreUpdated) android.util.Log.i("YeoroEngine", a.toString())
```

- [ ] **Step 2: 검증 도우미** `scripts/expect.sh`
```bash
#!/bin/bash
# 사용: expect.sh "<패턴>" <초>  — 로그에 패턴이 나오면 0, 시간 안에 안 나오면 1
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
for _ in $(seq 1 "$2"); do "$ADB" logcat -d -s YeoroEngine | grep -q "$1" && { echo "OK  $1"; exit 0; }; sleep 1; done
echo "FAIL $1"; exit 1
```

- [ ] **Step 3: 상황 A** `scripts/scenario_a.sh`: 가족 4명 착석, 트렁크 열고 닫기 2회, P단. 내비 목적지는 에뮬레이터 내비 앱에서 강릉으로 직접 설정하거나 `adb shell am start`가 막히면 수동으로 설정한다.
```bash
#!/bin/bash
set -e; cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" logcat -c
./vc.sh gear Park
for s in fl fr rl rr; do ./vc.sh seat "$s" on; done
for _ in 1 2; do ./vc.sh trunk open; sleep 2; ./vc.sh trunk close; sleep 2; done
echo "내비에서 목적지를 '강릉 경포해변'으로 설정하세요 (60초 대기)"
./expect.sh "AskBeforeDeparture" 60
```

- [ ] **Step 4: 상황 C** `scripts/scenario_c.sh`: 혼자, 평일 패턴은 시계에 의존하므로 좌석 1명, 트렁크 없음, 짧은 목적지에서 30초 주행 → `AskBeforeDeparture`가 **나오지 않아야** 통과
```bash
#!/bin/bash
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" logcat -c; ./vc.sh gear Park; ./vc.sh seat fl on; for s in fr rl rr; do ./vc.sh seat "$s" off; done
./vc.sh gear Drive; ./vc.sh speed 50; sleep 30
if ./expect.sh "AskBeforeDeparture" 3; then echo "FAIL: 출퇴근에 제안이 뜸"; exit 1; else echo "OK  조용함"; fi
```

- [ ] **Step 5: 상황 B** `scripts/scenario_b.sh`: 실제 시간 기준이라 1시간 정차를 기다릴 수 없다. 앱에 디버그용 시간 배속(`EngineBus.timeScale`)을 넣는 대신, B는 JVM 테스트(Task 4)로 검증하고 에뮬레이터에서는 "후보 상태 진입"까지만 본다.
```bash
#!/bin/bash
cd "$(dirname "$0")"; ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" logcat -c; ./vc.sh gear Park; for s in fl fr rl; do ./vc.sh seat "$s" on; done; ./vc.sh seat rr off
./vc.sh trunk open; sleep 2; ./vc.sh trunk close; ./vc.sh gear Drive; ./vc.sh speed 70; sleep 5
"$ADB" logcat -d -s YeoroEngine | tail -5; echo "앱 화면 상태가 '여행일지도 몰라요'인지 확인 (주말에 실행해야 45점)"
```

- [ ] **Step 6: 실행과 기록**
Run: `chmod +x scripts/*.sh && scripts/scenario_c.sh && scripts/scenario_a.sh`
Expected: `OK  조용함`, `OK  AskBeforeDeparture`. 결과와 화면 캡처 경로를 `README.md`의 "상황 재연 결과"에 적는다.

- [ ] **Step 7: 커밋** — `git add -A && git commit -qm "test: emulator scenario scripts A/B/C" && git push`

---

## Self-Review 결과
- 스펙 대비: 점수표(Task 2), 순간 규칙(Task 3), 상태 흐름·정책·상황 A~L(Task 4, G·H는 감지기 테스트, D·E는 점수 테스트, K의 재방문 혜택은 2·3단계 범위), 24시간 삭제(Task 6·7), P단 알림(Task 4·7), 동의 없는 프로필(Task 4 L. Pleos 프로필 연동은 2단계), Pleos 디자인(Task 8), 에뮬레이터 검증(Task 5·9)
- 1단계에서 뺀 것: 운전 교대(신호 부족, 로드맵), 공휴일 API(2단계에서 천문연 연동, 지금은 holiday=false), 목적지 장소 분류(TourAPI, 2단계, 지금은 OTHER), 집 위치 설정 화면(2단계, 지금은 첫 위치를 집으로)
- 타입 일관성: `ScoreResult.band`, `Action.*`, `TripState`, `RawVehicle`, `NaviDestination` 이름을 Task 4·5·7·8에서 같게 썼다.
