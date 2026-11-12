package ai.yeoro.ui.live

import ai.yeoro.settings.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivity
import ai.yeoro.EngineBus
import ai.yeoro.data.MomentEntity
import ai.yeoro.data.TripEntity
import ai.yeoro.engine.Gear
import ai.yeoro.engine.TripState
import ai.yeoro.ui.theme.Pretendard
import ai.yeoro.ui.theme.Dusk
import ai.yeoro.ui.theme.duskInk
import ai.yeoro.ux.LocalDrivingRestricted
import ai.yeoro.ux.LocalMotion
import ai.yeoro.ux.MotionLog
import ai.yeoro.ui.album.scene
import ai.yeoro.ui.common.PhotoImage
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

private val RecRed = Color(0xFFFF5A5A)

/**
 * 지금 화면. 장면 고르기는 liveMode(동의 꺼짐이 가장 먼저, 그다음 질문 > 도착 > 기록 중 > 기록 시작 > 앨범 준비 > 일상).
 * 기록 중 화면은 항상 정적이고, 다른 모드의 움직임은 LocalMotion이 허용할 때만 돈다.
 * onOpenAlbum: 앨범 준비 장면의 [앨범 보기] (방금 닫힌 여행 id).
 */
@Composable fun LiveScreen(onOpenAlbum: (Long) -> Unit = {}, vm: LiveViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ask by vm.ask.collectAsStateWithLifecycle()
    val started by vm.started.collectAsStateWithLifecycle()
    val trip by vm.openTrip.collectAsStateWithLifecycle()
    val moments by vm.moments.collectAsStateWithLifecycle()
    val arrival by vm.arrival.collectAsStateWithLifecycle()
    val settings = Settings.get(LocalContext.current)
    val names by settings.familyNames.collectAsStateWithLifecycle()
    val consent by settings.consent.collectAsStateWithLifecycle()
    val albumTripId by vm.albumTripId.collectAsStateWithLifecycle()
    val photos by vm.promoPhotos.collectAsStateWithLifecycle()
    val memory by vm.promoMemory.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    // 도착 카드는 P단에서만. P를 벗어나면 다음 틱을 기다리지 않고 바로(애니메이션 없이) 숨긴다
    val gear by EngineBus.gear.collectAsStateWithLifecycle()
    val voice by EngineBus.voice.collectAsStateWithLifecycle()
    val leftP = gear != Gear.P && gear != Gear.UNKNOWN
    // 근처 혜택·예약(컨셉)은 P단에서만. P를 벗어나면 닫는다
    val nearby by Nearby.ui.collectAsStateWithLifecycle()
    LaunchedEffect(leftP) { if (leftP) Nearby.close() }
    val mode = liveMode(consent, state, hasAsk = ask != null, hasArrival = arrival != null, leftP = leftP, started = started != null)
    // 사진 도착 카드(기록 중에는 P단에서만) > 1년 전 이번 주 회상 카드(일상 P단에서만). 정적
    val promo = livePromo(mode, gear, photos, memory)
    // 회상 카드가 실제로 보이면 그날을 적어 둔다(하루 한 번)
    // 보이지 않게 되면(P 이탈·홍보 전환·[닫기]·화면 떠남) 정리해서 그날은 다시 나오지 않게 한다
    // 화면 회전 같은 구성 변경으로 잠깐 사라지는 것은 "보이지 않게 됨"이 아니다 (VM이 살아 있어 같은 카드를 다시 보인다)
    val memoryCard = promo as? Promo.Memory
    val activity = LocalActivity.current
    DisposableEffect(memoryCard?.tripId) {
        memoryCard?.let(vm::memoryShown)
        onDispose { if (memoryCard != null && activity?.isChangingConfigurations != true) vm.memoryGone() }
    }
    val motion = LocalMotion.current
    // 지금 화면이 실제로 보이는 동안만 true (STARTED 이상 + 컴포지션 안). 질문 알림 여부의 기준
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) EngineBus.liveVisible.value = true
            else if (e == Lifecycle.Event.ON_STOP) EngineBus.liveVisible.value = false
        }
        lifecycle.addObserver(obs)
        EngineBus.liveVisible.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycle.removeObserver(obs); EngineBus.liveVisible.value = false }
    }
    // 나가는 프레임이 비지 않게 마지막 질문·시작 정보를 기억한다
    var lastAsk by remember { mutableStateOf(ask) }
    if (ask != null) lastAsk = ask
    var lastStarted by remember { mutableStateOf(started) }
    var lastArrival by remember { mutableStateOf(arrival) }
    if (arrival != null) lastArrival = arrival
    if (started != null) lastStarted = started
    var shown by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) { if (mode != shown && motion) MotionLog.started("crossfade $shown->$mode"); shown = mode }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1100.dp
            val side = if (maxWidth < 700.dp) 32.dp else 56.dp
            Column(Modifier.fillMaxSize()) {
                LiveHeader(mode == LiveMode.RECORDING, Modifier.padding(horizontal = side).padding(top = 32.dp, bottom = 8.dp))
                Crossfade(mode, Modifier.weight(1f).fillMaxWidth(), animationSpec = if (motion && mode != LiveMode.RECORDING && !(leftP && shown == LiveMode.ARRIVAL)) tween(300) else snap()  /* LocalMotion 게이트 */, label = "live") { m ->
                    val pad = Modifier.fillMaxSize().padding(horizontal = side).padding(top = 24.dp, bottom = 40.dp)
                    if (nearby != null && !leftP) NearbyPane(pad) else when (m) {
                        LiveMode.ASK -> (ask ?: lastAsk)?.let { a ->
                            Box(pad, contentAlignment = Alignment.TopCenter) {
                                AskCard(a, moments.size, names, listening = voice == ai.yeoro.voice.VoiceState.LISTENING && a.kind == "departure", onAnswer = { vm.answer(a, it) })
                            }
                        } ?: Spacer(pad)
                        // P를 벗어난 프레임에는 사라지는 중인 카드도 그리지 않는다 (Crossfade가 이전 내용을 한 프레임 더 들고 있지 않게)
                        LiveMode.ARRIVAL -> if (leftP) Spacer(pad) else (arrival ?: lastArrival)?.let { ArrivalCard(it, wide, onClose = vm::closeArrival, modifier = pad) } ?: Spacer(pad)
                        LiveMode.START -> (started ?: lastStarted)?.let { StartScene(it, wide, names, pad) } ?: Spacer(pad)
                        LiveMode.RECORDING -> RecordingPane(state ?: TripState.TRIP, trip, moments, pad, promo, onOpenAlbum)
                        LiveMode.OFF, LiveMode.ALBUM_READY, LiveMode.WAITING, LiveMode.DAILY ->
                            // 사진 카드가 같은 여행을 열면 [앨범 보기]는 카드에만 둔다 (같은 버튼 두 개)
                            DailyPane(m, pad, onOpenAlbum = albumTripId?.takeIf { m == LiveMode.ALBUM_READY && (promo as? Promo.Photos)?.tripId != it }?.let { id -> { onOpenAlbum(id) } },
                                promo = promo, onPromo = onOpenAlbum, onClosePromo = vm::closeMemory, latest = latest)
                    }
                }
            }
        }
    }
}

/** 엔진 시계(시연 시계 포함)의 날짜. 틱마다가 아니라 날짜가 바뀔 때만 내보낸다 */
private val engineDay = EngineBus.lastSnapshot.map { today(it?.timeMs ?: EngineBus.nowMs()) }.distinctUntilChanged()

@Composable private fun LiveHeader(recording: Boolean, modifier: Modifier) {
    val day by engineDay.collectAsStateWithLifecycle(initialValue = today(EngineBus.lastSnapshot.value?.timeMs ?: EngineBus.nowMs()))
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppMark(40.dp)
        Text("여로", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        if (recording) RecPill() else Text(day, style = MaterialTheme.typography.labelLarge, color = muted())
    }
}

private fun today(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()   // 엔진과 같은 시계(시연 시계 포함)
    val dow = "월화수목금토일"[d.dayOfWeek.value - 1]
    return "%d.%02d.%02d %c".format(d.year, d.monthValue, d.dayOfMonth, dow)
}

@Composable private fun RecPill() {
    Row(Modifier.background(RecRed.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(18.dp).background(RecRed, CircleShape))
        Text("기록 중", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 일상·꺼짐·앨범 준비·신호 기다림: 정적. 왼쪽에 지금 상태와 올해 기록, 오른쪽에 큰 그림(가장 최근 앨범 표지, 없으면 차 그림), 아래에 사진 도착·회상 카드(livePromo).
 * onOpenAlbum: 앨범 준비 장면의 [앨범 보기]. 주행 중(UX 제한)에는 버튼을 숨기고 표지 대신 차 그림을 보인다.
 */
@Composable private fun DailyPane(mode: LiveMode, modifier: Modifier, onOpenAlbum: (() -> Unit)?, promo: Promo?, onPromo: (Long) -> Unit, onClosePromo: () -> Unit, latest: LatestTrip? = null) {
    val restricted = LocalDrivingRestricted.current
    val (title, sub) = when (mode) {
        LiveMode.OFF -> "여로 기록이 꺼져 있어요" to "판단도 기록도 하지 않아요. 설정에서 켤 수 있어요."
        LiveMode.ALBUM_READY -> "앨범이 준비됐어요" to (if ((onOpenAlbum != null || promo != null) && !restricted) "방금 다녀온 여행을 한 권으로 엮었어요." else "차가 서 있을 때 앨범으로 보여 드릴게요.")
        LiveMode.WAITING -> "차량 신호를 기다리는 중" to "잠시 뒤에 지금 상태를 알려 드릴게요."
        else -> "지금은 일상이에요" to "여행이 시작되면 조용히 기록할게요."
    }
    val hero = latest?.takeIf { mode != LiveMode.OFF && !restricted }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(20.dp).background(Dusk, CircleShape))
                    Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(sub, Modifier.widthIn(max = 900.dp), style = MaterialTheme.typography.bodyLarge, color = muted())
                if (onOpenAlbum != null && !restricted) Button(onOpenAlbum, Modifier.padding(top = 12.dp).heightIn(min = 104.dp).widthIn(min = 320.dp), shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Dusk, contentColor = Color(0xFF1B1206))) {
                    Text("앨범 보기", style = MaterialTheme.typography.titleMedium)
                }
                latest?.stats?.takeIf { it.trips > 0 && mode != LiveMode.OFF }?.let { st ->
                    Row(Modifier.padding(top = 36.dp).widthIn(max = 900.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (st.kmThisYear > 0) StatTile("${st.kmThisYear}", "km", "함께 달린 길", Modifier.widthIn(min = 220.dp))
                        StatTile("${st.places}", "곳", "다녀온 곳", Modifier.widthIn(min = 180.dp))
                        StatTile("${st.trips}", "권", "여행 앨범", Modifier.widthIn(min = 180.dp))
                    }
                }
                EngineDebug(Modifier.widthIn(max = 980.dp).fillMaxWidth())
            }
            if (hero != null) LatestCover(hero, Modifier.fillMaxHeight().aspectRatio(3f / 4f), onOpen = { onPromo(hero.book.tripId) })
            else CalmCar(Modifier.fillMaxHeight().aspectRatio(3f / 4f))
        }
        // 사진 도착·회상 카드 (livePromo가 고른 것, 정적). 회상 카드에는 [닫기]. 같은 여행의 표지가 이미 크게 있으면 썸네일은 뺀다
        promo?.let { PromoCard(it, onPromo, Modifier.fillMaxWidth(), onClose = onClosePromo.takeIf { _ -> it is Promo.Memory },
            showThumb = !(it is Promo.Memory && it.tripId == hero?.book?.tripId)) }
    }
}

/** 큰 숫자 한 칸 (올해 기록) */
@Composable private fun StatTile(value: String, unit: String, label: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 72.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(unit, Modifier.padding(start = 6.dp, bottom = 10.dp), style = MaterialTheme.typography.titleSmall, color = muted(), maxLines = 1, softWrap = false)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = muted(), maxLines = 1)
    }
}

/** 가장 최근 앨범 표지 (사진, 없으면 그날의 장면 일러스트). 누르면 앨범. 정적 */
@Composable private fun LatestCover(l: LatestTrip, modifier: Modifier, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(40.dp)
    val illustration = Modifier.fillMaxSize().scene(l.book.hour, l.book.rained, seed = l.book.tripId.toInt(), sea = l.book.sea)
    Box(modifier.shadow(20.dp, shape).clip(shape).clickable(onClickLabel = "앨범 열기", onClick = onOpen)) {
        if (l.photo != null) PhotoImage(l.photo, Modifier.fillMaxSize(), maxPx = 1100) { Box(illustration) } else Box(illustration)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC0E1220))))
        Text("최근 앨범", Modifier.align(Alignment.TopStart).padding(28.dp).background(Dusk, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1B1206))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 32.dp, bottom = 40.dp)) {
            Text(l.book.title, style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 56.sp,
                lineBreak = androidx.compose.ui.text.style.LineBreak.Heading), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${l.book.date} · ${l.book.place}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 앨범이 아직 없거나 주행 중일 때의 차 그림 (아무 좌석도 켜지지 않은 고요한 차) */
@Composable private fun CalmCar(modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f), RoundedCornerShape(40.dp)).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        CarTopView(emptySet(), trunk = false, Modifier.weight(1f).fillMaxWidth())
        Text("차가 이미 아는 신호로 기억해요", style = MaterialTheme.typography.titleSmall, color = muted(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** 기록 중: 무조건 정적. 큰 숫자 + 최근 순간 3개 + 주행 중 화면 원칙. */
@Composable private fun RecordingPane(state: TripState, trip: TripEntity?, moments: List<MomentEntity>, modifier: Modifier, promo: Promo?, onPromo: (Long) -> Unit) {
    val lines = moments.mapNotNull { m -> momentLine(m.type, m.detail)?.let { m.timeMs to it } }
    val lead = when (state) {
        TripState.CANDIDATE -> "여행일지도 몰라요 · 조용히 남기는 중"
        TripState.STAYING -> "여행지에서 머무는 중" + (trip?.destination?.let { " · $it" } ?: "")
        else -> trip?.destination?.let { "여행 중 · $it" } ?: "여행 중"
    }
    Column(modifier) {
        Text(lead, style = MaterialTheme.typography.titleMedium, color = muted())
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("${lines.size}", style = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = 160.sp, lineHeight = 168.sp,
                fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
            Text("오늘의 순간", Modifier.padding(bottom = 28.dp), style = MaterialTheme.typography.titleLarge, color = muted())
        }
        // 큰 아이콘 타임라인 (정적). 사진 카드가 있으면(P단) 최근 순간은 세 개만
        MomentTimeline(lines.takeLast(if (promo != null) 3 else 4), Modifier.widthIn(max = 1400.dp).fillMaxWidth().padding(top = 48.dp))
        promo?.let { PromoCard(it, onPromo, Modifier.padding(top = 24.dp).widthIn(max = 1200.dp).fillMaxWidth(), compact = true) }
        Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
        SafeNote(Modifier.widthIn(max = 1400.dp).fillMaxWidth())
    }
}

/**
 * 기록 중 타임라인: 순간마다 큰 아이콘 원, 시각, 짧은 말. 가로로 나란히, 원 사이를 선 하나로 잇는다. 움직임 없음.
 * 아직 순간이 없으면 기록하는 네 가지(사람·비·창문·쉼)를 흐린 아이콘으로 보여 준다.
 */
@Composable private fun MomentTimeline(lines: List<Pair<Long, MomentLine>>, modifier: Modifier) {
    val empty = lines.isEmpty()
    val items: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, String>> = if (empty)
        listOf(Triple("", LiveIcons.User, "함께 탄 사람"), Triple("", LiveIcons.Rain, "비"), Triple("", LiveIcons.Window, "창문"), Triple("", LiveIcons.Stop, "쉬어 간 곳"))
    else lines.map { (t, l) ->
        val tm = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime()
        Triple("%02d:%02d".format(tm.hour, tm.minute), LiveIcons.of(l.icon), l.text)
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val amber = duskInk()
    val node = 144.dp
    Column(modifier) {
        if (empty) Text("이런 순간을 조용히 기록해요", Modifier.padding(bottom = 24.dp), style = MaterialTheme.typography.titleSmall, color = muted())
        Box {
            // 원 가운데를 지나는 선 (첫 원 가운데 ~ 마지막 원 가운데)
            if (items.size > 1) Box(Modifier.fillMaxWidth().padding(top = node / 2 - 2.dp).height(4.dp).drawBehind {
                val cw = size.width / items.size
                drawLine(if (empty) ink.copy(alpha = 0.10f) else amber.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(cw / 2, size.height / 2),
                    androidx.compose.ui.geometry.Offset(size.width - cw / 2, size.height / 2), strokeWidth = size.height)
            })
            Row(Modifier.fillMaxWidth()) {
                items.forEachIndexed { i, (time, icon, label) ->
                    val last = !empty && i == items.lastIndex
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(node).background(when { empty -> MaterialTheme.colorScheme.background; last -> Dusk; else -> MaterialTheme.colorScheme.surface }, CircleShape)
                            .drawBehind { if (!last) drawCircle(if (empty) ink.copy(alpha = 0.14f) else amber, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(4.dp.toPx())) },
                            contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(72.dp), tint = when { empty -> muted().copy(alpha = 0.6f); last -> Color(0xFF1A1306); else -> amber })
                        }
                        if (time.isNotEmpty()) Text(time, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = muted())
                        Text(label, Modifier.padding(top = if (time.isEmpty()) 20.dp else 4.dp), style = MaterialTheme.typography.titleMedium.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                            color = if (empty) muted() else ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable private fun SafeNote(modifier: Modifier) {
    val dash = muted().copy(alpha = 0.5f)
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) { append("주행 중 화면 원칙") }
        append(" · 움직이는 효과와 알림을 모두 끕니다. 기록은 계속되고, 차가 서면 보여 드려요.")
    }, modifier.drawBehind {
        drawRoundRect(dash, cornerRadius = CornerRadius(22.dp.toPx()),
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))))
    }.padding(horizontal = 32.dp, vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = muted())
}
