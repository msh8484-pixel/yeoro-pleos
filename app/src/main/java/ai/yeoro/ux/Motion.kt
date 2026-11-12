package ai.yeoro.ux

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * true = 모션 허용. 주행 제한 중이거나 시스템 애니메이터 배율이 0이면 false.
 * 모든 애니메이션(AnimatedVisibility, animateContentSize, 자동 스크롤 포함)은 반드시 LocalMotion.current를 확인해야 한다.
 */
val LocalMotion = staticCompositionLocalOf { true }

/** true = 주행 중 UX 제한(편집·긴 입력 금지). MainActivity가 DrivingRestriction 값을 넣는다. 모르면 안전하게 제한 */
val LocalDrivingRestricted = androidx.compose.runtime.compositionLocalOf { true }

/**
 * true = 이 화면의 등장 연출은 이미 끝난 것으로 본다(rememberMotionProgress가 처음부터 1). 주행 제한으로 가려졌던 앨범 단계가
 * 다시 보일 때 제목 타이핑·숫자 올리기 같은 등장 연출을 처음부터 다시 돌리지 않게 한다. 페이지 넘김 같은 상호작용 모션은 그대로다.
 */
val LocalIntroDone = staticCompositionLocalOf { false }

fun motionAllowed(restricted: Boolean, reducedMotionSetting: Boolean): Boolean = !restricted && !reducedMotionSetting

/** 시스템 애니메이터 배율이 0이면 true(모션 줄이기). */
fun isReducedMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/** 모션이 실제로 재생을 시작할 때 알린다. 디버그 빌드에서 MainActivity가 로그를 붙인다(주행 중 0건 확인용). */
object MotionLog {
    @Volatile var listener: ((String) -> Unit)? = null
    fun started(what: String) { listener?.invoke(what) }
}

@Composable
fun <T> motionSpec(spec: AnimationSpec<T>): AnimationSpec<T> = if (LocalMotion.current) spec else snap()

/**
 * 허용이면 0→1 tween, 아니면 바로 1. key마다 한 번만 재생하고, 1에 도달하면 게이트가 바뀌어도 1을 유지한다.
 * (허용 false→true 전환 때 다시 재생하지 않는다.)
 */
@Composable
fun rememberMotionProgress(key: Any?, durationMs: Int, delayMs: Int = 0): State<Float> = rememberMotionProgress(key, tween(durationMs), delayMs)

/**
 * spec(예: spring)으로 0→1. 규칙은 위와 같다: 허용일 때만 재생, key마다 한 번, 끝나면 1에 고정. 스프링은 1을 넘었다 돌아올 수 있다.
 * active: false인 동안은 시작하지 않고 지금 값(처음이면 0)에 멈춰 있다. pager가 미리 그려 둔 옆 쪽처럼 아직 보이지 않는 곳의
 * 등장 연출을 보일 때까지 미룬다. 모션이 꺼져 있으면 active와 상관없이 바로 1.
 */
@Composable
fun rememberMotionProgress(key: Any?, spec: AnimationSpec<Float>, delayMs: Int = 0, active: Boolean = true): State<Float> {
    val allowed = LocalMotion.current
    val done = LocalIntroDone.current
    val progress = remember(key) { Animatable(if (allowed && !done) 0f else 1f) }
    LaunchedEffect(key, allowed, active) {
        if (!allowed) { progress.snapTo(1f); return@LaunchedEffect }
        if (!active) return@LaunchedEffect
        if (progress.value < 1f) {
            val first = progress.value == 0f
            if (first && delayMs > 0) delay(delayMs.toLong())
            MotionLog.started("progress key=$key")
            progress.animateTo(1f, spec)
        }
    }
    return progress.asState()
}
