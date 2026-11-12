package ai.yeoro.cloud

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Base64

/**
 * 설치마다 한 번 만드는 기기 비밀(SecureRandom 32바이트, base64url 43자). 서버에는 sha256만 저장되고,
 * 이 값을 아는 차만 그 여행의 사진 목록을 보고 여행을 지울 수 있다. 로그에 찍지 않는다.
 */
class DeviceSecret(private val prefs: SharedPreferences, private val random: SecureRandom = SecureRandom()) {
    val value: String by lazy {
        synchronized(this) {
            prefs.getString(KEY, null) ?: Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
                .also { prefs.edit().putString(KEY, it).commit() }
        }
    }

    companion object {
        const val PREFS = "yeoro_cloud"
        private const val KEY = "device"
        @Volatile private var instance: DeviceSecret? = null
        fun get(c: Context): DeviceSecret = instance ?: synchronized(this) {
            instance ?: DeviceSecret(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
