package ai.yeoro.ui.home

import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ShelfTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun t(y: Int, mo: Int, d: Int, h: Int = 9, m: Int = 0) = LocalDateTime.of(y, mo, d, h, m).atZone(zone).toInstant().toEpochMilli()

    // 경포해변과 300m쯤 떨어진 곳(같은 목적지), 전주(다른 목적지)
    private val gyeongpo = 37.8055 to 128.8960
    private val gyeongpoNear = 37.8080 to 128.8960
    private val jeonju = 35.8150 to 127.1530

    private fun trip(id: Long, start: Long, dest: String?, at: Pair<Double, Double>?, state: String = "ALBUM_READY", end: Long? = start + 4 * 3_600_000L) =
        TripEntity(id = id, startMs = start, endMs = end, destination = dest, state = state, destLat = at?.first, destLon = at?.second)

    private val g2026 = trip(3, t(2026, 10, 24, 8, 52), "강릉 경포해변", gyeongpo)
    private val g2025 = trip(1, t(2025, 10, 25, 10, 0), "경포해변", gyeongpoNear)
    private val jj = trip(2, t(2026, 5, 5), "전주 한옥마을", jeonju)
    private val unconfirmed = trip(4, t(2026, 10, 18, 10, 12), null, null, state = "UNCONFIRMED", end = t(2026, 10, 18, 11, 40))
    private val open = trip(5, t(2026, 10, 27), "강릉 경포해변", gyeongpo, state = "TRIP", end = null)

    /* ── 같은 목적지 묶기 ── */

    @Test fun placeGroups_mergesDestinationsWithin500m() {
        val groups = placeGroups(listOf(g2026, jj, g2025))
        assertEquals(2, groups.size)
        assertEquals(listOf(1L, 3L), groups[0].trips.map { it.id })   // 방문 많은 순, 안에서는 시간순
        assertEquals(listOf(2L), groups[1].trips.map { it.id })
    }
    @Test fun placeGroups_nameIsLatestDestinationName() {
        assertEquals("강릉 경포해변", placeGroups(listOf(g2025, g2026))[0].name)
    }
    @Test fun placeGroups_ignoresTripsWithoutDestination() {
        assertEquals(1, placeGroups(listOf(unconfirmed, jj)).size)
    }
    @Test fun placeGroups_keepsFarPlacesApart() {
        val far = trip(9, t(2026, 1, 1), "강릉역", 37.7640 to 128.8990)   // 경포해변에서 약 4.6km
        assertEquals(2, placeGroups(listOf(g2026, far)).size)
    }
    @Test fun regularGroups_needTwoOrMoreTrips() {
        val r = regularGroups(placeGroups(listOf(g2026, jj, g2025)))
        assertEquals(1, r.size); assertEquals(2, r[0].count)
    }
    @Test fun groupDates_listsEachVisitOldestFirst() {
        assertEquals("2025.10.25 · 2026.10.24", groupDates(placeGroups(listOf(g2026, g2025))[0], zone))
    }

    /* ── 통계 ── */

    @Test fun homeStats_kmThisYearOnly_placesDistinct_tripCount() {
        val km = mapOf(1L to 240.4, 2L to 210.2, 3L to 238.7)
        val s = homeStats(listOf(g2026, jj, g2025), km, year = 2026, zone = zone)
        assertEquals(449, s.kmThisYear)   // 210.2 + 238.7 (2025 여행 제외)
        assertEquals(2, s.places)
        assertEquals(3, s.trips)
    }
    @Test fun homeStats_missingKmCountsZero() {
        assertEquals(0, homeStats(listOf(g2026), emptyMap(), 2026, zone).kmThisYear)
    }

    /* ── 책장·확인 안 한 여행 ── */

    @Test fun shelfTrips_onlyEndedAlbumReady_newestFirst() {
        assertEquals(listOf(3L, 2L, 1L), shelfTrips(listOf(jj, unconfirmed, g2025, open, g2026)).map { it.id })
    }
    @Test fun latestUnconfirmed_picksNewestEnded() {
        val older = trip(6, t(2026, 9, 1), null, null, state = "UNCONFIRMED")
        assertEquals(4L, latestUnconfirmed(listOf(older, g2026, unconfirmed))?.id)
        assertNull(latestUnconfirmed(listOf(g2026)))
    }
    @Test fun book_titleFromRulesAndNewBadgeWhenNotOpened() {
        val rain = listOf(MomentEntity(tripId = 3, timeMs = t(2026, 10, 24, 10, 58), type = "RAIN_START", detail = ""),
            MomentEntity(tripId = 3, timeMs = t(2026, 10, 24, 12, 40), type = "RAIN_STOP", detail = ""))
        val b = book(g2026, rain, km = 238.7, opened = emptySet(), zone = zone)
        assertEquals("비가 그친 오후, 강릉 경포해변으로", b.title)
        assertEquals("2026.10.24 토", b.date)
        assertEquals("강릉 경포해변", b.place)
        assertTrue(b.isNew); assertTrue(b.sea); assertTrue(b.rained)
        assertFalse(book(g2026, rain, 238.7, opened = setOf(3L), zone = zone).isNew)
    }
    @Test fun book_storedTitleWins_andNoDestination() {
        val b = book(trip(7, t(2026, 3, 1), null, null).copy(title = "봄 소풍"), emptyList(), 41.0, emptySet(), zone)
        assertEquals("봄 소풍", b.title); assertEquals("목적지 기록 없음", b.place); assertFalse(b.sea)
    }
    @Test fun unconfirmedLine_dateTimeRangeAndKm() {
        assertEquals("10월 18일 일요일 10:12~11:40 · 41km", unconfirmedLine(unconfirmed, 41.3, zone))
        assertEquals("10월 18일 일요일 10:12~11:40", unconfirmedLine(unconfirmed, null, zone))
    }

    /* ── 가족 지도 ── */

    @Test fun projectKorea_northUp_eastRight_centered() {
        val w = 500f; val h = 400f
        val (cx, cy) = projectKorea((33.0 + 38.7) / 2, (124.5 + 131.0) / 2, w, h)
        assertEquals(w / 2, cx, 0.5f); assertEquals(h / 2, cy, 0.5f)
        val (gx, gy) = projectKorea(gyeongpo.first, gyeongpo.second, w, h)
        val (jx, jy) = projectKorea(jeonju.first, jeonju.second, w, h)
        assertTrue(gx > jx); assertTrue(gy < jy)
    }
    @Test fun projectKorea_keepsAspectAndClampsOutside() {
        val w = 1000f; val h = 400f
        val (x0, y0) = projectKorea(38.7, 124.5, w, h)
        val (x1, y1) = projectKorea(33.0, 131.0, w, h)
        assertEquals(0f, y0, 0.5f); assertEquals(h, y1, 0.5f)   // 세로가 꽉 차고
        val scale = h / 5.7f
        assertEquals((131.0 - 124.5) * Math.cos(Math.toRadians(35.85)) * scale, (x1 - x0).toDouble(), 1.0)   // 가로는 cos(위도)만큼 줄어든다
        val (ox, oy) = projectKorea(45.0, 140.0, w, h)
        assertEquals(x1, ox, 0.5f); assertEquals(y0, oy, 0.5f)
    }
    @Test fun dotRadius_growsWithVisitsAndCaps() {
        assertEquals(22, dotRadiusDp(1)); assertEquals(28, dotRadiusDp(2)); assertEquals(40, dotRadiusDp(9))
    }
}
