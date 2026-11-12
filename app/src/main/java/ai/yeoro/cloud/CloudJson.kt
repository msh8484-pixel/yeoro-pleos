package ai.yeoro.cloud

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 서버 JSON 읽기·쓰기 (org.json, 순수) */
object CloudJson {
    /** DB 이름 길이 제한과 같다 (trip 함수: dest_name ≤ 80자) */
    private const val DEST_MAX = 80

    /** photos 함수 응답 → 사진 목록. taken_at은 null일 수 있다 */
    fun parseRemotePhotos(json: String): List<RemotePhoto> {
        val arr = JSONObject(json).getJSONArray("photos")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            RemotePhoto(
                id = p.getString("id"),
                takenAtMs = if (p.isNull("taken_at")) null else isoMs(p.getString("taken_at")),
                takenAtSource = p.optString("taken_at_source", "none"),
                uploadedMs = isoMs(p.getString("uploaded_at")),
                w = p.getInt("w"), h = p.getInt("h"),
                thumbUrl = p.getString("thumb_url"), url = p.getString("url"),
            )
        }
    }

    /** /outside/holidays 응답 → 쉬는 날 */
    fun parseHolidays(json: String): List<LocalDate> {
        val arr = JSONObject(json).getJSONArray("holidays")
        return (0 until arr.length()).map { LocalDate.parse(arr.getJSONObject(it).getString("date")) }
    }

    /** trip 등록 body. 출발 시각은 기기 시간대 오프셋 ISO(초 단위). 위치 기록은 넣지 않는다 */
    fun registerBody(token: String, device: String, startedAtMs: Long, destName: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        JSONObject()
            .put("token", token)
            .put("device", device)
            .put("started_at", Instant.ofEpochMilli(startedAtMs).truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("dest_name", destName?.trim()?.takeIf { it.isNotEmpty() }?.let { takeCodePoints(it, DEST_MAX) } ?: JSONObject.NULL)
            .toString()

    fun deleteBody(token: String, device: String): String = JSONObject().put("token", token).put("device", device).toString()

    /** 응답 읽기: 파싱 실패는 종류(JSONException·DateTimeParseException·NumberFormatException …)와 상관없이 Fail(status, "bad_json") */
    fun <T> parsed(status: Int, block: () -> CloudResult<T>): CloudResult<T> =
        try { block() } catch (e: Exception) { CloudResult.Fail(status, "bad_json") }

    /** 오류 본문 {"error":"…"}의 코드 (아니면 null) */
    fun errorCode(body: String?): String? =
        try { body?.takeIf { it.isNotBlank() }?.let { JSONObject(it).optString("error").takeIf { e -> e.isNotEmpty() } } } catch (_: Exception) { null }

    private fun isoMs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
    private fun takeCodePoints(s: String, n: Int): String = if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))
}
