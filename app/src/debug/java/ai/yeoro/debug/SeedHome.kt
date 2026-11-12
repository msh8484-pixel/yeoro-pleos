package ai.yeoro.debug

import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.OpenedAlbums
import ai.yeoro.data.TripEntity
import ai.yeoro.data.YeoroDb
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 디버그 전용 홈(책장) 샘플. SeedTrip(2026-10-24 강릉 경포해변, 새 앨범)에 더해
 *  - 2025-10-25(토) 강릉 경포해변 (같은 목적지 두 번째 → 정기 방문·단골 2단계, 열어 본 앨범)
 *  - 2026-05-05(화) 전주 한옥마을 (창문 순간, 열어 본 앨범)
 *  - 2026-10-18(일) 10:12~11:40 가평 쪽 41km, 확인 안 한 여행(UNCONFIRMED, 목적지 없음)
 * 같은 출발 시각의 샘플 여행을 지우고 다시 넣으므로 여러 번 보내도 하나씩만 남는다. 시각은 기기 시간대 기준.
 */
object SeedHome {
    private val HOME = 37.5563 to 126.9220
    private val GYEONGPO = 37.8055 to 128.9087
    private val JEONJU = 35.8150 to 127.1530
    private val GAPYEONG = 37.8160 to 127.5110

    private fun at(y: Int, mo: Int, d: Int, h: Int, m: Int) = LocalDateTime.of(y, mo, d, h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private class Sample(val trip: TripEntity, val via: List<Pair<Double, Double>>, val moments: List<Pair<Long, Pair<String, String>>>, val opened: Boolean)

    private val samples = listOf(
        Sample(TripEntity(startMs = at(2025, 10, 25, 10, 5), endMs = at(2025, 10, 25, 14, 41), destination = "강릉 경포해변", state = "ALBUM_READY",
            destLat = GYEONGPO.first, destLon = GYEONGPO.second),
            listOf(HOME, 37.49 to 127.98, 37.68 to 128.72, GYEONGPO), emptyList(), opened = true),
        Sample(TripEntity(startMs = at(2026, 5, 5, 9, 10), endMs = at(2026, 5, 5, 12, 5), destination = "전주 한옥마을", state = "ALBUM_READY",
            destLat = JEONJU.first, destLon = JEONJU.second),
            listOf(HOME, 37.20 to 127.05, 36.50 to 127.20, JEONJU), listOf(at(2026, 5, 5, 11, 48) to ("WINDOWS_OPEN" to "2")), opened = true),
        Sample(TripEntity(startMs = at(2026, 10, 18, 10, 12), endMs = at(2026, 10, 18, 11, 40), destination = null, state = "UNCONFIRMED"),
            listOf(HOME, 37.62 to 127.15, 37.70 to 127.35, GAPYEONG), emptyList(), opened = false),
    )

    /** 샘플 여행 넷(SeedTrip 포함)을 넣는다. 강릉 2026 앨범만 "새 앨범"으로 남긴다. */
    suspend fun seed(c: Context, db: YeoroDb): List<Long> {
        val main = SeedTrip.seed(db)
        OpenedAlbums.forget(c, main)
        val ids = db.withTransaction {
            val dao = db.dao()
            val existing = dao.trips().first()
            samples.map { s ->
                existing.filter { it.startMs == s.trip.startMs && it.state in setOf("ALBUM_READY", "UNCONFIRMED") }.forEach { dao.deleteTrip(it.id) }
                val id = dao.insertTrip(s.trip)
                val end = s.trip.endMs!!; val n = 12
                // 경유점 사이를 고르게 나눠 12개 위치 샘플
                (0 until n).forEach { k ->
                    val f = k / (n - 1.0) * (s.via.size - 1); val i = f.toInt().coerceAtMost(s.via.size - 2); val r = f - i
                    val a = s.via[i]; val b = s.via[i + 1]
                    dao.insertSample(LocationSampleEntity(tripId = id, timeMs = s.trip.startMs + (end - s.trip.startMs) * k / (n - 1),
                        lat = a.first + (b.first - a.first) * r, lon = a.second + (b.second - a.second) * r))
                }
                s.moments.forEach { (t, m) -> dao.insertMoment(MomentEntity(tripId = id, timeMs = t, type = m.first, detail = m.second)) }
                id
            }
        }
        samples.zip(ids).forEach { (s, id) -> if (s.opened) OpenedAlbums.mark(c, id) }
        return listOf(main) + ids
    }

    /** 빈 책장 확인용: 모든 여행(순간·위치·사진 함께)과 열어 본 앨범 표시를 지운다. 버퍼는 건드리지 않는다.
     *  앱의 [지우기]와 같은 길(CloudSync.deleteTrip)이라 서버에 등록된 시연 여행도 함께 지운다(동의가 꺼져 있으면 큐에만). */
    suspend fun clear(c: Context, db: YeoroDb): Int {
        val trips = db.dao().trips().first()
        val cloud = ai.yeoro.cloud.CloudSync.get(c)
        trips.forEach { cloud.deleteTrip(it.id) }
        OpenedAlbums.clear(c)
        return trips.size
    }
}
