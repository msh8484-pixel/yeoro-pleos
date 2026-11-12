package ai.yeoro.ui.live
import ai.yeoro.EngineBus
import ai.yeoro.engine.TripState
import ai.yeoro.ui.theme.mono
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 판단 엔진 상태의 한국어 이름 (시연 화면에 영어 상태 이름을 보이지 않게) */
fun stateName(s: TripState?): String = when (s) {
    TripState.OFF -> "기록 꺼짐"
    TripState.DAILY -> "일상"
    TripState.CANDIDATE -> "여행 후보"
    TripState.TRIP -> "여행 중"
    TripState.STAYING -> "머무는 중"
    TripState.ALBUM_READY -> "앨범 준비"
    null -> "신호 기다리는 중"
}

/** 디버그 빌드 전용 "판단 엔진" 패널. dbg debug_panel false로 숨긴다(시연 기본). */
@Composable fun EngineDebug(modifier: Modifier = Modifier) {
    val debuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val show by EngineBus.debugPanel.collectAsStateWithLifecycle()
    if (!debuggable || !show) return
    val score by EngineBus.lastScore.collectAsStateWithLifecycle()
    // 동의가 꺼져 있으면 꺼진 엔진은 점수를 내지 않아 마지막 점수가 낡은 값으로 남는다: 꺼짐으로 보인다
    val consent by ai.yeoro.settings.Settings.get(LocalContext.current).consent.collectAsStateWithLifecycle()
    val u = score?.takeIf { consent == true }
    val st = if (consent == true) u?.state else TripState.OFF
    Card(modifier) { Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("판단 엔진", style = MaterialTheme.typography.titleLarge)
        u?.score?.signals?.forEach { Text("${it.label} (${it.source})  ${if (it.points > 0) "+" else ""}${it.points}", style = mono) }
        Text("여행 점수 ${u?.score?.total ?: 0} · ${stateName(st)}", style = MaterialTheme.typography.headlineMedium)
    } }
}
