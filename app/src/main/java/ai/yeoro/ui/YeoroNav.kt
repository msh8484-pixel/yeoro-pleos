package ai.yeoro.ui

import ai.yeoro.ui.album.TripAlbumScreen
import androidx.activity.compose.BackHandler
import ai.yeoro.ui.home.HomeScreen
import ai.yeoro.ui.live.LiveScreen
import ai.yeoro.settings.Settings
import ai.yeoro.ui.settings.ConsentScreen
import ai.yeoro.ui.settings.SettingsScreen
import ai.yeoro.ux.LocalDrivingRestricted
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import ai.yeoro.ui.theme.AlbumTheme
import ai.yeoro.ux.LocalMotion
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val LIVE = "live"
    const val HOME = "home"
    /** from: 앨범을 연 곳 ("home" = 책장). 뒤로 가기가 그곳으로 돌아간다 */
    const val TRIP = "trip/{id}?from={from}"
    const val SETTINGS = "settings"
    /** 탭에서 들어갈 때는 id를 모르므로 0(= 가장 최근 여행으로 해석할 자리). */
    fun trip(id: Long = 0, from: String? = null) = "trip/$id" + (from?.let { "?from=$it" } ?: "")
}

private class Dest(val route: String, val label: String, val icon: ImageVector)
private val Dests = listOf(
    Dest(Routes.LIVE, "지금", IconLive),
    Dest(Routes.HOME, "우리 가족\n여행", IconHome),
    Dest(Routes.TRIP, "앨범", IconAlbum),
    Dest(Routes.SETTINGS, "설정", IconSettings),
)
private val MinTouch = 76.dp
private const val RailMinWidthDp = 600

@Composable fun YeoroApp() {
    // 첫 실행: 동의를 아직 정하지 않았으면 차가 서 있을 때 동의 화면부터 (정하기 전에는 기록하지 않는다)
    val settings = Settings.get(LocalContext.current)
    val consent by settings.consent.collectAsStateWithLifecycle()
    if (consent == null && !LocalDrivingRestricted.current) { ConsentScreen(settings::setConsent); return }
    val nav = rememberNavController()
    // 도착 알림에서 열면 지금 탭으로
    val openTab by MainActivity.openTab.collectAsStateWithLifecycle()
    LaunchedEffect(openTab) { openTab?.let { r -> Dests.firstOrNull { it.route == r }?.let { go(nav, it) }; MainActivity.openTab.value = null } }
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    // 레일/바도 현재 목적지의 테마를 따른다(앨범이면 남색 + 앰버 선택).
    AlbumTheme(active = route == Routes.TRIP) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= RailMinWidthDp.dp
                if (wide) Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxHeight().widthIn(min = 120.dp).background(MaterialTheme.colorScheme.surface),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) {
                        Dests.forEach { d -> NavItem(d, route == d.route, Modifier.widthIn(min = MinTouch)) { go(nav, d) } }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { YeoroNavHost(nav) }
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { YeoroNavHost(nav) }
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Dests.forEach { d -> NavItem(d, route == d.route, Modifier.weight(1f)) { go(nav, d) } }
                    }
                }
            }
        }
    }
}

/** 40dp 아이콘 + 72×48dp 선택 표시 + 라벨. 라벨은 줄바꿈을 허용하고 말줄임하지 않는다. */
@Composable private fun NavItem(d: Dest, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.heightIn(min = MinTouch).selectable(selected, role = Role.Tab, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp, 48.dp).background(if (selected) tint.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(d.icon, null, Modifier.size(40.dp), tint = tint) }
        Text(d.label, style = MaterialTheme.typography.labelLarge, color = tint, textAlign = TextAlign.Center)
    }
}

private fun go(nav: NavHostController, d: Dest) {
    val target = if (d.route == Routes.TRIP) Routes.trip() else d.route
    nav.navigate(target) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 책장에서 앨범 열기: 앨범 탭으로 옮겨 가는 것처럼 연다. 홈 위에 쌓으면 홈 탭의 restoreState가 [홈, 앨범]을 되살려
 * 홈 탭을 눌러도 앨범이 그대로 남는다.
 */
private fun openTrip(nav: NavHostController, id: Long, from: String? = null) {
    nav.navigate(Routes.trip(id, from)) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}

@Composable private fun YeoroNavHost(nav: NavHostController) {
    val motion = LocalMotion.current
    val fadeIn: AnimatedContentTransitionScope<*>.() -> EnterTransition =
        { if (motion) fadeIn(tween(200)) else EnterTransition.None }
    val fadeOut: AnimatedContentTransitionScope<*>.() -> ExitTransition =
        { if (motion) fadeOut(tween(200)) else ExitTransition.None }
    NavHost(nav, startDestination = Routes.LIVE,
        enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() }) {
        composable(Routes.LIVE) { LiveScreen(onOpenAlbum = { id -> openTrip(nav, id) }) }
        composable(Routes.HOME) { HomeScreen(onOpenTrip = { id -> openTrip(nav, id, from = Routes.HOME) }) }
        composable(Routes.TRIP, arguments = listOf(
            navArgument("id") { type = NavType.LongType },
            navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { entry ->
            // 책장에서 연 앨범: 뒤로 가기는 지금이 아니라 우리 가족 여행 탭으로 (앨범 안의 장 되돌리기가 먼저 받는다: 그 BackHandler가 나중에 등록됨)
            BackHandler(enabled = entry.arguments?.getString("from") == Routes.HOME) { go(nav, Dests.first { it.route == Routes.HOME }) }
            TripAlbumScreen(onDeleted = { go(nav, Dests.first { it.route == Routes.HOME }) })   // id 0 = 가장 최근에 끝난 확인된 여행 (탭), 책장은 여행 id로 연다
        }
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}
