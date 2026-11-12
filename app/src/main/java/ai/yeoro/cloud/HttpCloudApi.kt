package ai.yeoro.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * HttpURLConnection 구현 (새 의존성 없음). 연결 10초·읽기 20초. 모든 함수 호출에 apikey(publishable),
 * 기기 비밀은 trip(body)·photos(x-yeoro-device 헤더)에만 붙인다.
 * log: 디버그 빌드만 넘긴다. 함수 이름·상태만 남기고 토큰·키·기기 비밀은 찍지 않는다.
 */
class HttpCloudApi(
    private val config: CloudConfig,
    private val device: DeviceSecret,
    private val online: () -> Boolean = { true },
    private val log: ((String) -> Unit)? = null,
) : CloudApi {
    private class Res(val status: Int, val body: String)

    override suspend fun registerTrip(token: String, startedAtMs: Long, destName: String?): CloudResult<Unit> =
        call("trip", "POST", config.fn("trip"), { CloudJson.registerBody(token, device.value, startedAtMs, destName) }) { r ->
            if (r.status == 201 || r.status == 200) CloudResult.Ok(Unit) else fail(r)
        }

    override suspend fun deleteTrip(token: String): CloudResult<Unit> =
        call("trip", "DELETE", config.fn("trip"), { CloudJson.deleteBody(token, device.value) }) { r ->
            if (r.status == 204) CloudResult.Ok(Unit) else fail(r)
        }

    override suspend fun photos(token: String): CloudResult<List<RemotePhoto>> =
        call("photos", "GET", config.fn("photos") + "?t=" + enc(token), null, { mapOf("x-yeoro-device" to device.value) }) { r ->
            if (r.status == 200) CloudResult.Ok(CloudJson.parseRemotePhotos(r.body)) else fail(r)
        }

    override suspend fun deletePhotos(token: String, ids: List<String>): CloudResult<Unit> =
        call("photos", "DELETE", config.fn("photos") + "?t=" + enc(token) + "&ids=" + enc(ids.joinToString(",")), null, { mapOf("x-yeoro-device" to device.value) }) { r ->
            if (r.status == 204) CloudResult.Ok(Unit) else fail(r)
        }

    override suspend fun outside(lat: Double, lon: Double, atMs: Long, parts: Set<String>): CloudResult<String> {
        val q = "?lat=$lat&lon=$lon&t=$atMs" + if (parts.isEmpty()) "" else "&parts=" + enc(parts.sorted().joinToString(","))
        return call("outside", "GET", config.fn("outside") + q, null) { r -> if (r.status == 200) CloudResult.Ok(r.body) else fail(r) }
    }

    override suspend fun holidays(year: Int): CloudResult<List<LocalDate>> =
        call("holidays", "GET", config.fn("outside/holidays") + "?year=$year", null) { r ->
            if (r.status == 200) CloudResult.Ok(CloudJson.parseHolidays(r.body)) else fail(r)
        }

    override suspend fun diary(requestJson: String): CloudResult<String> =
        call("diary", "POST", config.fn("diary"), { requestJson }) { r -> if (r.status == 200) CloudResult.Ok(r.body) else fail(r) }

    /** 서명된 Storage 주소에서 내려받는다 (apikey 불필요). 임시 파일에 받은 뒤 바꿔 넣는다 */
    override suspend fun download(url: String, dest: File): CloudResult<Unit> = withContext(Dispatchers.IO) {
        if (!online()) return@withContext CloudResult.Fail(null, "offline")
        requests.incrementAndGet()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val c = open(url, "GET")
            try {
                val s = c.responseCode
                log?.invoke("cloud http download status=$s")
                if (s != 200) return@withContext CloudResult.Fail(s, "http_$s")
                dest.parentFile?.mkdirs()
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (!tmp.renameTo(dest)) { tmp.delete(); return@withContext CloudResult.Fail(null, "io") }
                CloudResult.Ok(Unit)
            } finally { c.disconnect() }
        } catch (e: IOException) {
            tmp.delete(); log?.invoke("cloud http download failed ${e.javaClass.simpleName}"); CloudResult.Fail(null, "network")
        }
    }

    private fun <T> fail(r: Res): CloudResult<T> = CloudResult.Fail(r.status, CloudJson.errorCode(r.body) ?: "http_${r.status}")

    /** body·headers는 IO 스레드에서 만든다 (기기 비밀을 처음 읽을 때 prefs 디스크 접근이 있을 수 있다) */
    private suspend fun <T> call(name: String, method: String, url: String, body: (() -> String)?, headers: () -> Map<String, String> = { emptyMap() }, map: (Res) -> CloudResult<T>): CloudResult<T> =
        withContext(Dispatchers.IO) {
            if (!online()) { log?.invoke("cloud http $method $name skipped offline"); return@withContext CloudResult.Fail(null, "offline") }
            requests.incrementAndGet()
            try {
                val c = open(url, method)
                try {
                    c.setRequestProperty("apikey", config.key)
                    headers().forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (body != null) {
                        c.doOutput = true
                        c.setRequestProperty("content-type", "application/json")
                        c.outputStream.use { it.write(body().toByteArray()) }
                    }
                    val s = c.responseCode
                    val text = (if (s >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    log?.invoke("cloud http $method $name status=$s")
                    CloudJson.parsed(s) { map(Res(s, text)) }
                } finally { c.disconnect() }
            } catch (e: IOException) {
                log?.invoke("cloud http $method $name failed ${e.javaClass.simpleName}")
                CloudResult.Fail(null, "network")
            }
        }

    private fun open(url: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10_000; readTimeout = 20_000; useCaches = false; instanceFollowRedirects = false
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** 이 프로세스에서 실제로 나간 요청 수 (디버그 확인용: 동의가 꺼져 있으면 늘지 않아야 한다) */
        val requests = AtomicInteger(0)
    }
}
