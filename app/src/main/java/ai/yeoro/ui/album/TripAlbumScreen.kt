package ai.yeoro.ui.album

import ai.yeoro.cloud.OutsideChipData
import ai.yeoro.cloud.OutsideChipKind
import ai.yeoro.cloud.outsideChips
import ai.yeoro.cloud.showExampleOutside
import ai.yeoro.ui.common.PhotoImage
import ai.yeoro.ui.live.LiveIcons
import ai.yeoro.ui.theme.AlbumColors
import ai.yeoro.ui.theme.Pretendard
import ai.yeoro.ux.LocalDrivingRestricted
import ai.yeoro.ux.LocalIntroDone
import ai.yeoro.ux.LocalMotion
import ai.yeoro.ux.motionSpec
import ai.yeoro.ux.rememberMotionProgress
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 여행 앨범 (시안 장면 6 공개 → 8 표지 → 9·10 장 넘기기). 남색 + 앰버, 글씨체는 Pretendard.
 * 모든 움직임은 LocalMotion을 거친다(rememberMotionProgress, motionSpec). 모션이 꺼져 있으면 처음부터 최종 모습이다.
 */

// 한국어는 어절 단위로 줄을 바꾼다(단어 중간 끊김 방지). 제목은 균형 잡힌 줄바꿈.
private val KoHeading = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val KoParagraph = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

private val InkDeep = Color(0xFF090B14)
private val Paper = Color(0xFFF7F3EC)
private val BtnInk = Color(0xFF1B1206)
private val ChipIcon = Color(0xFFFFCF8F)
private val TagInk = Color(0xFF8A92B0)
private val TagLine = Color(0xFF2A3150)
private val Diary = Color(0xFFE8E6F0)
private val WarnInk = Color(0xFFFF8A80)
private val DialogBg = Color(0xFF161B2E)
private val MinTouch = 76.dp
private val TimeSpace = Regex("(\\d+시) (\\d+분)")
private val CountSpace = Regex("(한|두|세|네|다섯|여섯|일곱|여덟|아홉|열) (개|자리|명)")

/** onDeleted: [이 여행 지우기] 뒤 홈(우리 가족 여행)으로 돌아가기 */
@Composable fun TripAlbumScreen(onDeleted: () -> Unit = {}, vm: TripAlbumViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 기본 글자는 Pretendard (제목·기록문은 아래에서 굵기·크기만 따로 준다)
    Box(Modifier.fillMaxSize().albumBackdrop()) { ProvideTextStyle(TextStyle(fontFamily = Pretendard)) {
        when (val s = state) {
            AlbumState.Loading -> {}
            AlbumState.Empty -> Column(Modifier.padding(64.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("앨범", fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 56.sp, color = AlbumColors.Text)
                Text("여행을 다녀오면 차가 기억한 길로 앨범을 만들어 드려요.", fontSize = 30.sp, color = AlbumColors.Sub)
            }
            is AlbumState.Ready -> AlbumBook(s.album, AlbumActions(
                removePhoto = { id -> vm.removePhoto(s.album.tripId, id) },
                deleteTrip = { vm.deleteTrip(s.album.tripId, onDeleted) },
            ))
        }
    } }
}

private enum class Stage { REVEAL, COVER, CHAPTERS }

/** 정차 중에만 쓰는 편집: 사진 빼기, 여행 지우기 */
private class AlbumActions(val removePhoto: (String) -> Unit, val deleteTrip: () -> Unit)

/**
 * 주행 중(UX 제한)에는 공개·표지·장 어디에 있든 정적인 요약(제목·날짜와 곳·안내 한 줄)만 그린다. 단계와 펼친 장은 이 함수가 들고 있어
 * 제한이 풀리면 있던 자리로 돌아간다. 제한 중에 가려진 단계의 등장 연출(제목 타이핑·숫자·표지 떠오름·사진 칸)은 다시 돌지 않는다.
 */
@Composable private fun AlbumBook(a: AlbumData, actions: AlbumActions) {
    var stage by rememberSaveable(a.tripId) { mutableStateOf(Stage.REVEAL) }
    var page by rememberSaveable(a.tripId) { mutableIntStateOf(0) }
    // 제한 중에 가려진 단계: 돌아왔을 때 그 단계의 등장 연출을 건너뛴다. 사용자가 다른 단계로 넘어가면 그 단계는 평소처럼 연출한다
    var settled by rememberSaveable(a.tripId) { mutableStateOf<Stage?>(null) }
    val restricted = LocalDrivingRestricted.current
    LaunchedEffect(restricted) { if (restricted) settled = stage }
    BackHandler(enabled = !restricted && stage != Stage.REVEAL) { stage = Stage.entries[stage.ordinal - 1] }
    if (restricted) { DrivingAlbum(a); return }
    Crossfade(stage, animationSpec = if (LocalMotion.current) tween(500) else snap(), label = "album-stage") { st ->
        CompositionLocalProvider(LocalIntroDone provides (st == settled)) {
            when (st) {
                Stage.REVEAL -> RevealPage(a) { stage = Stage.COVER }
                Stage.COVER -> CoverPage(a, actions) { stage = Stage.CHAPTERS }
                Stage.CHAPTERS -> ChapterBook(a, page, actions) { page = it }
            }
        }
    }
}

/** 주행 중 앨범: 움직임·문단·칩·숫자 없이 제목(크게)과 날짜·곳, 안내 한 줄. */
@Composable private fun DrivingAlbum(a: AlbumData) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 1100.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 48.dp else 90.dp, vertical = 64.dp), verticalArrangement = Arrangement.Center) {
            Text(listOfNotNull(a.date, a.destination).joinToString(" · "), fontSize = 30.sp, lineHeight = 44.sp, color = AlbumColors.Amber,
                style = TextStyle(lineBreak = KoParagraph))
            Text(a.title.replace(", ", ",\n"), Modifier.padding(top = 20.dp, bottom = 40.dp),
                style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = if (narrow) 72.sp else 96.sp,
                    lineHeight = if (narrow) 88.sp else 116.sp, color = AlbumColors.Text, lineBreak = KoHeading))
            Text("차를 세우면 앨범을 펼쳐 드려요.", fontSize = 34.sp, lineHeight = 48.sp, color = AlbumColors.Sub)
        }
    }
}

/* ───────────────────────── 공개 (장면 6) ───────────────────────── */

@Composable private fun RevealPage(a: AlbumData, onNext: () -> Unit) {
    val pins = a.chapters.mapNotNull { c -> if (c.lat != null && c.lon != null) MapPin(c.name, c.lat, c.lon) else null }
    // 경로가 다 그려진 뒤 제목을 치고 숫자를 올린다
    val afterRoute = ROUTE_DELAY_MS + ROUTE_DRAW_MS
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 1100.dp
        val mapH = (maxHeight * 0.42f).coerceAtLeast(320.dp)
        // 좁은 창(분할 화면): 지도 아래에 글을 두고 세로로 스크롤한다 (높이가 모자라도 [앨범 펼치기]까지 닿게)
        if (narrow) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RouteMap(a.tripId, a.route, pins, Modifier.fillMaxWidth().height(mapH), a.returnRoute)
            RevealSide(a, afterRoute, onNext, Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), fill = false)
        } else Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().weight(0.55f)) {
                RouteMap(a.tripId, a.route, pins, Modifier.fillMaxSize(), a.returnRoute)
                // 지도 오른쪽 끝을 앨범 바탕으로 풀어 경계선이 보이지 않게
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(120.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, AlbumColors.Bg))))
            }
            RevealSide(a, afterRoute, onNext, Modifier.fillMaxHeight().weight(0.45f).padding(start = 40.dp, end = 56.dp, top = 72.dp, bottom = 40.dp))
        }
    }
}

/** fill: 높이를 채우는 넓은 배치면 버튼을 아래로 민다. 스크롤하는 좁은 배치에서는 고정 간격. */
@Composable private fun RevealSide(a: AlbumData, startMs: Int, onNext: () -> Unit, modifier: Modifier, fill: Boolean = true) {
    Column(modifier) {
        Text(a.date, fontSize = 24.sp, letterSpacing = 0.12.em, color = AlbumColors.Amber)
        TypedTitle(a, startMs, Modifier.padding(top = 16.dp, bottom = 10.dp))
        Text(a.subtitle, fontSize = 30.sp, lineHeight = 44.sp, color = AlbumColors.Sub, style = TextStyle(lineBreak = KoParagraph))
        Spacer(Modifier.height(28.dp))
        val km by rememberMotionProgress("km-${a.tripId}", tween(1400, easing = EaseOutCubic), delayMs = startMs)
        val cnt by rememberMotionProgress("cnt-${a.tripId}", tween(1400, easing = EaseOutCubic), delayMs = startMs)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCard((a.km * km).roundToInt().toString(), "km를 함께 달렸어요", Modifier.weight(1f))
            StatCard((a.momentCount * cnt).roundToInt().toString(), "개의 순간", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCard(a.duration, "출발부터 도착까지", Modifier.weight(1f))
            StatCard(null, a.seats?.let { "${koCount(it)} 명이 함께" } ?: "탑승 인원 기록 없음", Modifier.weight(1f)) { SeatDots(a.seats) }
        }
        // 공개 화면은 도착 장과 같은 칩(arrival의 날씨·일몰·축제·휴일). 실제 값이 하나도 없을 때만 2a 예시
        val outside = outsideFor(a, ChapterKind.ARRIVAL, reveal = true)
        if (outside.isNotEmpty()) {
            OutsideCaption(Modifier.padding(top = 26.dp, bottom = 12.dp), example = outside.first().dashed)
            ChipFlow(outside)
        }
        if (a.photoCount > 0) PhotoSorting(a, startMs, Modifier.padding(top = 22.dp))
        Spacer(if (fill) Modifier.weight(1f).heightIn(min = 24.dp) else Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton("앨범 펼치기", onNext) }
    }
}

/** 85ms마다 한 글자. 전체 제목을 투명하게 깔아 자리를 먼저 잡아서 줄바꿈이 흔들리지 않는다. */
@Composable private fun TypedTitle(a: AlbumData, delayMs: Int, modifier: Modifier) {
    val text = a.title.replace(", ", ",\n")
    val p by rememberMotionProgress("title-${a.tripId}", tween(85 * text.length, easing = LinearEasing), delayMs = delayMs)
    val shown = (text.length * p).toInt().coerceIn(0, text.length)
    val style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 70.sp, lineHeight = 84.sp, color = AlbumColors.Text, lineBreak = KoHeading)
    Box(modifier) {
        Text(text, style = style.copy(color = Color.Transparent))
        Text(buildAnnotatedString {
            append(text, 0, shown)
            // 커서: 타이핑 중에만 보이는 앰버 막대(깜빡임 없음)
            if (shown < text.length) withStyle(SpanStyle(color = AlbumColors.Amber)) { append('|') }
        }, style = style)
    }
}

@Composable private fun StatCard(value: String?, label: String, modifier: Modifier, top: (@Composable () -> Unit)? = null) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(AlbumColors.Surface).border(1.dp, AlbumColors.Line, RoundedCornerShape(22.dp)).padding(horizontal = 26.dp, vertical = 20.dp)) {
        if (value != null) Text(value, fontSize = 48.sp, lineHeight = 60.sp, fontWeight = FontWeight.SemiBold, color = AlbumColors.Text)
        top?.invoke()
        Text(label, fontSize = 24.sp, lineHeight = 34.sp, color = AlbumColors.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val SeatColors = listOf(Color(0xFFFFC98A), Color(0xFFF7A9A0), Color(0xFFB9B2FF), Color(0xFF9FD8C4), Color(0xFFC9CFE6))

@Composable private fun SeatDots(n: Int?) {
    Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        if (n == null) Text("—", fontSize = 48.sp, color = AlbumColors.Text)
        else repeat(n.coerceAtMost(5)) { i ->
            Box(Modifier.offset(x = (-12 * i).dp).size(52.dp).border(3.dp, AlbumColors.Bg, CircleShape).padding(3.dp).clip(CircleShape).background(SeatColors[i]), contentAlignment = Alignment.Center) {
                Icon(LiveIcons.User, null, Modifier.size(28.dp), tint = BtnInk)
            }
        }
    }
}

/* ───────────────────────── 표지 (장면 8) ───────────────────────── */

@Composable private fun CoverPage(a: AlbumData, actions: AlbumActions, onOpen: () -> Unit) {
    val zoom by rememberMotionProgress("cover-${a.tripId}", tween(6000, easing = EaseOutCubic))
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        val zoomed = Modifier.fillMaxSize().graphicsLayer { val s = 1.08f - 0.08f * zoom; scaleX = s; scaleY = s }
        val illustration = Modifier.fillMaxSize().scene(a.arriveHour, a.rained, seed = 1, sea = seaLike(a.destination))
        // 표지 사진(도착 장의 가장 큰 가로 사진)이 있으면 꽉 차게, 없으면 2a 그림
        if (a.cover != null) Box(zoomed) { PhotoImage(a.cover.fullFile ?: a.cover.thumbFile, Modifier.fillMaxSize(), maxPx = 2048) { Box(illustration) } }
        else Box(zoomed.scene(a.arriveHour, a.rained, seed = 1, sea = seaLike(a.destination)))
        // 아래쪽 남색 덧칠: 사진 위에서도 글자가 읽히게 (사진이면 조금 더 진하게)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to InkDeep.copy(alpha = if (a.cover != null) 0.15f else 0.1f), 1f to InkDeep.copy(alpha = if (a.cover != null) 0.95f else 0.92f))))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrow = maxWidth < 1100.dp
            val side = if (narrow) 48.dp else 90.dp
            Column(Modifier.align(Alignment.BottomStart).padding(start = side, end = side, bottom = side)) {
                Rise(a, 0, 200) { Text("여로 앨범 · ${a.date}", fontSize = 24.sp, letterSpacing = 0.12.em, color = AlbumColors.Amber) }
                Rise(a, 1, 450) {
                    Text(a.title.replace(", ", ",\n"), Modifier.padding(top = 18.dp, bottom = 26.dp),
                        style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = if (narrow) 84.sp else 116.sp,
                            lineHeight = if (narrow) 96.sp else 128.sp, color = Color.White, lineBreak = KoHeading))
                }
                Rise(a, 2, 700) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                        if (a.seats != null) Box(Modifier.padding(start = 12.dp)) { SeatDots(a.seats) }
                        val photos = if (a.photoCount > 0) " · 사진 ${a.photoCount}장" else ""
                        Text("${a.chapters.size}개의 장$photos · 순간 ${a.momentCount}개 · ${a.km}km · ${a.duration}", fontSize = 28.sp, color = Color(0xFFD6D9E6),
                            style = TextStyle(lineBreak = KoParagraph))
                    }
                }
                Rise(a, 3, 950) { Box(Modifier.padding(top = 40.dp)) { PillButton("펼쳐 보기", onOpen) } }
            }
        }
        // 더보기: 닫힌 여행만 통째로 지울 수 있다 (앨범은 정차 중에만 펼쳐진다. 주행 중에는 DrivingAlbum)
        if (a.closed) Column(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 48.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(MinTouch).clip(CircleShape).background(InkDeep.copy(alpha = 0.55f)).border(1.dp, AlbumColors.Line, CircleShape)
                .clickable(role = Role.Button, onClickLabel = "더보기") { menu = !menu }, contentAlignment = Alignment.Center) {
                Text("⋯", fontSize = 36.sp, color = Color.White)
            }
            if (menu) Box(Modifier.heightIn(min = MinTouch).clip(RoundedCornerShape(22.dp)).background(DialogBg).border(1.dp, AlbumColors.Line, RoundedCornerShape(22.dp))
                .clickable(role = Role.Button) { menu = false; confirmDelete = true }.padding(horizontal = 36.dp, vertical = 20.dp), contentAlignment = Alignment.CenterStart) {
                Text("이 여행 지우기", fontSize = 30.sp, color = WarnInk)
            }
        }
        if (confirmDelete) ConfirmOverlay(
            title = "이 여행을 지울까요?",
            body = "사진과 기록이 모두 지워져요. 되돌릴 수 없어요.",
            confirm = "지우기", onConfirm = { confirmDelete = false; actions.deleteTrip() }, onCancel = { confirmDelete = false },
        )
    }
}

/** 표지 글자 등장: 아래에서 떠오르며 나타난다(시안 .reveal) */
@Composable private fun Rise(a: AlbumData, i: Int, delayMs: Int, content: @Composable () -> Unit) {
    val p by rememberMotionProgress("cover-rise-${a.tripId}-$i", tween(800, easing = EaseOutCubic), delayMs = delayMs)
    val dy = with(LocalDensity.current) { 30.dp.toPx() }
    Box(Modifier.graphicsLayer { alpha = p; translationY = (1f - p) * dy }) { content() }
}

/* ───────────────────────── 장 넘기기 (장면 9·10) ───────────────────────── */

/**
 * 앨범의 쪽: 장 번호 순서, 그때 vs 지금이 있으면 도착 장 바로 다음에 THEN_NOW 한 쪽 (도착 장이 없으면 넣지 않는다).
 * 다시 가기 링크가 있으면(목적지 이름이 있으면) 맨 끝(RETURN 장 뒤)에 AGAIN 한 쪽.
 */
private const val THEN_NOW = -1
private const val AGAIN = -2
private fun albumPages(a: AlbumData): List<Int> {
    val pages = a.chapters.indices.toMutableList()
    val arrival = a.chapters.indexOfLast { it.kind == ChapterKind.ARRIVAL }
    if (a.thenNow != null && arrival >= 0) pages.add(arrival + 1, THEN_NOW)
    if (a.again.isNotEmpty() && a.destination != null) pages.add(AGAIN)
    return pages
}

/** 다시 가기 쪽 (시안 장면 11): 링크마다 QR. 차 안 예약은 없다. QR 카드 등장은 이 쪽에 자리 잡았을 때만(모션 허용 시) */
@Composable private fun AgainPage(a: AlbumData, visible: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1500.dp
        val side = if (maxWidth < 1100.dp) 40.dp else 90.dp
        AgainPanel(a.destination?.trim()?.takeIf { it.isNotEmpty() } ?: return@BoxWithConstraints, a.again, a.visit, wide, motionKey = "album-${a.tripId}", visible = visible,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = side, end = side, top = 56.dp, bottom = 16.dp))
    }
}

@Composable private fun ChapterBook(a: AlbumData, initialPage: Int, actions: AlbumActions, onPage: (Int) -> Unit) {
    val motion = LocalMotion.current
    val pages = albumPages(a)
    val pager = rememberPagerState(initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }
    LaunchedEffect(pager.currentPage) { onPage(pager.currentPage) }
    val scope = rememberCoroutineScope()
    val go: (Int) -> Unit = { i -> scope.launch { if (motion) pager.animateScrollToPage(i) else pager.scrollToPage(i) } }
    // 길게 누른 사진: [이 사진 빼기] 확인을 장 트랙까지 덮어 띄운다 (정차 중에만. 주행 제한이 걸리면 앨범이 DrivingAlbum으로 바뀌어 확인도 사라진다)
    var removing by remember { mutableStateOf<AlbumPhoto?>(null) }
    Box(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
        // 주행 제한 중(모션 꺼짐)에는 넘기는 애니메이션이 생기지 않게 스와이프를 막고 아래 장 트랙으로만 넘긴다
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = motion, beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                if (!motion) return@graphicsLayer
                val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                rotationY = off * 12f
                alpha = 1f - abs(off) * 0.6f
                cameraDistance = 24f * density
            }) {
                val ci = pages.getOrNull(page) ?: return@Box
                val tn = a.thenNow
                // 옆 쪽은 미리 그려 두므로(beyondViewportPageCount) 등장 연출은 이 쪽에 자리 잡았을 때 시작한다
                val visible = pager.settledPage == page
                when {
                    ci == THEN_NOW && tn != null -> ThenNowPage(a, tn, visible)
                    ci == AGAIN -> AgainPage(a, visible)
                    ci >= 0 -> ChapterPage(a, ci, visible) { removing = it }
                }
            }
        }
        ChapterTrack(pages.map { when (it) { THEN_NOW -> "그때 vs 지금"; AGAIN -> "다시 가기"; else -> a.chapters[it].name } }, pager.currentPage, go)
    }
        removing?.let { p ->
            ConfirmOverlay(
                title = "이 사진을 뺄까요?",
                body = "차 앨범과 서버에서 모두 지워져요. 되돌릴 수 없어요.",
                confirm = "이 사진 빼기", photo = p,
                onConfirm = { removing = null; actions.removePhoto(p.id) }, onCancel = { removing = null },
            )
        }
    }
}

@Composable private fun ChapterPage(a: AlbumData, i: Int, visible: Boolean, onLongPress: (AlbumPhoto) -> Unit) {
    val c = a.chapters[i]
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 1100.dp
        if (narrow) Column(Modifier.fillMaxSize()) {
            Collage(a, i, visible, Modifier.fillMaxWidth().weight(0.42f), onLongPress)
            ChapterText(a, c, i, Modifier.fillMaxWidth().weight(0.58f).padding(horizontal = 40.dp, vertical = 16.dp))
        } else Row(Modifier.fillMaxSize()) {
            Collage(a, i, visible, Modifier.fillMaxHeight().weight(0.52f), onLongPress)
            ChapterText(a, c, i, Modifier.fillMaxHeight().weight(0.48f).padding(start = 20.dp, end = 56.dp, top = 72.dp, bottom = 16.dp))
        }
    }
}

@Composable private fun ChapterText(a: AlbumData, c: Chapter, i: Int, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text("${a.chapters.size}장 중 ${i + 1}장", fontSize = 24.sp, letterSpacing = 0.12.em, color = AlbumColors.Amber)
        Text(c.name, Modifier.padding(top = 12.dp, bottom = 6.dp), fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 54.sp, lineHeight = 68.sp, color = AlbumColors.Text)
        Text(whenLine(c, a), fontSize = 30.sp, color = AlbumColors.Sub)
        // "10시 58분", "네 개"가 줄 끝에서 갈라지지 않도록 시와 분 사이는 줄바꿈 없는 공백
        Text(chapterDiary(c, a.destination, a.zone, a.names, a.outside?.arrival).replace(TimeSpace, "$1\u00A0$2").replace(CountSpace, "$1\u00A0$2"), Modifier.padding(top = 26.dp, bottom = 24.dp),
            style = TextStyle(fontFamily = Pretendard, fontSize = 33.sp, lineHeight = 54.sp, color = Diary, lineBreak = KoParagraph))
        Text("차가 기억한 순간", fontSize = 24.sp, color = AlbumColors.Sub, modifier = Modifier.padding(bottom = 12.dp))
        val car = c.moments.mapNotNull { momentChip(it, a.zone) }.map { ChipData(it.text, it.source, LiveIcons.of(it.icon), dashed = false) } +
            (if (c.kind == ChapterKind.ARRIVAL) listOf(ChipData("${clock(c.startMs, a.zone)} 도착", "위치", LiveIcons.Flag, dashed = false)) else emptyList()) +
            (if (c.kind == ChapterKind.DEPARTURE || c.kind == ChapterKind.RETURN) listOf(ChipData("${clock(c.startMs, a.zone)} 출발", "위치", LiveIcons.Flag, dashed = false)) else emptyList()) +
            (if (c.kind == ChapterKind.RETURN) listOf(ChipData("${clock(c.endMs, a.zone)} 집 도착", "위치", LiveIcons.Flag, dashed = false)) else emptyList())
        ChipFlow(car)
        val outside = outsideFor(a, c.kind)
        if (outside.isNotEmpty()) {
            OutsideCaption(Modifier.padding(top = 24.dp, bottom = 12.dp), example = outside.first().dashed)
            ChipFlow(outside)
        }
    }
}

private fun whenLine(c: Chapter, a: AlbumData): String {
    val t = clock(c.startMs, a.zone)
    val stay = c.stayMin?.let { " · ${stayText(it)} 머묾" } ?: ""
    return when (c.kind) {
        ChapterKind.DEPARTURE -> "$t 출발"
        ChapterKind.STOP -> "$t 정차$stay"
        ChapterKind.ARRIVAL -> "$t 도착$stay"
        ChapterKind.RETURN -> "$t 출발 · ${clock(c.endMs, a.zone)} 집 도착"
    }
}

/**
 * 그날의 바깥 칩. 받아 둔 공공데이터(trip_outside)에 실제 값이 하나라도 있으면 실제 칩만(실선), 하나도 없으면 2a의 "예시" 칩(점선)만.
 * 둘을 섞지 않는다. reveal: 공개 화면(도착 장과 같은 칩, 예시는 축제 자리까지 세 개).
 */
private fun outsideFor(a: AlbumData, k: ChapterKind, reveal: Boolean = false): List<ChipData> {
    val o = a.outside
    if (o != null && !showExampleOutside(o)) return outsideChips(o, k).map { ChipData(it.text, it.source, outsideIcon(it), dashed = false) }
    return when (k) {
        ChapterKind.DEPARTURE -> listOf(ChipData("맑음 11℃", "기상청", LiveIcons.Sun, dashed = true))
        ChapterKind.STOP -> listOf(ChipData("수유실·키즈존", "도로공사", LiveIcons.Flag, dashed = true))
        ChapterKind.ARRIVAL -> listOfNotNull(ChipData("18℃ 갬", "기상청", LiveIcons.Sun, dashed = true), ChipData("일몰 17:42", "천문연", LiveIcons.Clock, dashed = true),
            if (reveal) ChipData("지역 축제", "TourAPI", LiveIcons.Flag, dashed = true) else null)
        ChapterKind.RETURN -> emptyList()
    }
}

private val PrecipWords = listOf("비", "눈", "소나기", "빗방울", "눈날림", "비/눈", "빗방울눈날림")
private fun outsideIcon(c: OutsideChipData): ImageVector = when (c.kind) {
    OutsideChipKind.WEATHER -> if (PrecipWords.any { c.text.endsWith(" $it") || c.text == it }) LiveIcons.Rain else LiveIcons.Sun
    OutsideChipKind.SUN -> LiveIcons.Clock
    OutsideChipKind.FESTIVAL -> LiveIcons.Ticket
    OutsideChipKind.HOLIDAY -> LiveIcons.Flag
}

/* 콜라주: 사진 자리 2~5칸. 기울기와 깊이가 다르고, 터치 드래그 위치에 따라 깊이만큼 어긋난다(모션 허용 시). */
private class Tile(val x: Float, val y: Float, val w: Float, val h: Float, val rot: Float, val depth: Float)
private val Tiles = listOf(
    Tile(90f, 120f, 540f, 400f, -4f, 0.6f), Tile(460f, 320f, 390f, 500f, 5f, 1f), Tile(120f, 560f, 370f, 290f, 3f, 1.4f),
    Tile(500f, 70f, 300f, 220f, -6f, 1.8f), Tile(40f, 330f, 250f, 200f, 7f, 2.2f),
)
private fun tileCount(k: ChapterKind) = when (k) { ChapterKind.DEPARTURE -> 2; ChapterKind.STOP -> 3; ChapterKind.ARRIVAL -> 5; ChapterKind.RETURN -> 2 }

/** onLongPress: 실제 사진 칸을 길게 눌렀을 때 (사진 빼기 확인). 사진이 없는 장은 2a 일러스트 칸 */
@Composable private fun Collage(a: AlbumData, i: Int, visible: Boolean, modifier: Modifier, onLongPress: (AlbumPhoto) -> Unit) {
    val c = a.chapters[i]
    val photos = a.photos[i].orEmpty()
    val motion = LocalMotion.current
    var touch by remember { mutableStateOf(Offset.Zero) }   // -0.5..0.5
    val tx by animateFloatAsState(touch.x, motionSpec(spring(stiffness = Spring.StiffnessLow)), label = "px")
    val ty by animateFloatAsState(touch.y, motionSpec(spring(stiffness = Spring.StiffnessLow)), label = "py")
    BoxWithConstraints(modifier.pointerInput(motion) {
        if (!motion) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            touch = Offset(down.position.x / size.width - 0.5f, down.position.y / size.height - 0.5f)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull() ?: break
                if (!ch.pressed) break
                touch = Offset((ch.position.x / size.width - 0.5f).coerceIn(-0.5f, 0.5f), (ch.position.y / size.height - 0.5f).coerceIn(-0.5f, 0.5f))
            }
            touch = Offset.Zero
        }
    }) {
        // 시안 900×960 좌표를 칸 크기에 맞춰 줄이고 가운데에 둔다
        val s = minOf(maxWidth.value / 900f, maxHeight.value / 960f)
        val ox = (maxWidth.value - 900f * s) / 2; val oy = (maxHeight.value - 960f * s) / 2 + 20f * s
        // 사진이 k장이면 min(k, 5)칸에 실제 사진, 0장이면 2a 일러스트 자리
        val n = if (photos.isNotEmpty()) minOf(photos.size, Tiles.size) else tileCount(c.kind)
        // 큰 칸이 아래에 깔리도록 깊이 순서대로
        Tiles.take(n).forEachIndexed { j, t ->
            val photo = photos.getOrNull(j)
            val illustration = Modifier.fillMaxSize().scene(a.arriveHour, a.rained, seed = i * 7 + j + 2, sea = c.kind == ChapterKind.ARRIVAL && seaLike(a.destination), hourOverride = hourOf(c, a))
            val enter by rememberMotionProgress("tile-${a.tripId}-$i-$j", spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow), delayMs = 120 + j * 90, active = visible)
            Box(Modifier.offset((ox + t.x * s).dp, (oy + t.y * s).dp).size((t.w * s).dp, (t.h * s).dp)
                .graphicsLayer {
                    val e = enter
                    rotationZ = t.rot * (3f - 2f * e)
                    translationX = -tx * t.depth * 40f * density
                    translationY = -ty * t.depth * 30f * density + (1f - e) * 60f * density
                    rotationY = tx * 6f; rotationX = -ty * 6f
                    val sc = 0.9f + 0.1f * e; scaleX = sc; scaleY = sc
                    alpha = e.coerceIn(0f, 1f)
                    cameraDistance = 24f * density
                }
                .shadow(30.dp, RoundedCornerShape(14.dp))
                .background(Paper, RoundedCornerShape(14.dp)).padding(8.dp).clip(RoundedCornerShape(8.dp))
                .then(if (photo != null) Modifier.pointerInput(photo.id) { detectTapGestures(onLongPress = { onLongPress(photo) }) } else illustration)
            ) {
                if (photo != null) PhotoImage(photo.thumbFile, Modifier.fillMaxSize(), maxPx = 720) { Box(illustration) }
                // 다른 칸에 가리지 않는 오른쪽 세로 칸의 위쪽에 한 번만
                else if (j == 1) Text("사진이 올 자리", Modifier.align(Alignment.TopEnd).padding(14.dp)
                    .background(InkDeep.copy(alpha = 0.55f), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
                    fontSize = 24.sp, color = Color.White)
            }
        }
        if (photos.isNotEmpty()) Text(if (photos.size > Tiles.size) "사진 ${photos.size}장 중 ${Tiles.size}장 · 길게 누르면 뺄 수 있어요" else "사진을 길게 누르면 뺄 수 있어요",
            Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 8.dp), fontSize = 24.sp, color = AlbumColors.Sub)
    }
}

private fun hourOf(c: Chapter, a: AlbumData) = java.time.Instant.ofEpochMilli(c.startMs).atZone(a.zone).hour

/* 장 트랙: 점과 이름, 지난 장은 앰버로 채운다 */
@Composable private fun ChapterTrack(labels: List<String>, current: Int, go: (Int) -> Unit) {
    val n = labels.size
    val fill by animateFloatAsState(if (n > 1) current / (n - 1f) else 1f, motionSpec(tween(600)), label = "track")
    Row(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Transparent, InkDeep.copy(alpha = 0.95f)))).padding(horizontal = 56.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        ArrowButton("‹", "이전 장", current > 0) { go(current - 1) }
        BoxWithConstraints(Modifier.weight(1f).height(110.dp).padding(horizontal = 100.dp)) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().padding(top = 29.dp).height(2.dp).background(AlbumColors.Line))
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).padding(top = 29.dp).height(2.dp).background(AlbumColors.Amber))
            labels.forEachIndexed { i, name ->
                val x = if (n > 1) w * (i / (n - 1f)) else w / 2
                val itemW = 220.dp
                Column(Modifier.offset(x = x - itemW / 2).width(itemW).heightIn(min = MinTouch)
                    .clickable(role = Role.Tab, onClickLabel = name) { go(i) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 19.dp).size(22.dp).border(3.dp, AlbumColors.Bg, CircleShape).padding(3.dp)
                        .background(if (i <= current) AlbumColors.Amber else Color(0xFF2A3048), CircleShape))
                    Text(name, Modifier.padding(top = 8.dp), fontSize = 24.sp, color = if (i == current) AlbumColors.Text else AlbumColors.Sub,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        ArrowButton("›", "다음 장", current < n - 1) { go(current + 1) }
    }
}

@Composable private fun ArrowButton(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(80.dp).clip(CircleShape).background(AlbumColors.Surface).border(1.dp, AlbumColors.Line, CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 40.sp, color = if (enabled) AlbumColors.Text else AlbumColors.Sub.copy(alpha = 0.4f))
    }
}

/* ───────────────────────── 사진 정리 (장면 7) ───────────────────────── */

/**
 * 공개 화면의 "사진 정리" 줄: 장 이름마다 "N장" 칸과 썸네일 더미. 모션이 허용되면 경로가 그려진 뒤 사진이 110ms 간격으로 칸에 내려앉고
 * 칸의 장수가 따라 오른다. 모션이 꺼져 있으면 처음부터 다 쌓여 있다. 더미에는 장마다 앞 네 장만 보인다.
 */
@Composable private fun PhotoSorting(a: AlbumData, startMs: Int, modifier: Modifier) {
    Column(modifier) {
        Text("사진 정리 · ${a.photoCount}장", fontSize = 24.sp, color = AlbumColors.Sub, modifier = Modifier.padding(bottom = 12.dp))
        var order = 0
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            a.chapters.forEachIndexed { ci, c ->
                val photos = a.photos[ci].orEmpty()
                val first = order; order += photos.size
                val landed = photos.mapIndexed { k, p ->
                    rememberMotionProgress("sort-${a.tripId}-${p.id}", tween(420, easing = EaseOutCubic), delayMs = startMs + (first + k) * 110)
                }
                val count = landed.count { it.value >= 0.6f }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(AlbumColors.Surface).border(1.dp, AlbumColors.Line, RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(c.name, fontSize = 24.sp, color = AlbumColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val dy = with(LocalDensity.current) { 36.dp.toPx() }
                    Row(Modifier.padding(top = 8.dp).height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            photos.take(4).forEachIndexed { k, p ->
                                val e = landed[k].value
                                PhotoImage(p.thumbFile, Modifier.align(Alignment.CenterStart).offset(x = (k * 22).dp).size(58.dp)
                                    .graphicsLayer { alpha = e.coerceIn(0f, 1f); translationY = (1f - e) * -dy; rotationZ = if (k % 2 == 0) -4f else 4f
                                        val sc = 1.25f - 0.25f * e; scaleX = sc; scaleY = sc }
                                    .border(3.dp, Paper, RoundedCornerShape(10.dp)).padding(3.dp).clip(RoundedCornerShape(7.dp)), maxPx = 200) {
                                    Box(Modifier.fillMaxSize().background(AlbumColors.Line))
                                }
                            }
                        }
                        Text("${count}장", Modifier.padding(start = 6.dp), fontSize = 24.sp, color = if (count > 0) AlbumColors.Amber else AlbumColors.Sub, maxLines = 1)
                    }
                }
            }
        }
    }
}

/* ───────────────────────── 공통 조각 ───────────────────────── */

/**
 * 되돌릴 수 없는 일의 마지막 확인. 화면을 어둡게 덮고 가운데 카드: 제목·설명(·사진), [취소](먼저)·[confirm](붉은 글자). 터치 영역 76dp 이상.
 * 덮개를 눌러도 취소.
 */
@Composable private fun ConfirmOverlay(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, photo: AlbumPhoto? = null) {
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(InkDeep.copy(alpha = 0.78f)).pointerInput(Unit) { detectTapGestures { onCancel() } }, contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(min = 760.dp, max = 1100.dp).padding(40.dp).clip(RoundedCornerShape(28.dp)).background(DialogBg)
            .border(1.dp, AlbumColors.Line, RoundedCornerShape(28.dp)).pointerInput(Unit) { detectTapGestures { } }.padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo != null) PhotoImage(photo.thumbFile, Modifier.size(200.dp).clip(RoundedCornerShape(14.dp)), maxPx = 400) { Box(Modifier.size(200.dp).background(AlbumColors.Line)) }
            Column(Modifier.weight(1f, fill = false)) {
                Text(title, fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 58.sp, color = AlbumColors.Text,
                    style = TextStyle(lineBreak = KoHeading))
                Text(body, Modifier.padding(top = 14.dp, bottom = 32.dp), fontSize = 30.sp, lineHeight = 44.sp, color = AlbumColors.Sub, style = TextStyle(lineBreak = KoParagraph))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.heightIn(min = MinTouch).widthIn(min = 180.dp).clip(RoundedCornerShape(999.dp)).border(1.5.dp, AlbumColors.Line, RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 40.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text("취소", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AlbumColors.Text)
                    }
                    Box(Modifier.heightIn(min = MinTouch).widthIn(min = 200.dp).clip(RoundedCornerShape(999.dp)).background(WarnInk.copy(alpha = 0.16f))
                        .border(1.5.dp, WarnInk.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = onConfirm).padding(horizontal = 40.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text(confirm, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = WarnInk)
                    }
                }
            }
        }
    }
}

internal data class ChipData(val text: String, val source: String, val icon: ImageVector, val dashed: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ChipFlow(chips: List<ChipData>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { chips.forEach { Chip(it) } }
}

@Composable private fun Chip(c: ChipData) {
    val shape = RoundedCornerShape(999.dp)
    val border = if (c.dashed) Modifier.dashedBorder(AlbumColors.Line.copy(alpha = 0.5f), 1.5.dp) else Modifier.border(1.dp, AlbumColors.Line, shape)
    Row(Modifier.clip(shape).background(AlbumColors.Surface).then(border).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(c.icon, null, Modifier.size(28.dp), tint = ChipIcon)
        // 긴 문구는 두 줄까지 감싸고 그래도 넘치면 말줄임. 출처는 늘 보이게 문구 쪽만 줄어든다
        Text(c.text, Modifier.weight(1f, fill = false), fontSize = 24.sp, color = AlbumColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(lineBreak = KoParagraph))
        Text(c.source, fontSize = 24.sp, color = AlbumColors.Sub, maxLines = 1, softWrap = false)
    }
}

private fun Modifier.dashedBorder(color: Color, width: Dp) = drawBehind {
    val px = width.toPx()
    drawRoundRect(color, topLeft = Offset(px / 2, px / 2), size = Size(size.width - px, size.height - px), cornerRadius = CornerRadius(size.height / 2),
        style = Stroke(px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density))))
}

/** example: 2a 예시 칩일 때만 "예시" 꼬리표 */
@Composable private fun OutsideCaption(modifier: Modifier, example: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("그날의 바깥", fontSize = 24.sp, color = AlbumColors.Sub)
        if (example) Text("예시", Modifier.border(1.dp, TagLine, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 24.sp, color = TagInk)
    }
}

@Composable private fun PillButton(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = MinTouch).clip(RoundedCornerShape(999.dp))
        .background(Brush.linearGradient(listOf(Color(0xFFFFC98A), Color(0xFFFF9F6E))))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 44.dp, vertical = 22.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = BtnInk)
    }
}

/** 앨범 바탕: 남색 위 오른쪽 위 앰버, 왼쪽 아래 보라 빛 (시안 .yeoro) */
private fun Modifier.albumBackdrop() = drawWithCache {
    val amber = Brush.radialGradient(listOf(Color(0x24FFB46A), Color.Transparent), center = Offset(size.width * 0.85f, -size.height * 0.1f), radius = size.width * 0.6f)
    val violet = Brush.radialGradient(listOf(Color(0x296F5BD8), Color.Transparent), center = Offset(-size.width * 0.1f, size.height * 1.1f), radius = size.width * 0.45f)
    onDrawBehind { drawRect(AlbumColors.Bg); drawRect(amber); drawRect(violet) }
}
