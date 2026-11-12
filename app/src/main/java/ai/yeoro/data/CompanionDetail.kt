package ai.yeoro.data

import ai.yeoro.engine.Seat

/** 좌석 순간 detail("2→4")에 그때 앉은 좌석을 좌석 순서로 붙인다: "2→4|DRIVER,PASSENGER,…". 좌석을 모르면 그대로. */
fun companionDetail(counts: String, seats: Set<Seat>): String =
    if (seats.isEmpty()) counts else counts + "|" + Seat.entries.filter { it in seats }.joinToString(",") { it.name }

/** 좌석 순간 detail 읽기: (전, 후, 좌석 목록). 좌석이 없는 예전 기록은 빈 목록. */
data class Joined(val from: Int?, val to: Int?, val seats: List<Seat>)
fun parseJoined(detail: String): Joined {
    val counts = detail.substringBefore("|")
    val (from, to) = counts.split("→").map { it.trim().toIntOrNull() }.let { it.getOrNull(0) to it.getOrNull(1) }
    val seats = if ("|" in detail) detail.substringAfter("|").split(",").mapNotNull { n -> Seat.entries.firstOrNull { it.name == n.trim() } } else emptyList()
    return Joined(from, to, seats)
}

/** 출발 때 앉은 좌석 순간 detail("→4|DRIVER,…"). 앉은 사람이 없으면 null (남기지 않는다). */
fun departureSeatsDetail(seats: Set<Seat>): String? = if (seats.isEmpty()) null else companionDetail("→${seats.size}", seats)
