package ai.yeoro.cloud

import java.io.File
import java.time.LocalDate

sealed interface CloudResult<out T> {
    data class Ok<T>(val value: T) : CloudResult<T>
    /** status: HTTP 상태(연결 실패면 null), code: 서버의 error 값 또는 "network"·"offline"·"bad_json"·"http_<상태>" */
    data class Fail(val status: Int?, val code: String) : CloudResult<Nothing>
}

/** 여로 Edge Function 계약 (supabase/functions/{trip,photos,outside,diary}) */
interface CloudApi {
    suspend fun registerTrip(token: String, startedAtMs: Long, destName: String?): CloudResult<Unit>
    suspend fun deleteTrip(token: String): CloudResult<Unit>
    suspend fun photos(token: String): CloudResult<List<RemotePhoto>>
    /** 차에서 사진 빼기 (DELETE photos?t&ids, 50장까지). 204 → Ok, 404(unknown_photo·unknown_token)는 이미 지워진 것 */
    suspend fun deletePhotos(token: String, ids: List<String>): CloudResult<Unit>
    suspend fun download(url: String, dest: File): CloudResult<Unit>
    /** 원문 JSON (캐시에 그대로) */
    suspend fun outside(lat: Double, lon: Double, atMs: Long, parts: Set<String> = emptySet()): CloudResult<String>
    suspend fun holidays(year: Int): CloudResult<List<LocalDate>>
    suspend fun diary(requestJson: String): CloudResult<String>
}

data class RemotePhoto(val id: String, val takenAtMs: Long?, val takenAtSource: String, val uploadedMs: Long,
                       val w: Int, val h: Int, val thumbUrl: String, val url: String)
