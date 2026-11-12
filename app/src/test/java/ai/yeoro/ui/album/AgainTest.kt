package ai.yeoro.ui.album

import ai.yeoro.cloud.Festival
import ai.yeoro.cloud.OutsideInfo
import ai.yeoro.cloud.PlaceInfo
import ai.yeoro.cloud.TripOutside
import ai.yeoro.engine.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgainTest {
    private val naver = "https://map.naver.com/p/search/%EA%B0%95%EB%A6%89%20%EA%B2%BD%ED%8F%AC%ED%95%B4%EB%B3%80"
    private val festival = Festival("강릉커피축제", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 25), 4, 2.1, "https://www.coffeefestival.net/")

    private fun outside(placeHome: String?, fest: Festival? = null, departure: OutsideInfo? = null) = TripOutside(
        arrival = OutsideInfo(0, null, null, PlaceInfo(PlaceKind.TOURIST, "경포해변", placeHome), fest, null),
        departure = departure,
    )

    @Test fun `네이버 지도 검색 주소 - 한글은 UTF-8, 공백은 %20`() {
        assertEquals(naver, naverMapUrl("강릉 경포해변"))
        assertEquals("https://map.naver.com/p/search/A%2BB%20%26%20C", naverMapUrl("A+B & C"))
        assertEquals("https://map.naver.com/p/search/%EA%B0%95%EB%A6%89", naverMapUrl("  강릉 "))
    }

    @Test fun `홈페이지가 없으면 지도 링크 하나`() {
        assertEquals(listOf(AgainLink("지도에서 보기 · 네이버 지도", naver)), againLinks("강릉 경포해변", null))
        assertEquals(listOf(AgainLink("지도에서 보기 · 네이버 지도", naver)), againLinks("강릉 경포해변", outside(null)))
        assertEquals(listOf(AgainLink("지도에서 보기 · 네이버 지도", naver)), againLinks("강릉 경포해변", outside("")))
    }

    @Test fun `장소 홈페이지가 http(s)면 두 번째 링크`() {
        val l = againLinks("강릉 경포해변", outside("https://gn.go.kr/beach"))
        assertEquals(2, l.size)
        assertEquals(AgainLink("홈페이지 · 경포해변", "https://gn.go.kr/beach"), l[1])
        assertEquals("http://example.kr/", againLinks("강릉 경포해변", outside("http://example.kr/"))[1].url)
    }

    @Test fun `javascript·data·ftp·깨진 주소는 거부`() {
        for (bad in listOf("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,hi", "ftp://x.kr/", "https://", "https:// x.kr", "www.x.kr", "https://x.kr/\n<script>")) {
            assertEquals(bad, 1, againLinks("강릉 경포해변", outside(bad)).size)
            assertFalse(bad, isWebUrl(bad))
        }
        assertTrue(isWebUrl("HTTPS://WWW.X.KR/a?b=1"))
    }

    @Test fun `장소 홈페이지가 없거나 못 쓰면 축제 홈페이지로`() {
        val l = againLinks("강릉 경포해변", outside(null, festival))
        assertEquals(listOf(AgainLink("지도에서 보기 · 네이버 지도", naver), AgainLink("홈페이지 · 강릉커피축제", "https://www.coffeefestival.net/")), l)
        assertEquals("https://www.coffeefestival.net/", againLinks("강릉 경포해변", outside("javascript:void(0)", festival))[1].url)
        // 장소 홈페이지가 쓸 만하면 장소가 먼저, 최대 2개
        assertEquals(listOf("지도에서 보기 · 네이버 지도", "홈페이지 · 경포해변"), againLinks("강릉 경포해변", outside("https://gn.go.kr/", festival)).map { it.label })
    }

    @Test fun `출발 쪽(집 근처) 바깥의 홈페이지는 쓰지 않는다`() {
        val dep = OutsideInfo(0, null, null, PlaceInfo(PlaceKind.OTHER, "서울 인디스페이스", "https://indiespace.kr/"), festival, null)
        assertEquals(1, againLinks("강릉 경포해변", TripOutside(null, dep)).size)
        assertEquals(listOf("지도에서 보기 · 네이버 지도", "홈페이지 · 경포해변"), againLinks("강릉 경포해변", outside("https://gn.go.kr/", departure = dep)).map { it.label })
    }

    @Test fun `목적지 이름이 없으면 링크도 없다(쪽을 만들지 않는다)`() {
        assertEquals(emptyList<AgainLink>(), againLinks(null, outside("https://gn.go.kr/", festival)))
        assertEquals(emptyList<AgainLink>(), againLinks("  ", outside("https://gn.go.kr/", festival)))
    }

    @Test fun `단골 단계 줄`() {
        assertEquals("단골까지 두 번 남았어요 · 세 번째 방문이면 단골이에요", againStageLine(1))
        assertEquals("단골까지 한 번 남았어요 · 세 번째 방문이면 단골이에요", againStageLine(2))
        assertEquals("단골이에요", againStageLine(3))
        assertEquals("단골이에요", againStageLine(7))
    }
}
