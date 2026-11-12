package ai.yeoro.ui.theme
import ai.yeoro.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
/** 앱 전체 글씨체: Pretendard 가변 폰트 하나 (SIL OFL 1.1, docs/PRETENDARD_OFL.txt) */
private fun pretendard(w: Int, fw: FontWeight) = Font(R.font.pretendard, fw, variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Pretendard = FontFamily(pretendard(400, FontWeight.Normal), pretendard(500, FontWeight.Medium), pretendard(600, FontWeight.SemiBold), pretendard(700, FontWeight.Bold))
val Dusk = Color(0xFFFFB46A)
/** 밝은 바탕에서 글자·선 아이콘에 쓰는 진한 앰버 (Dusk는 흰 바탕 대비가 낮다) */
val DuskDeep = Color(0xFFB0601A)
/** 앰버 강조색: 어두운 바탕이면 Dusk, 밝으면 DuskDeep */
@Composable fun duskInk(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Dusk else DuskDeep

private val Light = lightColorScheme(primary = Color(0xFF16181D), onPrimary = Color.White, secondary = Dusk, background = Color(0xFFF4F5F7), surface = Color.White, onSurface = Color(0xFF16181D), onSurfaceVariant = Color(0xFF6D727B))
private val Dark = darkColorScheme(primary = Color(0xFFEEF0F7), onPrimary = Color(0xFF0E1220), secondary = Dusk, background = Color(0xFF0E1220), surface = Color(0xFF161B2E), onSurface = Color(0xFFEEF0F7), onSurfaceVariant = Color(0xFF9AA1B8))

// Pleos 2560x1440 @160dpi 디스플레이: 한눈에 읽히는 크기, 모든 스타일 24sp 이상. body/label 줄 높이 1.5배, headline은 더 촘촘한 줄 높이(약 1.3배)
private fun t(w: FontWeight, size: Int) = TextStyle(fontFamily = Pretendard, fontWeight = w, fontSize = size.sp, lineHeight = (size * 3 / 2).sp)
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 72.sp),
    headlineMedium = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 64.sp),
    headlineSmall = t(FontWeight.SemiBold, 40),
    titleLarge = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 52.sp),
    titleMedium = t(FontWeight.SemiBold, 32),
    titleSmall = t(FontWeight.SemiBold, 28),
    bodyLarge = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 48.sp),
    bodyMedium = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 45.sp),
    bodySmall = t(FontWeight.Normal, 24),
    labelLarge = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 42.sp),
    labelMedium = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 36.sp),
    labelSmall = t(FontWeight.SemiBold, 24),
)

// IBM Plex Mono 미포함 -> 시스템 고정폭
val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 28.sp, lineHeight = 42.sp)

@Composable fun YeoroTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
