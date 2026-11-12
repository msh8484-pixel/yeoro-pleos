package ai.yeoro.debug

import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.data.YeoroDb
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 디버그 전용 샘플 여행: 2026-10-24(토) 08:52 서울 마포 → 영동고속도로 → 13:20 강릉 경포해변.
 * 위치 30개, 정차 2번(09:53 횡성휴게소 38분, 11:42 대관령 14분), 좌석 4석, 비 10:58~12:40, 13:02 창문 4개.
 * 경로(ROUTE)는 시연 스크립트의 demo_route 재생에도 쓴다(DemoDrive). 도착점은 디버그 목적지 주입(route inject)과 같은 좌표.
 * round = true(왕복): 같은 갈 길 뒤에 경포 근처에서 머물다 17:05에 떠나 양양·서울양양고속도로(인제·홍천)로 돌아오며 18:40 가평휴게소에서 15분 쉬고 19:40 집(마포)에 닿는다.
 * 같은 출발 시각·목적지의 기존 샘플 여행(편도·왕복 모두)을 지우고 다시 넣으므로 여러 번 돌려도 하나만 남는다. 시각은 기기 시간대 기준.
 */
object SeedTrip {
    private const val DEST = "강릉 경포해변"
    private val DAY = LocalDate.of(2026, 10, 24)

    // (시, 분, 위도, 경도)
    internal val ROUTE = listOf(
        Wp(8, 52, 37.5563, 126.9220), Wp(9, 0, 37.5300, 127.0000), Wp(9, 8, 37.4850, 127.0350), Wp(9, 15, 37.4000, 127.1000),
        Wp(9, 22, 37.2800, 127.1100), Wp(9, 27, 37.2450, 127.2100), Wp(9, 32, 37.2400, 127.3000), Wp(9, 37, 37.2500, 127.4000),
        Wp(9, 42, 37.2900, 127.5300), Wp(9, 46, 37.3100, 127.6300), Wp(9, 50, 37.3900, 127.8800), Wp(9, 53, 37.4900, 127.9800),
        Wp(10, 31, 37.4900, 127.9800), Wp(10, 38, 37.5200, 128.1000), Wp(10, 45, 37.5400, 128.2200), Wp(10, 52, 37.5650, 128.3200),
        Wp(10, 58, 37.5750, 128.4000), Wp(11, 6, 37.5950, 128.4700), Wp(11, 14, 37.6200, 128.5300), Wp(11, 22, 37.6400, 128.5800),
        Wp(11, 32, 37.6600, 128.6500), Wp(11, 42, 37.6800, 128.7200), Wp(11, 56, 37.6800, 128.7200), Wp(12, 10, 37.7100, 128.7700),
        Wp(12, 25, 37.7350, 128.8150), Wp(12, 40, 37.7500, 128.8450), Wp(12, 50, 37.7650, 128.8650), Wp(13, 2, 37.7800, 128.8800),
        Wp(13, 12, 37.7950, 128.8900), Wp(13, 20, 37.8055, 128.9087),
    )

    // 왕복의 나머지: 경포 근처에서 머문 샘플, 17:05 떠남. 귀갓길은 갈 길(영동고속도로)과 다른 길:
    // 주문진 → 양양 → 서울양양고속도로(인제·홍천) → 가평휴게소(18:40~18:55 정차) → 강일 → 19:40 집
    private val STAY_AND_RETURN = listOf(
        Wp(14, 30, 37.8045, 128.9070), Wp(16, 0, 37.8060, 128.9095), Wp(17, 5, 37.8055, 128.9087),
        Wp(17, 15, 37.8650, 128.8350), Wp(17, 25, 37.9650, 128.7500), Wp(17, 35, 38.0600, 128.6200), Wp(17, 45, 37.9850, 128.4600),
        Wp(17, 55, 37.9200, 128.2700), Wp(18, 8, 37.8100, 128.0500), Wp(18, 20, 37.7400, 127.8300), Wp(18, 32, 37.7100, 127.6100),
        Wp(18, 40, 37.7050, 127.4800), Wp(18, 55, 37.7050, 127.4800), Wp(19, 5, 37.6600, 127.3300), Wp(19, 15, 37.5700, 127.1700),
        Wp(19, 28, 37.5400, 127.0300), Wp(19, 40, 37.5563, 126.9220),
    )

    internal data class Wp(val h: Int, val m: Int, val lat: Double, val lon: Double)
    private fun at(h: Int, m: Int) = DAY.atTime(LocalTime.of(h, m)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun wp(h: Int, m: Int) = (ROUTE + STAY_AND_RETURN).first { it.h == h && it.m == m }

    /** 넣은 여행 id를 돌려준다 */
    suspend fun seed(db: YeoroDb, round: Boolean = false): Long = db.withTransaction {
        val dao = db.dao()
        val start = at(8, 52)
        dao.trips().first().filter { it.startMs == start && it.destination == DEST }.forEach { dao.deleteTrip(it.id) }
        val id = dao.insertTrip(TripEntity(startMs = start, endMs = if (round) at(19, 40) else at(13, 20), destination = DEST, state = "ALBUM_READY", destLat = 37.8055, destLon = 128.9087))
        (if (round) ROUTE + STAY_AND_RETURN else ROUTE).forEach { dao.insertSample(LocationSampleEntity(tripId = id, timeMs = at(it.h, it.m), lat = it.lat, lon = it.lon)) }
        fun moment(h: Int, m: Int, type: String, detail: String = "") = wp(h, m).let { MomentEntity(tripId = id, timeMs = at(h, m), type = type, detail = detail, lat = it.lat, lon = it.lon) }
        listOf(
            moment(9, 53, "STOP", "38"),
            moment(10, 31, "COMPANION_JOINED", ai.yeoro.data.companionDetail("2→4", setOf(ai.yeoro.engine.Seat.DRIVER, ai.yeoro.engine.Seat.PASSENGER, ai.yeoro.engine.Seat.REAR_LEFT, ai.yeoro.engine.Seat.REAR_RIGHT))), // 실제 엔진처럼 쉬었다 출발할 때만
            moment(10, 58, "RAIN_START"),
            moment(11, 42, "STOP", "14"),
            moment(12, 40, "RAIN_STOP"),
            moment(13, 2, "WINDOWS_OPEN", "4"),
        ).plus(if (round) listOf(moment(18, 40, "STOP", "15")) else emptyList()).forEach { dao.insertMoment(it) }
        id
    }
}
