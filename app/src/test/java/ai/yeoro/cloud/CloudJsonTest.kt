package ai.yeoro.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudJsonTest {
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun `사진 목록 - taken_at null 포함, 소수 초 uploaded_at`() {
        val json = """
            {"expires_at":"2026-11-30T00:00:00+00:00","photos":[
              {"id":"0b8f6c1e-1111-4a5b-9c3d-000000000001","taken_at":"2026-10-31T04:30:00+00:00","taken_at_source":"exif",
               "uploaded_at":"2026-10-31T05:00:00.123456+00:00","w":1600,"h":1200,"thumb_url":"https://x/t1","url":"https://x/f1"},
              {"id":"0b8f6c1e-1111-4a5b-9c3d-000000000002","taken_at":null,"taken_at_source":"none",
               "uploaded_at":"2026-10-31T05:01:00+00:00","w":900,"h":1600,"thumb_url":"https://x/t2","url":"https://x/f2"}
            ]}
        """.trimIndent()
        val p = CloudJson.parseRemotePhotos(json)
        assertEquals(2, p.size)
        assertEquals(RemotePhoto("0b8f6c1e-1111-4a5b-9c3d-000000000001", ms("2026-10-31T04:30:00Z"), "exif", ms("2026-10-31T05:00:00.123Z"), 1600, 1200, "https://x/t1", "https://x/f1"), p[0])
        assertNull(p[1].takenAtMs)
        assertEquals("none", p[1].takenAtSource)
        assertEquals(1600, p[1].h)
    }

    @Test fun `사진 목록 - 비어 있음`() {
        assertTrue(CloudJson.parseRemotePhotos("""{"expires_at":"2026-11-30T00:00:00+00:00","photos":[]}""").isEmpty())
    }

    @Test fun `특일 - 날짜 목록`() {
        val json = """{"year":2026,"holidays":[{"date":"2026-10-03","name":"개천절"},{"date":"2026-10-09","name":"한글날"}]}"""
        assertEquals(listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 9)), CloudJson.parseHolidays(json))
        assertTrue(CloudJson.parseHolidays("""{"year":2026,"holidays":[]}""").isEmpty())
    }

    @Test fun `등록 body - 기기 시간대 오프셋 ISO, 초 단위`() {
        val start = ms("2026-10-31T08:52:00.789+09:00")
        val b = JSONObject(CloudJson.registerBody("tok", "dev", start, "강릉 경포해변", ZoneId.of("Asia/Seoul")))
        assertEquals("tok", b.getString("token"))
        assertEquals("dev", b.getString("device"))
        assertEquals("2026-10-31T08:52:00+09:00", b.getString("started_at"))
        assertEquals("강릉 경포해변", b.getString("dest_name"))
    }

    @Test fun `등록 body - 목적지 없으면 null, 길면 80자로`() {
        val b = JSONObject(CloudJson.registerBody("tok", "dev", 0L, null, ZoneId.of("UTC")))
        assertTrue(b.isNull("dest_name"))
        assertEquals("1970-01-01T00:00:00Z", b.getString("started_at"))
        val long = JSONObject(CloudJson.registerBody("tok", "dev", 0L, "가".repeat(100), ZoneId.of("UTC")))
        assertEquals(80, long.getString("dest_name").length)
    }

    @Test fun `오류 본문의 error 코드`() {
        assertEquals("token_taken", CloudJson.errorCode("""{"error":"token_taken"}"""))
        assertNull(CloudJson.errorCode("not json"))
        assertNull(CloudJson.errorCode(""))
    }

    @Test fun `파싱 실패는 종류와 상관없이 bad_json (날짜 형식 오류 포함)`() {
        val badDate = """{"photos":[{"id":"a","taken_at":"어제","uploaded_at":"2026-10-31T05:00:00Z","w":1,"h":1,"thumb_url":"t","url":"u"}]}"""
        assertEquals(CloudResult.Fail(200, "bad_json"), CloudJson.parsed(200) { CloudResult.Ok(CloudJson.parseRemotePhotos(badDate)) })
        assertEquals(CloudResult.Fail(200, "bad_json"), CloudJson.parsed(200) { CloudResult.Ok(CloudJson.parseHolidays("""{"holidays":[{"date":"2026-13-01"}]}""")) })
        assertEquals(CloudResult.Fail(200, "bad_json"), CloudJson.parsed(200) { CloudResult.Ok(CloudJson.parseHolidays("<html>")) })
        assertEquals(CloudResult.Ok(emptyList<LocalDate>()), CloudJson.parsed(200) { CloudResult.Ok(CloudJson.parseHolidays("""{"holidays":[]}""")) })
    }
}
