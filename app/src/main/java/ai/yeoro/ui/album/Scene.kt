package ai.yeoro.ui.album

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import kotlin.math.sin

/* 앨범 표지·콜라주·책장 책 표지가 함께 쓰는 사진 자리 일러스트 (2b에서 실제 사진으로 바뀌는 자리). */

/** 바다 그림을 쓸 목적지인지(이름만 보고 고르는 2a 자리표시 규칙) */
internal fun seaLike(dest: String?) = dest != null && listOf("해변", "바다", "해수욕장", "항", "포구", "해안").any { it in dest }

private class Palette(val sky: List<Color>, val sun: Color, val far: Color, val near: Color, val sea: List<Color>, val ground: Color)

/** 시간대(도착 시각 등)와 비 여부로 고른 색. 비가 온 날은 하늘을 조금 잿빛으로 섞는다. */
private fun palette(hour: Int, rained: Boolean): Palette {
    val base = when (hour) {
        in 5..10 -> Palette(listOf(Color(0xFF7FA6D3), Color(0xFFC9D8EA), Color(0xFFF6D7B0)), Color(0xFFFFE8B0), Color(0xFF7E8FB0), Color(0xFF45557A),
            listOf(Color(0xFF5E93BE), Color(0xFF2C5A80)), Color(0xFFE9D3AE))
        in 11..16 -> Palette(listOf(Color(0xFF5F97CF), Color(0xFFA9CBEA), Color(0xFFF9E2C2)), Color(0xFFFFF3D6), Color(0xFF6F86AE), Color(0xFF3A4C72),
            listOf(Color(0xFF3F86B5), Color(0xFF1E4E75)), Color(0xFFEBD6B2))
        in 17..19 -> Palette(listOf(Color(0xFF3B3F7A), Color(0xFFB9708A), Color(0xFFFFB46A)), Color(0xFFFFD49A), Color(0xFF5A4F82), Color(0xFF2C2A52),
            listOf(Color(0xFF6A5F8E), Color(0xFF26294F)), Color(0xFFCFA68A))
        else -> Palette(listOf(Color(0xFF0E1430), Color(0xFF232A57), Color(0xFF3B3F7A)), Color(0xFFEEF0F7), Color(0xFF2B3360), Color(0xFF151A38),
            listOf(Color(0xFF243063), Color(0xFF0E1430)), Color(0xFF3A3A5A))
    }
    if (!rained) return base
    val grey = Color(0xFF9AA4B8)
    return Palette(base.sky.map { lerp(it, grey, 0.28f) }, base.sun, lerp(base.far, grey, 0.2f), base.near, base.sea.map { lerp(it, grey, 0.15f) }, base.ground)
}

/**
 * 하늘 그라디언트, 해 빛무리, 먼 산 두 겹, (바다면) 수평선과 물결 또는 (아니면) 길. seed로 산 모양을 바꾼다.
 * 모든 경로는 크기가 바뀔 때만 만든다.
 */
internal fun Modifier.scene(hour: Int, rained: Boolean, seed: Int, sea: Boolean, hourOverride: Int? = null) = drawWithCache {
    val pal = palette(hourOverride ?: hour, rained)
    val w = size.width; val h = size.height
    val horizon = h * if (sea) 0.58f else 0.62f
    var r = seed * 9301 + 49297
    fun rnd(): Float { r = (r * 1103515245 + 12345) and 0x7fffffff; return (r % 10000) / 10000f }
    fun ridge(base: Float, amp: Float, freq: Float): Path {
        val p1 = rnd() * 6.3f; val p2 = rnd() * 6.3f
        return Path().apply {
            moveTo(0f, h); var x = 0f
            while (x <= w) { lineTo(x, base - amp * (0.6f * sin(x / w * freq + p1) + 0.4f * sin(x / w * freq * 2.3f + p2))); x += w / 60f }
            lineTo(w, h); close()
        }
    }
    val sky = Brush.verticalGradient(pal.sky, endY = horizon)
    val sunC = Offset(w * (0.25f + 0.5f * rnd()), horizon - h * (0.18f + 0.12f * rnd()))
    val sunGlow = Brush.radialGradient(listOf(pal.sun.copy(alpha = 0.9f), pal.sun.copy(alpha = 0.25f), Color.Transparent), sunC, h * 0.32f)
    val far = ridge(horizon - h * 0.02f, h * 0.10f, 5f + rnd() * 3f)
    val near = ridge(horizon + h * (if (sea) 0.0f else 0.06f), h * 0.07f, 7f + rnd() * 4f)
    val seaBrush = Brush.verticalGradient(pal.sea, startY = horizon, endY = h)
    val shimmer = (0 until 14).map { k -> Triple(rnd() * w, horizon + (h - horizon) * (0.1f + 0.8f * k / 14f), w * (0.05f + 0.12f * rnd())) }
    val sand = Path().apply { moveTo(0f, h); lineTo(0f, h * 0.88f); quadraticTo(w * 0.45f, h * 0.80f, w, h * 0.93f); lineTo(w, h); close() }
    val front = ridge(horizon + h * 0.18f, h * 0.05f, 4f + rnd() * 3f)
    val clouds = if (rained) (0 until 4).map { Offset(rnd() * w, h * (0.08f + 0.25f * rnd())) to w * (0.12f + 0.1f * rnd()) } else emptyList()
    val sunR = h * 0.06f
    onDrawBehind {
        drawRect(sky)
        drawCircle(sunGlow, h * 0.32f, sunC)
        drawCircle(pal.sun, sunR, sunC)
        clouds.forEach { (c, rw) ->
            // 겹친 타원 세 겹으로 가장자리를 부드럽게
            for (k in 0..2) { val f = 1f - k * 0.25f; drawOval(Color.White.copy(alpha = 0.09f), Offset(c.x - rw * f, c.y - rw * 0.2f * f), Size(rw * 2 * f, rw * 0.4f * f)) }
        }
        drawPath(far, pal.far.copy(alpha = 0.85f))
        if (sea) {
            drawRect(seaBrush, Offset(0f, horizon), Size(w, h - horizon))
            shimmer.forEach { (x, y, len) -> drawLine(Color.White.copy(alpha = 0.18f), Offset(x, y), Offset(x + len, y), strokeWidth = 2.5f) }
            drawPath(sand, pal.ground)
        } else {
            drawPath(near, pal.near)
            drawPath(front, lerp(pal.near, Color.Black, 0.3f))
        }
    }
}
