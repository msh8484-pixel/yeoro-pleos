package ai.yeoro.ui

import ai.yeoro.EngineBus
import ai.yeoro.service.RecorderService
import ai.yeoro.ux.MotionLog
import android.content.pm.ApplicationInfo
import android.util.Log
import ai.yeoro.ui.theme.YeoroTheme
import ai.yeoro.ux.DrivingRestriction
import ai.yeoro.ux.LocalMotion
import ai.yeoro.ux.LocalDrivingRestricted
import ai.yeoro.ux.isReducedMotion
import ai.yeoro.ux.motionAllowed
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    // 권한 결정 뒤에 서비스를 시작해야 CAR_SPEED 구독이 허용된 상태로 붙는다. 거부해도 서비스는 뜬다.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startRecorder() }

    private lateinit var driving: DrivingRestriction

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        driving = DrivingRestriction(this).also { it.start() }
        ai.yeoro.debug.DebugHooks.restoreUi(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            MotionLog.listener = { Log.d("YeoroMotion", "animate $it restricted=${driving.restricted.value}") }
        val wanted = buildList {
            add("android.car.permission.CAR_SPEED")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
        if (b == null) handleOpen(intent)
        setContent {
            val restricted by driving.restricted.collectAsState()
            val motion = motionAllowed(restricted, isReducedMotion(this))
            CompositionLocalProvider(LocalMotion provides motion, LocalDrivingRestricted provides restricted) { YeoroTheme { YeoroApp() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** 도착 알림에서 열 때: 지금 탭으로 */
    private fun handleOpen(i: Intent?) {
        if (i?.getStringExtra(EXTRA_OPEN) == Routes.LIVE) { openTab.value = Routes.LIVE; i.removeExtra(EXTRA_OPEN) }
    }

    companion object {
        private const val EXTRA_OPEN = "open"
        /** 열어야 할 탭 (YeoroApp이 옮긴 뒤 null로 되돌린다) */
        val openTab = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        /** 앱을 지금 탭으로 여는 인텐트 (이미 떠 있으면 onNewIntent로 받는다) */
        fun openLiveIntent(c: android.content.Context): Intent = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, Routes.LIVE)
    }

    override fun onDestroy() {
        driving.stop()
        super.onDestroy()
    }

    private fun startRecorder() =
        ContextCompat.startForegroundService(this, Intent(this, RecorderService::class.java))
}
