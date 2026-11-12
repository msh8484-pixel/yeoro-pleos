package ai.yeoro.cloud

import android.content.SharedPreferences
import java.time.LocalDate

/**
 * 연도별 쉬는 날(천문연 특일, isHoliday Y) 캐시. prefs `yeoro_holidays`에 "y_2026" → "2026-01-01,2026-02-16,…".
 * 판단 엔진의 CalendarInfo.holiday가 매 틱 읽는다. 캐시가 없으면 false(주말 판단만, 2a와 같음).
 * consent: 지금 서버를 불러도 되는가(동의·키·기어, CloudSync.canCallNow). 아니면 refresh는 아무것도 부르지 않는다.
 */
class Holidays(private val prefs: SharedPreferences, private val api: CloudApi?, private val consent: () -> Boolean) {
    @Volatile private var memo: Pair<Int, Set<LocalDate>>? = null

    fun isHoliday(d: LocalDate): Boolean = year(d.year).contains(d)

    /** 그해 목록을 받아 바꿔 넣는다. 받았으면 true. 실패하면 캐시를 그대로 둔다 */
    suspend fun refresh(year: Int): Boolean {
        val a = api ?: return false
        if (!consent()) return false
        val r = a.holidays(year)
        if (r !is CloudResult.Ok) return false
        prefs.edit().putString(key(year), r.value.filter { it.year == year }.sorted().joinToString(",")).apply()
        memo = null
        return true
    }

    private fun year(y: Int): Set<LocalDate> {
        memo?.let { (yy, set) -> if (yy == y) return set }
        val set = prefs.getString(key(y), null).orEmpty().split(",").mapNotNull { s -> runCatching { LocalDate.parse(s.trim()) }.getOrNull() }.toSet()
        memo = y to set
        return set
    }

    private fun key(y: Int) = "y_$y"
}
