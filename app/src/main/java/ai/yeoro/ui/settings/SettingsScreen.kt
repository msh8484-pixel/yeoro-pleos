package ai.yeoro.ui.settings

import ai.yeoro.EngineBus
import ai.yeoro.R
import ai.yeoro.engine.Seat
import ai.yeoro.settings.Settings
import ai.yeoro.ui.live.seatName
import ai.yeoro.ui.theme.duskInk
import ai.yeoro.ux.LocalDrivingRestricted
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val MinTouch = 96.dp
private val CardShape = RoundedCornerShape(28.dp)
private val SeatOrder = listOf(Seat.DRIVER, Seat.PASSENGER, Seat.REAR_LEFT, Seat.REAR_CENTER, Seat.REAR_RIGHT)

/** 무엇을 기록하는지 (동의 화면과 설정이 같은 목록을 쓴다) */
private val Recorded = listOf("좌석", "와이퍼", "창문", "정차", "트렁크", "위치 경로")
private val Promises = listOf(
    "일상 운행 기록은 24시간 뒤 지워요",
    "얼굴 인식도, 음성 녹음도 하지 않아요",
    "사진은 가족이 직접 올린 것만 앨범에 들어가요",
)

/**
 * 차 밖으로 나가는 것 (동의 화면과 설정이 같은 목록을 쓴다). 이 밖의 기록(위치 경로·좌석·순간)은 차 안에만 있다.
 * AI 기록문 줄은 그 기능(2b Task 11)이 들어오기 전까지 숨긴다.
 */
internal const val SHOW_AI_DISCLOSURE = false
internal fun outsideItems(showAi: Boolean = SHOW_AI_DISCLOSURE): List<Pair<String, String>> = listOfNotNull(
    "사진 올리기를 위해 여행 등록" to "공유 토큰, 출발 시각, 목적지 이름, 이 차의 기기 확인 값(무작위 값, 서버에는 바꾼 값만 남아요). 지나온 길(위치 기록)은 보내지 않아요.",
    "사진" to "가족이 QR로 직접 올린 사진만 받아요. 서버에는 30일(+정리 7일)까지만 두어요.",
    "날씨·일몰·관광 정보" to "목적지 좌표와 도착 시각으로 기상청·천문연·한국관광공사에 물어봐요.",
    ("AI 기록문(켤 때만)" to "기록문의 사실 문장만, 가족 이름은 빼고 보내요.").takeIf { showAi },
)
internal const val OUTSIDE_NOTE = "여행을 지우면, 동의가 켜져 있을 때 서버의 사진과 등록도 함께 지워요. 기록 동의를 끄면 아무것도 보내지 않아요."

/** 한국어는 어절 단위로 줄을 바꾼다 ("앨범\n에" 같은 갈라짐 방지) */
private val KoParagraph = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
private fun TextStyle.ko() = copy(lineBreak = KoParagraph)

@Composable private fun muted() = MaterialTheme.colorScheme.onSurfaceVariant
@Composable private fun line() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

/** 여로 표시: 해 질 녘 그라데이션 둥근 사각형 안의 흰 지도 핀 (앱 아이콘과 같은 모양) */
@Composable fun YeoroMark(size: Dp) {
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(Color(0xFFFFB46A), Color(0xFFFF8A7A), Color(0xFF6F5BD8))), RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_yeoro), null, Modifier.size(size * 0.62f), tint = Color.White)
    }
}

/**
 * 첫 실행 동의 화면 (동의를 아직 정하지 않았고 차가 서 있을 때만). [기록 시작하기] = 동의, [나중에] = 꺼진 채로 둔다.
 * 정적 화면이다(모션 없음).
 */
@Composable fun ConsentScreen(onDecide: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = if (maxWidth < 700.dp) 32.dp else 64.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = side, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        YeoroMark(72.dp)
                        Text("여로", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("우리 가족 여행을\n차가 기억해도 될까요?", style = MaterialTheme.typography.headlineLarge.ko(), color = MaterialTheme.colorScheme.onSurface)
                        Text("차가 이미 아는 신호만 조용히 남겨요. 카메라로 찍지 않아요. 마이크는 설정에서 ‘말로 답하기’를 켜 두면 출발 질문 때만, 지금 화면이 보일 때 잠깐 열어요.", style = MaterialTheme.typography.bodyLarge.ko(), color = muted())
                    }
                    RecordedBlock()
                    PromiseBlock()
                    OutsideBlock()
                    Text("설정에서 언제든 끄거나 켤 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        val shape = RoundedCornerShape(24.dp)
                        Button({ onDecide(false) }, Modifier.weight(1f).heightIn(min = MinTouch), shape = shape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Text("나중에", style = MaterialTheme.typography.titleSmall)
                        }
                        Button({ onDecide(true) }, Modifier.weight(1f).heightIn(min = MinTouch), shape = shape,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Text("기록 시작하기", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RecordedBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("기록하는 것", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Recorded.forEach {
                Text(it, Modifier.border(1.dp, line(), RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable private fun PromiseBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Promises.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(20.dp).background(duskInk(), RoundedCornerShape(50)))
                Text(p, style = MaterialTheme.typography.bodyLarge.ko(), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** 차 밖으로 나가는 것: 항목 이름(굵게) + 설명, 끝에 지우기·동의 끄기 안내 */
@Composable private fun OutsideBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("차 밖으로 나가는 것", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        outsideItems().forEach { (head, body) ->
            Column(Modifier.fillMaxWidth().border(1.dp, line(), RoundedCornerShape(20.dp)).padding(horizontal = 28.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(head, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold).ko(), color = MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
            }
        }
        Text(OUTSIDE_NOTE, style = MaterialTheme.typography.bodyMedium.ko(), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 설정: 여로 기록 동의, 음성, 집 위치, 좌석별 가족 이름. 주행 중에는 모두 읽기 전용(차를 세우면 바꾼다). */
@Composable fun SettingsScreen() {
    val settings = Settings.get(LocalContext.current)
    val consent by settings.consent.collectAsStateWithLifecycle()
    val home by settings.home.collectAsStateWithLifecycle()
    val auto by settings.homeAuto.collectAsStateWithLifecycle()
    val names by settings.familyNames.collectAsStateWithLifecycle()
    val here by EngineBus.location.collectAsStateWithLifecycle()
    val voiceGuide by settings.voiceGuide.collectAsStateWithLifecycle()
    val voiceAnswer by settings.voiceAnswer.collectAsStateWithLifecycle()
    val restricted = LocalDrivingRestricted.current
    val context = LocalContext.current
    fun micGranted() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var mic by remember { mutableStateOf(micGranted()) }
    // 말로 답하기를 켤 때만 마이크 권한을 묻는다. 거절하면 꺼진 채로 둔다
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        mic = ok; settings.setVoiceAnswer(ok)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = if (maxWidth < 700.dp) 32.dp else 56.dp
            val wide = maxWidth >= 1100.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = side, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 1400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("설정", style = MaterialTheme.typography.headlineLarge.ko(), color = MaterialTheme.colorScheme.onSurface)
                    ConsentCard(consent == true, restricted) { settings.setConsent(it) }
                    VoiceCard(consent == true, voiceGuide, voiceAnswer && mic, restricted,
                        onGuide = settings::setVoiceGuide,
                        onAnswer = { on -> if (!on) settings.setVoiceAnswer(false) else if (micGranted()) { mic = true; settings.setVoiceAnswer(true) } else askMic.launch(android.Manifest.permission.RECORD_AUDIO) })
                    HomeCard(home, auto, here, restricted, onHere = { settings.setHomeHere(here) }, onReset = settings::resetHome)
                    FamilyCard(names, restricted, wide) { seat, n -> settings.setFamilyName(seat, n) }
                }
            }
        }
    }
}

@Composable private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, CardShape).border(1.dp, line(), CardShape).padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

/** 주행 중(UX 제한)에는 다른 설정처럼 읽기 전용: 달리는 중에 기록을 끄면 여행이 그 자리에서 닫힌다. 차를 세우면 바꿀 수 있다. */
@Composable private fun ConsentCard(on: Boolean, restricted: Boolean, onChange: (Boolean) -> Unit) {
    Section("여로 기록") {
        Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(on, enabled = !restricted, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (on) "켜져 있어요" else "꺼져 있어요", style = MaterialTheme.typography.titleSmall, color = if (on) duskInk() else MaterialTheme.colorScheme.onSurface)
                Text(if (on) "가족 여행을 차가 조용히 기록해요." else "판단도 기록도 하지 않아요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
            }
            // 줄 전체가 스위치다(터치 영역 96dp). 스위치 자체는 표시만
            Switch(on, null, Modifier.scale(1.5f).padding(horizontal = 20.dp), enabled = !restricted)
        }
        if (restricted) Text("차를 세우면 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        RecordedBlock()
        PromiseBlock()
        OutsideBlock()
    }
}

/**
 * 음성: 도착 인사(TTS)와 출발 질문에 말로 답하기(STT). Pleos 온디바이스라 음성이 차 밖으로 나가지 않는다.
 * 기록 동의가 꺼져 있으면 질문·도착 카드가 애초에 없으니 둘 다 비활성. 주행 중에는 다른 설정처럼 읽기 전용.
 */
@Composable private fun VoiceCard(consent: Boolean, guide: Boolean, answer: Boolean, restricted: Boolean, onGuide: (Boolean) -> Unit, onAnswer: (Boolean) -> Unit) {
    val enabled = consent && !restricted
    Section("음성") {
        ToggleRow("도착을 소리로 알려 주기", "차를 세우고 도착 카드가 뜰 때 한 번 말해요.", guide && consent, enabled, onGuide)
        ToggleRow("출발 질문에 말로 답하기", "차를 세우고 지금 화면을 보고 있을 때만 출발 질문을 소리로 묻고, 그 뒤 6초 동안만 마이크를 열어요. 듣는 동안 카드에 ‘듣고 있어요’가 보여요.", answer && consent, enabled, onAnswer)
        Text("음성은 차 안에서만 처리돼요. 밖으로 보내지 않고, 녹음해 두지도 않아요.", style = MaterialTheme.typography.bodyMedium.ko(), color = MaterialTheme.colorScheme.onSurface)
        when {
            !consent -> Text("여로 기록을 켜면 쓸 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
            restricted -> Text("차를 세우면 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        }
    }
}

/** 줄 전체가 스위치 (터치 영역 96dp). 스위치 자체는 표시만 */
@Composable private fun ToggleRow(title: String, sub: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        }
        Switch(on, null, Modifier.scale(1.5f).padding(horizontal = 20.dp), enabled = enabled)
    }
}

@Composable private fun HomeCard(
    home: Pair<Double, Double>?, auto: Boolean, here: Pair<Double, Double>?, restricted: Boolean,
    onHere: () -> Unit, onReset: () -> Unit,
) {
    Section("집 위치") {
        val (status, sub) = when {
            home == null -> "아직 정하지 않았어요" to "다음에 받은 위치를 집으로 정해요."
            auto -> "자동으로 정해졌어요" to "처음 받은 위치를 집으로 썼어요 · ${coord(home)}"
            else -> "직접 정했어요" to coord(home)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        }
        Text("집에서 출발하고 집으로 돌아오는 것으로 여행의 시작과 끝을 알아요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val shape = RoundedCornerShape(24.dp)
            Button(onHere, Modifier.weight(1f).heightIn(min = MinTouch), enabled = !restricted && here != null, shape = shape) {
                Text("지금 위치를 집으로", style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            OutlinedButton(onReset, Modifier.weight(1f).heightIn(min = MinTouch), enabled = !restricted && home != null, shape = shape) {
                Text("다시 정하기", style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        when {
            restricted -> Text("차를 세우면 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
            here == null -> Text("지금 위치를 아직 모르겠어요. 내비가 위치를 잡으면 누를 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        }
    }
}

private fun coord(p: Pair<Double, Double>) = "%.4f, %.4f".format(p.first, p.second)

@Composable private fun FamilyCard(names: Map<Seat, String>, restricted: Boolean, wide: Boolean, onChange: (Seat, String) -> Unit) {
    Section("가족 이름") {
        Text("출발 카드와 앨범 기록문에서 좌석 대신 이름을 써요. 비워 두면 \"운전석\"처럼 좌석 이름을 써요.", style = MaterialTheme.typography.bodyMedium.ko(), color = muted())
        if (restricted) Text("차를 세우면 고칠 수 있어요.", style = MaterialTheme.typography.bodyMedium.ko(), color = duskInk())
        val rows = if (wide) SeatOrder.chunked(2) else SeatOrder.map { listOf(it) }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { s -> SeatName(s, names[s], restricted, Modifier.weight(1f)) { onChange(s, it) } }
                if (row.size == 1 && wide) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun SeatName(s: Seat, name: String?, restricted: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(seatName(s), style = MaterialTheme.typography.labelLarge, color = muted())
        if (restricted) {
            // 주행 중: 읽기 전용
            Box(Modifier.fillMaxWidth().heightIn(min = MinTouch).border(1.dp, line(), RoundedCornerShape(20.dp)).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterStart) {
                Text(name ?: "비어 있음", style = MaterialTheme.typography.bodyLarge.ko(), color = if (name != null) MaterialTheme.colorScheme.onSurface else muted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            var text by remember(s) { mutableStateOf(name.orEmpty()) }
            OutlinedTextField(
                text, { v -> val t = v.take(Settings.NAME_MAX); text = t; onChange(t) },
                Modifier.fillMaxWidth().heightIn(min = MinTouch),
                textStyle = MaterialTheme.typography.bodyLarge, singleLine = true, shape = RoundedCornerShape(20.dp),
                placeholder = { Text(placeholderOf(s), style = MaterialTheme.typography.bodyLarge.ko(), color = muted().copy(alpha = 0.6f)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
    }
}

private fun placeholderOf(s: Seat) = when (s) {
    Seat.DRIVER -> "예: 아빠"; Seat.PASSENGER -> "예: 엄마"; Seat.REAR_LEFT -> "예: 민지"; Seat.REAR_CENTER -> "예: 할머니"; Seat.REAR_RIGHT -> "예: 민호"
}
