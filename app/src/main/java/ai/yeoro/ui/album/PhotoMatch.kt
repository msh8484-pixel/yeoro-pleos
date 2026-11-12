package ai.yeoro.ui.album

import kotlin.math.abs

/*
 * 가족 사진 → 앨범의 장 (순수). 얼굴 인식 없이 촬영 시각만 차의 기록(장의 시작·끝)과 맞춘다.
 */

/** 앨범에 놓을 사진. takenAtMs: 믿을 수 있는 촬영 시각(trustedTakenAt), 모르면 null. 파일은 PhotoFiles 경로 */
data class AlbumPhoto(val id: String, val takenAtMs: Long?, val w: Int, val h: Int, val thumbFile: String?, val fullFile: String?)

/** 휴대폰과 차의 시계 차이·"찍고 조금 뒤 출발" 같은 어긋남을 받아 주는 폭 */
const val CLOCK_SKEW_MS = 10 * 60_000L

/** file(파일 수정 시각)이 올린 시각과 이만큼 가까우면 촬영 시각이 아니다(iOS는 고를 때의 시각을 준다) */
private const val FILE_NEAR_UPLOAD_MS = 5 * 60_000L

/** 서버가 준 촬영 시각 중 믿을 만한 것만. exif는 그대로, file은 올린 시각과 5분 안이면 버림, none은 null */
fun trustedTakenAt(takenAtMs: Long?, source: String, uploadedMs: Long): Long? = when {
    takenAtMs == null -> null
    source == "exif" -> takenAtMs
    source == "file" -> takenAtMs.takeIf { abs(it - uploadedMs) >= FILE_NEAR_UPLOAD_MS }
    else -> null
}

/** 장 i의 원래 창 [start, end). DEPARTURE의 끝은 다음 장 시작, ARRIVAL·RETURN은 다음 장 시작(없으면 끝없음), STOP은 머문 시간 끝 */
private fun rawWindow(chapters: List<Chapter>, i: Int): LongRange {
    val c = chapters[i]
    val next = chapters.getOrNull(i + 1)?.startMs
    val end = when (c.kind) {
        ChapterKind.DEPARTURE -> next ?: c.endMs
        ChapterKind.ARRIVAL, ChapterKind.RETURN -> next ?: Long.MAX_VALUE
        ChapterKind.STOP -> c.endMs
    }
    return c.startMs until maxOf(end, c.startMs)
}

private fun LongRange.widen(skew: Long): LongRange =
    (first - skew) until (if (last == Long.MAX_VALUE - 1) Long.MAX_VALUE else last + 1 + skew)

private fun gap(r: LongRange, t: Long): Long = when {
    r.isEmpty() -> abs(t - r.first)
    t < r.first -> r.first - t
    t > r.last -> t - r.last
    else -> 0
}

/**
 * 사진 → 장 번호. 장 i의 창 = [시작 − skew, 끝 + skew). 창이 여럿 맞으면 원래 창(skew 없이)에 드는 장(여럿이면 시작이 늦은 장), 원래
 * 창에 드는 장이 없으면(skew 창만 맞음) 원래 창까지 시간 거리가 가장 가까운 장(같으면 시작이 늦은 장). 어느 창에도 없으면 원래 창까지 시간 거리가 가장 가까운 장(같으면 앞 장).
 * 시각을 모르는 사진은 도착(ARRIVAL) 장. 결과는 모든 장 번호를 열쇠로 갖고, 장마다 촬영 시각순(모르는 사진은 끝).
 */
fun assignPhotos(chapters: List<Chapter>, photos: List<AlbumPhoto>, skewMs: Long = CLOCK_SKEW_MS): Map<Int, List<AlbumPhoto>> {
    if (chapters.isEmpty()) return emptyMap()
    val raw = chapters.indices.map { rawWindow(chapters, it) }
    val wide = raw.map { it.widen(skewMs) }
    val arrival = chapters.indexOfLast { it.kind == ChapterKind.ARRIVAL }.takeIf { it >= 0 } ?: chapters.lastIndex
    val out = chapters.indices.associateWith { mutableListOf<AlbumPhoto>() }
    for (p in photos) {
        val t = p.takenAtMs
        val idx = if (t == null) arrival else {
            val hits = chapters.indices.filter { t in wide[it] }
            when {
                hits.size == 1 -> hits.single()
                hits.isNotEmpty() -> hits.filter { t in raw[it] }.maxByOrNull { chapters[it].startMs }
                    ?: hits.sortedByDescending { chapters[it].startMs }.minBy { gap(raw[it], t) }   // 원래 창에서 가장 가까운 장, 같으면 늦은 장
                else -> chapters.indices.minBy { gap(raw[it], t) }
            }
        }
        out.getValue(idx) += p
    }
    return out.mapValues { (_, l) -> l.sortedWith(compareBy(nullsLast()) { it: AlbumPhoto -> it.takenAtMs }.thenBy { it.id }) }
}

/** 표지 사진: 도착 장의 가로 사진 중 가장 큰 것, 없으면 앨범의 첫 사진(장 순서), 사진이 없으면 null */
fun coverPhoto(chapters: List<Chapter>, byChapter: Map<Int, List<AlbumPhoto>>): AlbumPhoto? {
    val arrival = chapters.indexOfLast { it.kind == ChapterKind.ARRIVAL }
    byChapter[arrival].orEmpty().filter { it.w > it.h }.maxByOrNull { it.w.toLong() * it.h }?.let { return it }
    return chapters.indices.firstNotNullOfOrNull { byChapter[it]?.firstOrNull() }
}

/** 저장된 사진 행 → 앨범 사진 (촬영 시각은 믿을 만한 것만) */
fun albumPhoto(e: ai.yeoro.data.TripPhotoEntity) =
    AlbumPhoto(e.id, trustedTakenAt(e.takenAtMs, e.takenAtSource, e.uploadedMs), e.w, e.h, e.thumbFile, e.fullFile)
