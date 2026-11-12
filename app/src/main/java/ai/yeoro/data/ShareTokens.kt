package ai.yeoro.data

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Base64

/**
 * 여행마다 한 번 만드는 사진 올리기 공유 토큰(128비트 난수, base64url 22자). 여행 번호는 짐작할 수 있어 주소에 쓰지 않는다.
 * 스키마 변경 없이 prefs("yeoro_share")에 여행 id → 토큰("t_<id>"), 서버 등록 표시("r_<id>")로 보관한다. 여행을 지우면 둘 다 지운다.
 */
class ShareTokens(private val prefs: SharedPreferences, private val random: SecureRandom = SecureRandom()) {
    /** 이 여행의 토큰. 없으면 만들어 저장한다(같은 여행은 늘 같은 토큰). */
    @Synchronized fun token(tripId: Long): String {
        prefs.getString(key(tripId), null)?.let { return it }
        val t = newToken(random)
        prefs.edit().putString(key(tripId), t).apply()
        return t
    }
    /** 이미 만든 토큰만 (QR을 띄운 적 없는 여행은 null) */
    @Synchronized fun tokenOrNull(tripId: Long): String? = prefs.getString(key(tripId), null)
    /** 서버에 여행 등록을 마쳤는지 (CloudSync) */
    @Synchronized fun isRegistered(tripId: Long): Boolean = prefs.getBoolean(regKey(tripId), false)
    @Synchronized fun markRegistered(tripId: Long) { prefs.edit().putBoolean(regKey(tripId), true).apply() }
    /** 토큰이 아직 그대로일 때만 등록 표시 (등록 중에 여행을 지웠으면 표시하지 않는다: 같은 id가 다시 쓰여도 잘못 붙지 않게) */
    @Synchronized fun markRegisteredIf(tripId: Long, token: String): Boolean =
        (prefs.getString(key(tripId), null) == token).also { if (it) markRegistered(tripId) }
    /** 토큰이 있는 모든 여행: 여행 id → 토큰 */
    @Synchronized fun all(): Map<Long, String> = prefs.all.mapNotNull { (k, v) ->
        if (k.startsWith("t_") && v is String) k.removePrefix("t_").toLongOrNull()?.let { it to v } else null
    }.toMap()
    /** 토큰과 등록 표시를 함께 지운다 */
    @Synchronized fun forget(tripId: Long) { prefs.edit().remove(key(tripId)).remove(regKey(tripId)).apply() }

    companion object {
        const val PREFS = "yeoro_share"
        private fun key(id: Long) = "t_$id"
        private fun regKey(id: Long) = "r_$id"
        fun newToken(random: SecureRandom = SecureRandom()): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes))
        @Volatile private var instance: ShareTokens? = null
        fun get(c: Context): ShareTokens = instance ?: synchronized(this) {
            instance ?: ShareTokens(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
