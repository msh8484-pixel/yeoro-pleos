package ai.yeoro.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** 한 번이라도 열어 본 앨범의 여행 id. 책장의 "새 앨범" 배지는 여기에 없는 앨범에만 붙는다. (스키마 변경 없이 prefs에 보관) */
object OpenedAlbums {
    private const val PREFS = "yeoro_home"
    private const val KEY = "opened_trip_ids"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun read(p: SharedPreferences): Set<Long> = p.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    private fun write(c: Context, ids: Set<Long>) { prefs(c).edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply() }

    fun mark(c: Context, id: Long) { val cur = read(prefs(c)); if (id !in cur) write(c, cur + id) }
    fun forget(c: Context, id: Long) { val cur = read(prefs(c)); if (id in cur) write(c, cur - id) }
    fun clear(c: Context) { prefs(c).edit().remove(KEY).apply() }

    fun flow(c: Context): Flow<Set<Long>> = callbackFlow {
        val p = prefs(c)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, k -> if (k == KEY || k == null) trySend(read(sp)) }
        p.registerOnSharedPreferenceChangeListener(l)
        trySend(read(p))
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }.conflate()
}
