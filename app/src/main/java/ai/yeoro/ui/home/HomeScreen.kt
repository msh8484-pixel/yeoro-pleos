package ai.yeoro.ui.home

import ai.yeoro.ui.common.PhotoImage
import ai.yeoro.service.regularStage
import ai.yeoro.ui.album.AgainPanel
import ai.yeoro.ui.theme.AlbumColors
import ai.yeoro.ui.theme.Pretendard
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.yeoro.ui.album.scene
import ai.yeoro.ui.live.lineColor
import ai.yeoro.ui.live.muted
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ux.LocalDrivingRestricted
import ai.yeoro.ux.LocalMotion
import ai.yeoro.ux.MotionLog
import ai.yeoro.ux.motionSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/*
 * 홈 "우리 가족 여행" (시안 장면 12). Pleos 테마(라이트/다크)를 따르고, 책 표지만 해 질 녘 일러스트를 가진다.
 * 넓은 화면: 왼쪽 책장 + 오른쪽 480dp 패널(열 수는 책장 폭으로 정한다). 좁은 창(분할 약 852px): 한 줄로 세로 스크롤, 패널은 책장 아래.
 * 움직임은 책을 누를 때 기울기(rotationY −12°) 하나뿐이고 motionSpec(LocalMotion)을 거친다.
 */

private val KoHeading = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val KoParagraph = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val InkDeep = Color(0xFF080A12)
private val BtnInk = Color(0xFF1B1206)
private val CoverSub = Color(0xFFD3D6E2)
private val MinTouch = 76.dp
private val WarnRed = Color(0xFFD9473F)
private val SideWidth = 480.dp
private val BookShape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 18.dp, bottomEnd = 18.dp)
private const val EMPTY = "아직 여행이 없어요. 다음 가족 나들이부터 조용히 기록할게요."

private enum class Tab(val label: String) { ALBUMS("여행 앨범"), REGULARS("정기 방문") }

@Composable fun HomeScreen(onOpenTrip: (Long) -> Unit, vm: HomeViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val again by vm.again.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    // 주행 중(UX 제한)에는 [다시 가기] 대화상자를 거둔다
    val restricted = LocalDrivingRestricted.current
    LaunchedEffect(restricted) { vm.setRestricted(restricted) }
    again?.takeIf { !restricted }?.let { AgainDialog(it, onClose = vm::closeAgain) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val u = ui ?: return@Surface
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val full = maxWidth
            val wide = full >= 1400.dp
            // 책 한 권 최소 300dp: 전체 화면(약 2440dp)에서 4열, Pleos 기본 창(약 1580dp)에서 3열, 분할 창에서 2열
            fun cols(main: androidx.compose.ui.unit.Dp) = ((main.value + 28) / 328).toInt().coerceIn(2, 4)
            val empty = u.books.isEmpty() && u.unconfirmed == null
            if (empty) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (wide) 56.dp else 32.dp)) {
                Header()
                Text(EMPTY, Modifier.padding(top = 48.dp).widthIn(max = 900.dp), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = KoParagraph), color = muted())
            } else if (wide) Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 56.dp, end = 40.dp, top = 48.dp, bottom = 40.dp)) {
                    Main(u, covers, onOpenTrip, vm, columns = cols(full - SideWidth - 96.dp))
                }
                Column(Modifier.width(SideWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    SidePanel(u, mapHeight = 400)
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Main(u, covers, onOpenTrip, vm, columns = cols(full - 64.dp))
                SidePanel(u, mapHeight = 340)
            }
        }
    }
}

@Composable private fun Header() {
    Text("우리 가족 여행", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
    Text("차가 기억한 길, 가족이 남긴 순간, 다시 갈 곳", style = MaterialTheme.typography.bodyMedium.copy(lineBreak = KoParagraph), color = muted())
}

@Composable private fun Main(u: HomeUi, covers: Map<Long, String>, onOpenTrip: (Long) -> Unit, vm: HomeViewModel, columns: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.ALBUMS) }
    Column {
        Header()
        u.unconfirmed?.let { UnconfirmedCardView(it, onKeep = { vm.keep(it.tripId) }, onDiscard = { vm.discard(it.tripId) }) }
        Tabs(tab) { tab = it }
        when (tab) {
            Tab.ALBUMS -> if (u.books.isEmpty()) Text(EMPTY, Modifier.padding(top = 32.dp), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = KoParagraph), color = muted())
                else Grid(u.books, columns, Modifier.padding(top = 32.dp)) { b, m -> BookCover(b, covers[b.tripId], m) { onOpenTrip(b.tripId) } }
            Tab.REGULARS -> if (u.regulars.isEmpty()) Text("같은 곳에 두 번 이상 다녀오면 여기에 한 권으로 모여요.", Modifier.padding(top = 32.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(lineBreak = KoParagraph), color = muted())
                else Grid(u.regulars, (columns - 1).coerceAtLeast(1), Modifier.padding(top = 32.dp)) { r, m -> RegularCell(r, covers[r.latestTripId], m, onOpen = { onOpenTrip(r.latestTripId) }, onAgain = { vm.openAgain(r) }) }
        }
    }
}

@Composable private fun <T> Grid(items: List<T>, columns: Int, modifier: Modifier, cell: @Composable (T, Modifier) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                row.forEach { cell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable private fun Tabs(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            val fg = if (on) MaterialTheme.colorScheme.background else muted()
            Box(Modifier.heightIn(min = MinTouch).clip(RoundedCornerShape(50))
                .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                .border(1.dp, if (on) MaterialTheme.colorScheme.onSurface else lineColor(), RoundedCornerShape(50))
                .selectable(on, role = Role.Tab) { onSelect(t) }.padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
                Text(t.label, style = MaterialTheme.typography.titleSmall, color = fg)
            }
        }
    }
}

/* ── 확인하지 않은 여행 ── */

/**
 * [지우기]는 되돌릴 수 없으니 두 번에 나눈다: 처음 누르면 카드가 "이 기록을 지울까요?"로 바뀌고 [취소]/[지우기].
 * 주행 중(UX 제한)에는 설정처럼 [지우기]를 막고 "차를 세우면 바꿀 수 있어요."를 보인다(확인 중이었다면 확인을 거둔다).
 */
@Composable private fun UnconfirmedCardView(c: UnconfirmedCard, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val restricted = LocalDrivingRestricted.current
    var confirming by remember(c.tripId) { mutableStateOf(false) }
    LaunchedEffect(restricted) { if (restricted) confirming = false }
    val confirm = confirming && !restricted
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(Modifier.padding(top = 28.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, if (confirm) WarnRed.copy(alpha = 0.7f) else Dusk.copy(alpha = 0.6f))) {
        FlowRow(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 360.dp).weight(1f)) {
                if (confirm) {
                    Text("이 기록을 지울까요?", style = MaterialTheme.typography.titleLarge, color = ink)
                    Text("되돌릴 수 없어요.", style = MaterialTheme.typography.bodyMedium.copy(lineBreak = KoParagraph), color = muted())
                } else {
                    Text("이날, 여행이었나요?", style = MaterialTheme.typography.titleLarge, color = ink)
                    Text(c.line, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = KoParagraph), color = muted())
                    if (restricted) Text("차를 세우면 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium.copy(lineBreak = KoParagraph), color = muted())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (confirm) {
                    OutlinedButton({ confirming = false }, Modifier.heightIn(min = MinTouch).widthIn(min = 160.dp), shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, lineColor())) {
                        Text("취소", style = MaterialTheme.typography.titleSmall, color = ink)
                    }
                    Button({ confirming = false; onDiscard() }, Modifier.heightIn(min = MinTouch).widthIn(min = 180.dp), shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WarnRed, contentColor = Color.White)) {
                        Text("지우기", style = MaterialTheme.typography.titleSmall)
                    }
                } else {
                    OutlinedButton({ confirming = true }, Modifier.heightIn(min = MinTouch).widthIn(min = 160.dp), enabled = !restricted, shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, lineColor())) {
                        Text("지우기", style = MaterialTheme.typography.titleSmall, color = if (restricted) muted().copy(alpha = 0.5f) else ink)
                    }
                    Button(onKeep, Modifier.heightIn(min = MinTouch).widthIn(min = 180.dp), shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Dusk, contentColor = BtnInk)) {
                        Text("남기기", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/* ── 책 ── */

/** 누르면 왼쪽 등을 축으로 −12° 열리고 8dp 떠오른다. 모션이 꺼져 있으면 애니메이션 없이 바로 그 모습. */
@Composable private fun PressTilt(key: Any, content: @Composable (Modifier, MutableInteractionSource) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val motion = LocalMotion.current
    LaunchedEffect(pressed) { if (pressed && motion) MotionLog.started("book-tilt $key") }
    val tilt by animateFloatAsState(if (pressed) 1f else 0f, motionSpec(tween(400, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))), label = "book-tilt")
    content(Modifier.graphicsLayer {
        rotationY = -12f * tilt; translationY = -8.dp.toPx() * tilt
        transformOrigin = TransformOrigin(0f, 0.5f); cameraDistance = 14f * density
    }, src)
}

@Composable private fun BookCover(b: Book, photo: String?, modifier: Modifier, onClick: () -> Unit) = PressTilt(b.tripId) { tilt, src ->
    Box(modifier.then(tilt).aspectRatio(3f / 4f).shadow(18.dp, BookShape).clip(BookShape)
        .clickable(src, indication = null, role = Role.Button, onClickLabel = "앨범 열기", onClick = onClick)) {
        Cover(b, b.tripId.toInt(), photo)
        if (b.isNew) Text("새 앨범", Modifier.align(Alignment.TopEnd).padding(16.dp).background(Dusk, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = BtnInk)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, end = 24.dp, bottom = 28.dp)) {
            Text(b.title, style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 44.sp, lineBreak = KoHeading),
                color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(b.date, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal), color = CoverSub)
            Text(b.place, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal, lineBreak = KoParagraph), color = CoverSub, maxLines = 2)
        }
    }
}

/** 정기 방문 묶음: 표지(누르면 가장 최근 앨범) + [다시 가기](76dp). 주행 중에는 버튼을 막고 "차를 세우면 열 수 있어요" */
@Composable private fun RegularCell(r: Regular, photo: String?, modifier: Modifier, onOpen: () -> Unit, onAgain: () -> Unit) {
    val restricted = LocalDrivingRestricted.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RegularCover(r, photo, Modifier.fillMaxWidth(), onOpen)
        OutlinedButton(onAgain, Modifier.fillMaxWidth().heightIn(min = MinTouch), enabled = !restricted, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, if (restricted) lineColor() else Dusk.copy(alpha = 0.7f))) {
            Text("다시 가기", style = MaterialTheme.typography.titleSmall, color = if (restricted) muted().copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface)
        }
        if (restricted) Text("차를 세우면 열 수 있어요", style = MaterialTheme.typography.bodyMedium.copy(lineBreak = KoParagraph), color = muted())
    }
}

/** [다시 가기] 대화상자: 앨범 마지막 쪽과 같은 QR 패널(남색 바탕) + [닫기] */
@Composable private fun AgainDialog(s: AgainSheet, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 1500.dp
            Column(Modifier.widthIn(max = 2000.dp).clip(RoundedCornerShape(32.dp)).background(AlbumColors.Bg).border(1.dp, AlbumColors.Line, RoundedCornerShape(32.dp))
                .verticalScroll(rememberScrollState()).padding(if (wide) 56.dp else 36.dp)) {
                ProvideTextStyle(TextStyle(fontFamily = Pretendard)) {
                    AgainPanel(s.dest, s.links, s.visit, wide, motionKey = "home-${s.dest}")
                }
                Button(onClose, Modifier.padding(top = 32.dp).align(Alignment.End).heightIn(min = 96.dp).widthIn(min = 240.dp), shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AlbumColors.Surface, contentColor = AlbumColors.Text)) {
                    Text("닫기", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable private fun RegularCover(r: Regular, photo: String?, modifier: Modifier, onClick: () -> Unit) = PressTilt("regular-${r.latestTripId}") { tilt, src ->
    Box(modifier.then(tilt).aspectRatio(4f / 3f).shadow(18.dp, BookShape).clip(BookShape)
        .clickable(src, indication = null, role = Role.Button, onClickLabel = "가장 최근 앨범 열기", onClick = onClick)) {
        Cover(r.cover, r.latestTripId.toInt() + 101, photo)
        Text("${r.count}번 방문", Modifier.align(Alignment.TopEnd).padding(16.dp).background(Dusk, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = BtnInk)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, end = 24.dp, bottom = 28.dp)) {
            Text(r.name, style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 52.sp, lineBreak = KoHeading),
                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.dates, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal, lineBreak = KoParagraph), color = CoverSub)
        }
    }
}

/** 표지 사진(없으면 일러스트) + 아래 어둡게 + 왼쪽 책등 그림자 */
@Composable private fun BoxScope.Cover(b: Book, seed: Int, photo: String? = null) {
    // 표지 사진이 있으면 사진(앨범 표지와 같은 사진), 없거나 읽는 중이면 일러스트
    PhotoImage(photo, Modifier.matchParentSize(), maxPx = 900) { Box(Modifier.matchParentSize().scene(b.hour, b.rained, seed = seed, sea = b.sea)) }
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to InkDeep.copy(alpha = 0.9f))))
    Box(Modifier.fillMaxHeight().width(18.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f)))))
}

/* ── 오른쪽 패널 ── */

@Composable private fun SidePanel(u: HomeUi, mapHeight: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("우리 가족 지도", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            FamilyMap(u.dots, Modifier.fillMaxWidth().height(mapHeight.dp))
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Kpi("%,d".format(u.stats.kmThisYear), "올해 km", Modifier.weight(1f))
            Kpi("${u.stats.places}", "간 곳", Modifier.weight(1f))
            Kpi("${u.stats.trips}", "여행 수", Modifier.weight(1f))
        }
        if (u.levels.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("단골 단계", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            u.levels.forEach { (name, n) -> LevelRow(name, n) }
            Text("1회 스탬프 · 2회 그때 vs 지금 · 3회 단골", style = MaterialTheme.typography.labelMedium.copy(lineBreak = KoParagraph), color = muted())
        }
    }
}

@Composable private fun Kpi(value: String, label: String, modifier: Modifier) {
    Column(modifier.fillMaxHeight().border(1.dp, lineColor(), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(value, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 52.sp, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal, lineBreak = KoParagraph), color = muted())
    }
}

@Composable private fun LevelRow(name: String, visits: Int) {
    val stage = regularStage(visits)
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = KoParagraph), color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Text("${visits}번 방문", style = MaterialTheme.typography.labelMedium, color = muted())
        }
        Row(Modifier.width(180.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (1..3).forEach { i -> Box(Modifier.weight(1f).height(14.dp).background(if (i <= stage) Dusk else off, RoundedCornerShape(7.dp))) }
        }
    }
}

/** 지도 타일 없이 경위선만 옅게 그리고, 같은 목적지 묶음마다 방문 횟수만큼 큰 점과 숫자, 옆에 짧은 이름. */
@Composable private fun FamilyMap(dots: List<MapDot>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val bg = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background, 0.6f)
    val nameColor = MaterialTheme.colorScheme.onSurface
    val haloColor = Dusk.copy(alpha = 0.22f)
    val numStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, color = BtnInk, fontFeatureSettings = "tnum")
    val nameStyle = MaterialTheme.typography.labelMedium.copy(color = nameColor)
    Canvas(modifier.clip(RoundedCornerShape(24.dp)).background(bg).border(1.dp, lineColor(), RoundedCornerShape(24.dp))) {
        val pad = 24.dp.toPx()
        val w = size.width - 2 * pad; val h = size.height - 2 * pad
        fun p(lat: Double, lon: Double) = projectKorea(lat, lon, w, h).let { (x, y) -> Offset(x + pad, y + pad) }
        KoreaLats.forEach { la -> drawLine(grid, p(la, 124.5), p(la, 131.0), strokeWidth = 1.5f) }
        KoreaLons.forEach { lo -> drawLine(grid, p(33.0, lo), p(38.7, lo), strokeWidth = 1.5f) }
        dots.sortedBy { it.count }.forEach { d ->
            val c = p(d.lat, d.lon); val r = dotRadiusDp(d.count).dp.toPx()
            drawCircle(haloColor, r * 1.6f, c)
            drawCircle(Dusk, r, c)
            val num = measurer.measure("${d.count}", numStyle)
            drawText(num, topLeft = Offset(c.x - num.size.width / 2f, c.y - num.size.height / 2f))
            val label = measurer.measure(d.name.substringBefore(' '), nameStyle)
            // 이름은 점 오른쪽, 넘치면 왼쪽
            val lx = if (c.x + r + 10.dp.toPx() + label.size.width <= size.width - 8.dp.toPx()) c.x + r + 10.dp.toPx() else c.x - r - 10.dp.toPx() - label.size.width
            drawText(label, topLeft = Offset(lx, c.y - label.size.height / 2f))
        }
    }
}
