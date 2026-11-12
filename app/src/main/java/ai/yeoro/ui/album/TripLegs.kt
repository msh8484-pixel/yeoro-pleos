package ai.yeoro.ui.album

import ai.yeoro.data.LocationSampleEntity
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.geo.haversineKm
import ai.yeoro.service.ARRIVAL_PARK_MS
import ai.yeoro.service.ARRIVAL_RADIUS_M
import kotlin.math.abs

/*
 * 왕복 여행 나누기 (순수). 가족 여행은 대개 집으로 돌아온다. 돌아오는 길을 도착 장과 경로에서 떼어 내기 위해
 * 샘플 목록에서 "도착한 샘플"과 "목적지 곁을 마지막으로 떠난 샘플"을 찾는다.
 */

/** samples는 시간순. arriveIdx: 목적지에 닿은 샘플, leaveIdx: 목적지 곁을 마지막으로 떠난 샘플(왕복일 때만), returnsHome: 집으로 돌아온 여행 */
data class TripLegs(val arriveIdx: Int, val leaveIdx: Int?, val returnsHome: Boolean)

private const val HOME_BACK_KM = 1.0       // 마지막 샘플이 집에서 이 안이면 집에 돌아온 것
private const val AWAY_MIN_KM = 2.0        // 도착 지점이 집에서 이 이상 떨어져야 왕복 여행

/**
 * - arriveIdx: 목적지 좌표가 있으면 ARRIVAL_RADIUS_M(300m) 안의 샘플 가운데 처음으로 "머문" 샘플(엔진의 도착 판정 ArrivalPolicy처럼
 *   300m 안 + 머묾). 머묾 = 그 샘플부터 반경 안에 이어진 샘플들이 ARRIVAL_PARK_MS(2분) 이상 걸치거나, 그 샘플 시각부터 반경을 벗어나기 전
 *   사이에 반경 안에서 시작한 정차(STOP 순간)가 있을 때. 지나치기·GPS 튐처럼 잠깐 든 샘플은 도착이 아니다.
 *   머문 샘플이 없으면 반경 안에 처음 든 샘플. 한 번도 안 들었거나 목적지가 없으면 집에서 가장 먼 샘플(같으면 앞), 집도 모르면 마지막 샘플.
 * - 도착 지점(중심): 목적지 반경으로 도착을 찾았으면 목적지 좌표, 아니면 도착 샘플 좌표.
 * - returnsHome: 집을 알고, 마지막 샘플이 집 1km 안이고, 도착 지점이 집에서 2km 이상이고, arriveIdx가 마지막이 아닐 때.
 * - leaveIdx(returnsHome일 때만): arriveIdx 이후 도착 지점 300m 안에 있던 마지막 샘플.
 * moments: 여행의 순간(STOP만 쓴다, 좌표가 없으면 가장 가까운 샘플 좌표). 샘플이 없으면 null.
 */
fun tripLegs(
    samples: List<LocationSampleEntity>, dest: Pair<Double, Double>?, home: Pair<Double, Double>?,
    moments: List<MomentEntity> = emptyList(),
): TripLegs? {
    if (samples.isEmpty()) return null
    val radiusKm = ARRIVAL_RADIUS_M / 1000.0
    val at = { i: Int -> samples[i].lat to samples[i].lon }
    val byDest = dest?.let { d -> arrivalByDest(samples, d, moments) }
    val arriveIdx = byDest
        ?: home?.let { h -> samples.indices.maxBy { haversineKm(at(it), h) } }   // maxBy는 같으면 앞의 것
        ?: samples.lastIndex
    val centre = if (byDest != null) dest else at(arriveIdx)
    val returnsHome = home != null && arriveIdx != samples.lastIndex &&
        haversineKm(at(samples.lastIndex), home) <= HOME_BACK_KM && haversineKm(centre, home) >= AWAY_MIN_KM
    val leaveIdx = if (returnsHome) (arriveIdx..samples.lastIndex).lastOrNull { haversineKm(at(it), centre) <= radiusKm } ?: arriveIdx else null
    return TripLegs(arriveIdx, leaveIdx, returnsHome)
}

/** 여행·샘플(시간순)·순간에서 tripLegs를 한 번 계산한다 (장 나누기와 앨범 경로가 같은 값을 쓴다) */
fun tripLegsOf(trip: TripEntity, ordered: List<LocationSampleEntity>, moments: List<MomentEntity>, home: Pair<Double, Double>?): TripLegs? =
    tripLegs(ordered, trip.destLat?.let { la -> trip.destLon?.let { lo -> la to lo } }, home, moments)

/** 목적지 300m 안에서 처음 머문 샘플, 머문 샘플이 없으면 처음 든 샘플, 한 번도 안 들었으면 null */
private fun arrivalByDest(samples: List<LocationSampleEntity>, dest: Pair<Double, Double>, moments: List<MomentEntity>): Int? {
    val radiusKm = ARRIVAL_RADIUS_M / 1000.0
    val inside = samples.map { haversineKm(it.lat to it.lon, dest) <= radiusKm }
    val firstIn = inside.indexOf(true).takeIf { it >= 0 } ?: return null
    val stopTimes = moments.filter { it.type == "STOP" }.mapNotNull { m ->
        val pos = m.lat?.let { la -> m.lon?.let { lo -> la to lo } }
            ?: samples.minBy { abs(it.timeMs - m.timeMs) }.let { it.lat to it.lon }
        m.timeMs.takeIf { haversineKm(pos, dest) <= radiusKm }
    }
    fun dwells(i: Int): Boolean {
        var j = i
        while (j + 1 < samples.size && inside[j + 1]) j++
        if (samples[j].timeMs - samples[i].timeMs >= ARRIVAL_PARK_MS) return true
        val until = samples.getOrNull(j + 1)?.timeMs ?: Long.MAX_VALUE
        return stopTimes.any { it >= samples[i].timeMs && it < until }
    }
    return samples.indices.firstOrNull { inside[it] && dwells(it) } ?: firstIn
}
