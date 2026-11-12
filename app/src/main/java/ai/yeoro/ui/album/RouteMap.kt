package ai.yeoro.ui.album

import ai.yeoro.ux.rememberMotionProgress
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 지도 위 핀: 장 이름과 좌표 */
data class MapPin(val name: String, val lat: Double, val lon: Double)

private val MapBg = Color(0xFF0B1020)
private val RouteInk = Color(0xFFFFCF8F)
private val Glow = Color(0x73FFB46A)          // rgba(255,180,106,.45)
private val LabelInk = Color(0xFFC9CFE6)

const val ROUTE_DELAY_MS = 300
const val ROUTE_DRAW_MS = 2600
const val PIN_GAP_MS = 600
/** 돌아오는 길 선의 투명도: 등고선(최대 약 0.16)보다 분명히 밝고 앰버 갈 길보다는 흐리게 */
private const val RETURN_ALPHA = 0.45f

/**
 * 남색 바탕 + 등고선 장식 위에 위치 샘플 경로를 앰버 선(4dp, 흐린 글로우 14dp)으로 그린다.
 * 모션 허용이면 경로를 2.6초 동안 그리고(PathMeasure), 이어서 핀이 600ms 간격으로 스프링으로 떨어진다. 아니면 처음부터 다 그린 상태.
 * 왕복이면 돌아오는 길(returnRoute)을 같은 색 alpha 0.45(RETURN_ALPHA), 2dp로 처음부터 흐리게 깔아 둔다(글로우·그리기 애니메이션 없음).
 * 투영·경로·등고선·라벨 배치는 크기가 바뀔 때만 다시 계산하고(drawWithCache), 프레임마다는 진행률만 읽는다.
 */
@Composable fun RouteMap(key: Any, route: List<Pair<Double, Double>>, pins: List<MapPin>, modifier: Modifier = Modifier,
                         returnRoute: List<Pair<Double, Double>> = emptyList()) {
    val draw = rememberMotionProgress("route-$key", tween(ROUTE_DRAW_MS, easing = FastOutSlowInEasing), delayMs = ROUTE_DELAY_MS)
    val pinStates: List<State<Float>> = pins.indices.map { i ->
        rememberMotionProgress("pin-$key-$i", spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
            delayMs = ROUTE_DELAY_MS + ROUTE_DRAW_MS + 150 + i * PIN_GAP_MS)
    }
    val measurer = rememberTextMeasurer()
    Box(modifier.drawWithCache {
        val w = size.width; val h = size.height
        val all = route + returnRoute + pins.map { it.lat to it.lon }
        val xy = projectRoute(all, w, h)
        val n = route.size + returnRoute.size   // 핀 좌표는 xy의 n번째부터
        val path = smoothPath(xy, 0, route.size)
        val returnPath = smoothPath(xy, route.size, returnRoute.size)
        val measure = PathMeasure().apply { setPath(path, false) }
        val length = measure.length
        val seg = Path()

        val contours = contourPaths(w, h)
        val contourStroke = Stroke(1.6.dp.toPx())
        val routeStroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val returnStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 14.dp.toPx(); strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND; color = Glow.toArgb()
            maskFilter = android.graphics.BlurMaskFilter(6.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        // 핀과 라벨 배치(겹치지 않게 네 방향 중 첫 빈자리)
        val pinR = 16.dp.toPx(); val dotR = 7.dp.toPx(); val ring = 4.dp.toPx(); val drop = 56.dp.toPx()
        val labelStyle = TextStyle(color = LabelInk, fontSize = 24.sp)
        val pad = 10.dp.toPx(); val gap = 22.dp.toPx()
        val placed = mutableListOf<Rect>()
        val labels = pins.mapIndexed { i, p ->
            val x = xy[2 * (n + i)]; val y = xy[2 * (n + i) + 1]
            val layout = measurer.measure(p.name, labelStyle)
            val lw = layout.size.width + 2 * pad; val lh = layout.size.height + pad
            val options = listOf(Offset(x + gap, y + gap * 0.6f), Offset(x + gap, y - gap * 0.6f - lh), Offset(x - gap - lw, y + gap * 0.6f), Offset(x - gap - lw, y - gap * 0.6f - lh))
            val fits = { o: Offset -> o.x >= 8 && o.y >= 8 && o.x + lw <= w - 8 && o.y + lh <= h - 8 }
            val pinRects = pins.indices.map { j -> val px = xy[2 * (n + j)]; val py = xy[2 * (n + j) + 1]; Rect(Offset(px, py), pinR) }
            val pick = options.firstOrNull { o -> val r = Rect(o, Size(lw, lh)); fits(o) && placed.none { it.overlaps(r) } && pinRects.none { it.overlaps(r) } }
                ?: options.firstOrNull(fits) ?: options[0]
            val rect = Rect(pick, Size(lw, lh)); placed += rect
            Triple(Offset(x, y), rect, layout)
        }

        onDrawBehind {
            drawRect(MapBg)
            contours.forEachIndexed { k, c -> drawPath(c, Color(0.59f, 0.67f, 0.9f, 0.05f + (k % 9 + 1) * 0.012f), style = contourStroke) }
            if (returnRoute.size > 1) drawPath(returnPath, RouteInk.copy(alpha = RETURN_ALPHA), style = returnStroke)
            val p = draw.value
            if (p > 0f && length > 0f) {
                seg.reset()
                measure.getSegment(0f, length * p.coerceAtMost(1f), seg, true)
                drawIntoCanvas { it.nativeCanvas.drawPath(seg.asAndroidPath(), glowPaint) }
                drawPath(seg, RouteInk, style = routeStroke)
            }
            labels.forEachIndexed { i, (at, rect, layout) ->
                val q = pinStates[i].value
                if (q <= 0f) return@forEachIndexed
                val a = q.coerceIn(0f, 1f)
                val c = Offset(at.x, at.y - (1f - q) * drop)
                drawCircle(MapBg.copy(alpha = a), pinR, c)
                drawCircle(RouteInk.copy(alpha = a), pinR - ring / 2, c, style = Stroke(ring))
                drawCircle(RouteInk.copy(alpha = a), dotR, c)
                drawRoundRect(MapBg.copy(alpha = 0.8f * a), rect.topLeft, rect.size, CornerRadius(rect.height / 2))
                drawText(layout, topLeft = Offset(rect.left + pad, rect.top + pad / 2), alpha = a)
            }
        }
    })
}

/** xy의 from번째 점부터 count개를 점 사이 중점을 지나는 2차 곡선으로 부드럽게 잇는다 */
private fun smoothPath(xy: FloatArray, from: Int, count: Int): Path = Path().apply {
    if (count == 0) return@apply
    val x = { i: Int -> xy[2 * (from + i)] }; val y = { i: Int -> xy[2 * (from + i) + 1] }
    moveTo(x(0), y(0))
    for (i in 1 until count) {
        val px = x(i - 1); val py = y(i - 1); val cx = x(i); val cy = y(i)
        if (i == count - 1) quadraticTo(px, py, cx, cy) else quadraticTo(px, py, (px + cx) / 2, (py + cy) / 2)
    }
}

/** 노이즈 섞인 타원 고리 몇 겹(시안의 drawDarkMap). 크기 기준으로 고정 위치, 결정적 위상. */
private fun contourPaths(w: Float, h: Float): List<Path> {
    val s = minOf(w / 1000f, h / 760f); val ox = (w - 1000f * s) / 2; val oy = (h - 760f * s) / 2
    val centers = listOf(floatArrayOf(620f, 300f, 260f), floatArrayOf(700f, 470f, 200f), floatArrayOf(330f, 230f, 180f), floatArrayOf(520f, 560f, 170f), floatArrayOf(760f, 150f, 150f))
    val phases = floatArrayOf(1.3f, 4.1f, 2.6f, 5.4f, 0.7f)
    return centers.flatMapIndexed { ci, (cx, cy, r) ->
        (1..9).map { k ->
            val rr = r * k / 9f
            Path().apply {
                var a = 0f
                while (a <= 2 * PI + 0.01) {
                    val m = 1 + 0.18f * sin(a * 3 + phases[ci] + k * 0.4f) + 0.08f * sin(a * 7 + phases[ci])
                    val x = ox + (cx + cos(a) * rr * m * 1.25f) * s; val y = oy + (cy + sin(a) * rr * m * 0.8f) * s
                    if (a == 0f) moveTo(x, y) else lineTo(x, y)
                    a += 0.08f
                }
                close()
            }
        }
    }
}
