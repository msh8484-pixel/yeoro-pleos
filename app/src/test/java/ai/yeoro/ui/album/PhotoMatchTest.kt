package ai.yeoro.ui.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PhotoMatchTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun t(h: Int, m: Int) = LocalDateTime.of(2026, 10, 24, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ch(kind: ChapterKind, h: Int, m: Int, stay: Int? = null, homeMs: Long? = null) =
        Chapter(kind, kind.name, t(h, m), stay, null, null, emptyList(), homeMs)
    private fun p(id: String, at: Long?, w: Int = 2048, h: Int = 1536) = AlbumPhoto(id, at, w, h, "$id.t", null)
    private fun where(chapters: List<Chapter>, at: Long?): Int =
        assignPhotos(chapters, listOf(p("x", at))).entries.single { it.value.isNotEmpty() }.key

    // 출발 08:52 → 첫 번째 쉼 09:53(38분, ~10:31) → 두 번째 쉼 11:42(14분, ~11:56) → 도착 13:20
    private val oneWay = listOf(
        ch(ChapterKind.DEPARTURE, 8, 52), ch(ChapterKind.STOP, 9, 53, 38), ch(ChapterKind.STOP, 11, 42, 14), ch(ChapterKind.ARRIVAL, 13, 20),
    )

    @Test fun `휴게소 창 안의 사진은 그 쉼 장`() {
        assertEquals(1, where(oneWay, t(10, 5)))
        assertEquals(2, where(oneWay, t(11, 50)))
    }

    @Test fun `창 사이 틈은 시간상 가까운 장`() {
        assertEquals(1, where(oneWay, t(10, 50)))   // 첫 쉼 끝(10:31)에서 19분, 둘째 쉼(11:42)까지 52분
        assertEquals(2, where(oneWay, t(11, 30)))
        assertEquals(2, where(oneWay, t(12, 30)))   // 둘째 쉼 끝(11:56)에서 34분, 도착(13:20)까지 50분
    }

    @Test fun `경계 ±10분 - 출발 2분 전 사진은 출발 장, 도착 8분 전은 도착 장`() {
        assertEquals(0, where(oneWay, t(8, 50)))
        assertEquals(0, where(oneWay, t(8, 30)))    // 어느 창에도 없으면 가까운 장
        assertEquals(3, where(oneWay, t(13, 12)))
        assertEquals(3, where(oneWay, t(18, 0)))    // 도착 장은 끝이 없다
    }

    @Test fun `창이 겹치면 원래 창에 드는 장, 둘 다 아니면 시작이 늦은 장`() {
        assertEquals(0, where(oneWay, t(9, 50)))    // 출발 장 원래 창 [08:52, 09:53)
        assertEquals(1, where(oneWay, t(9, 55)))    // 쉼 원래 창
        val tight = listOf(ch(ChapterKind.DEPARTURE, 8, 0), ch(ChapterKind.STOP, 9, 0, 30), ch(ChapterKind.ARRIVAL, 9, 40))
        assertEquals(2, where(tight, t(9, 36)))     // 쉼 끝(9:30)에서 6분, 도착(9:40)까지 4분: 둘 다 skew 창만 맞음 → 원래 창이 더 가까운 도착
        assertEquals(1, where(tight, t(9, 34)))     // 쉼 끝에서 4분, 도착까지 6분 → 쉼
        assertEquals(1, where(tight, t(9, 20)))
        // 쉼이 끝나고 5분 뒤, 다음 장 시작 8분 전: 둘 다 skew 창 → 원래 창이 가까운 쉼
        val gapped = listOf(ch(ChapterKind.DEPARTURE, 8, 0), ch(ChapterKind.STOP, 9, 0, 30), ch(ChapterKind.ARRIVAL, 9, 43))
        assertEquals(1, where(gapped, t(9, 35)))
    }

    @Test fun `시각을 모르는 사진은 도착 장`() {
        assertEquals(3, where(oneWay, null))
    }

    @Test fun `돌아오는 길이 있는 왕복`() {
        val round = listOf(ch(ChapterKind.DEPARTURE, 8, 52), ch(ChapterKind.ARRIVAL, 13, 20, 225), ch(ChapterKind.RETURN, 17, 5, homeMs = t(19, 40)))
        assertEquals(1, where(round, t(14, 0)))
        assertEquals(1, where(round, t(16, 58)))    // 도착 장 원래 창 [13:20, 17:05)
        assertEquals(2, where(round, t(17, 10)))
        assertEquals(2, where(round, t(20, 30)))    // 돌아오는 길의 끝도 열려 있다
        assertEquals(1, where(round, null))
    }

    @Test fun `장마다 촬영 시각순, 모든 장의 열쇠가 있다`() {
        val r = assignPhotos(oneWay, listOf(p("b", t(13, 50)), p("n", null), p("a", t(13, 30)), p("s", t(10, 0))))
        assertEquals(setOf(0, 1, 2, 3), r.keys)
        assertEquals(listOf("a", "b", "n"), r.getValue(3).map { it.id })
        assertEquals(listOf("s"), r.getValue(1).map { it.id })
        assertEquals(emptyList<AlbumPhoto>(), r.getValue(0))
    }

    @Test fun `trustedTakenAt - exif는 그대로, 올린 시각과 5분 안의 file은 버림, none은 null`() {
        val up = t(20, 0)
        assertEquals(t(13, 30), trustedTakenAt(t(13, 30), "exif", up))
        assertEquals(t(19, 57), trustedTakenAt(t(19, 57), "exif", up))
        assertNull(trustedTakenAt(t(19, 57), "file", up))
        assertNull(trustedTakenAt(t(20, 4), "file", up))
        assertEquals(t(13, 30), trustedTakenAt(t(13, 30), "file", up))
        assertNull(trustedTakenAt(t(13, 30), "none", up))
        assertNull(trustedTakenAt(null, "exif", up))
    }

    @Test fun `coverPhoto - 도착 장의 가로 사진 중 가장 큰 것, 없으면 첫 사진`() {
        val big = p("big", t(13, 40), 2048, 1536)
        val small = p("small", t(13, 30), 1024, 768)
        val tall = p("tall", t(13, 35), 1536, 2048)
        val early = p("early", t(10, 0), 1536, 2048)
        assertEquals(big, coverPhoto(oneWay, assignPhotos(oneWay, listOf(small, tall, big, early))))
        assertEquals(early, coverPhoto(oneWay, assignPhotos(oneWay, listOf(tall, early))))
        assertNull(coverPhoto(oneWay, assignPhotos(oneWay, emptyList())))
    }
}
