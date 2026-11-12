package ai.yeoro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 앨범·회상 화면의 남색 바탕 토큰 (album-prototype/index.html). */
object AlbumColors {
    val Bg = Color(0xFF0E1220)
    val Surface = Color(0x0FFFFFFF)       // rgba(255,255,255,.06)
    val Line = Color(0x1FD6DEFF)          // rgba(214,222,255,.12)
    val Text = Color(0xFFEEF0F7)
    val Sub = Color(0xFF9AA1B8)
    val Amber = Color(0xFFFFB46A)
    val Coral = Color(0xFFFF8A7A)
}

private val AlbumScheme = darkColorScheme(
    primary = AlbumColors.Amber, onPrimary = AlbumColors.Bg, secondary = AlbumColors.Coral,
    background = AlbumColors.Bg, onBackground = AlbumColors.Text,
    surface = AlbumColors.Bg, onSurface = AlbumColors.Text, surfaceVariant = AlbumColors.Surface,
    onSurfaceVariant = AlbumColors.Sub, outline = AlbumColors.Line, outlineVariant = AlbumColors.Line,
)

/** 앨범·회상 화면: 남색 색상만 바꾸고 글씨체는 앱 전체와 같은 Pretendard. */
@Composable fun AlbumTheme(active: Boolean = true, content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val baseColors = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = if (active) AlbumScheme else baseColors,
        typography = base,
        content = content,
    )
}
