package com.lpecom.gemglasses.bobai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lpecom.gemglasses.bobai.domain.ModeStatus
import com.lpecom.gemglasses.bobai.domain.OperatingMode
import com.lpecom.gemglasses.bobai.domain.SessionStatus

// Theme: high-contrast, work-oriented. No dynamic colour so status colours stay
// predictable; every status also carries an icon and text (never colour-only).

private val Light = lightColorScheme(
    primary = Color(0xFF0B4F9C), onPrimary = Color.White,
    secondary = Color(0xFF1F5130), tertiary = Color(0xFF7A4A00),
    background = Color(0xFFF7F8FA), surface = Color.White, onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE6EAF0), onSurfaceVariant = Color(0xFF2F3A45),
    error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CC6FF), onPrimary = Color(0xFF002F65),
    secondary = Color(0xFF9ED6AE), tertiary = Color(0xFFFFC870),
    background = Color(0xFF0E1114), surface = Color(0xFF161A1F), onSurface = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFF262C33), onSurfaceVariant = Color(0xFFCDD5DE),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BobAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** True on the unfolded inner display / tablets: available width ≥ 600dp (not device detection). */
val LocalExpanded = staticCompositionLocalOf { false }

enum class Tone { OK, WARN, BAD, NEUTRAL }

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.OK -> MaterialTheme.colorScheme.secondary
    Tone.WARN -> MaterialTheme.colorScheme.tertiary
    Tone.BAD -> MaterialTheme.colorScheme.error
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val icon = when (tone) {
        Tone.OK -> Icons.Filled.CheckCircle
        Tone.WARN, Tone.BAD -> Icons.Filled.Warning
        Tone.NEUTRAL -> Icons.Filled.Info
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, toneColor(tone)),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = toneColor(tone), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MockBanner(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.tertiary, modifier = modifier.fillMaxWidth()) {
        Text(
            "MOCK DATA — fictional equipment and documents for UI testing. Not for field use.",
            color = MaterialTheme.colorScheme.background,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

fun ModeStatus.tone(): Tone = when (mode) {
    OperatingMode.CONNECTED -> Tone.OK
    OperatingMode.OFFLINE_ASSISTED, OperatingMode.OFFLINE_REFERENCE -> Tone.WARN
    OperatingMode.OFFLINE_NO_REFERENCES -> Tone.BAD
}

/** Glasses / mode / capture status. Camera and mic are reported off: nothing in BobAI opens them yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusStrip(glasses: GlassesStatus, mode: ModeStatus, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusChip(glasses.label, if (glasses.ok) Tone.OK else Tone.WARN)
        StatusChip(mode.mode.label, mode.tone())
        StatusChip("Camera off · Mic off", Tone.NEUTRAL)
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) { Text(text) }
}

object Routes {
    const val HOME = "home"
    const val JOBS = "jobs"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val ASSISTANT = "assistant"
    const val EQUIPMENT = "equipment"
    const val DOWNLOADS = "downloads"
    const val LIVE = "live"
    fun doc(id: String, page: Int? = null) = "doc/$id?page=${page ?: -1}"
    fun job(id: String) = "job/$id"
    fun summary(id: String) = "summary/$id"
}

private enum class TopTab(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home),
    JOBS(Routes.JOBS, "Jobs", Icons.Filled.Work),
    LIBRARY(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.MenuBook),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}

fun NavHostController.goTop(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BobAiRoot(legacyContent: @Composable () -> Unit) {
    val vm: BobAiViewModel = hiltViewModel()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        CompositionLocalProvider(LocalExpanded provides expanded) {
            val isTop = TopTab.entries.any { it.route == route }
            Row(Modifier.fillMaxSize()) {
                if (expanded && route != Routes.LIVE) {
                    NavigationRail(Modifier.statusBarsPadding()) {
                        TopTab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = route == t.route,
                                onClick = { nav.goTop(t.route) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        if (route == Routes.LIVE) {
                            TopAppBar(
                                title = { Text("Live voice (existing GemGlasses)") },
                                navigationIcon = {
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to BobAI")
                                    }
                                },
                            )
                        } else {
                            MockBanner(Modifier.statusBarsPadding())
                        }
                    },
                    bottomBar = {
                        Column {
                            val active = snapshot.sessions.firstOrNull {
                                it.id == snapshot.activeSessionId && it.status != SessionStatus.ENDED
                            }
                            if (active != null && route != Routes.ASSISTANT && route != Routes.EQUIPMENT && route != Routes.LIVE) {
                                ActiveSessionBar(
                                    text = "Active session · " + (active.equipment?.model ?: "equipment not confirmed"),
                                    onOpen = { nav.navigate(if (active.equipment == null) Routes.EQUIPMENT else Routes.ASSISTANT) },
                                )
                            }
                            val showTabs = !expanded && (isTop || route == null)
                            if (!showTabs && route != Routes.LIVE) {
                                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            }
                            if (showTabs) {
                                NavigationBar {
                                    TopTab.entries.forEach { t ->
                                        NavigationBarItem(
                                            selected = route == t.route,
                                            onClick = { nav.goTop(t.route) },
                                            icon = { Icon(t.icon, contentDescription = null) },
                                            label = { Text(t.label) },
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        NavHost(nav, startDestination = Routes.HOME) {
                            composable(Routes.HOME) { HomeScreen(vm, nav) }
                            composable(Routes.JOBS) { JobsScreen(vm, nav) }
                            composable(Routes.LIBRARY) { LibraryScreen(vm, nav) }
                            composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
                            composable(Routes.ASSISTANT) { AssistantScreen(vm, nav) }
                            composable(Routes.EQUIPMENT) { EquipmentScreen(vm, nav) }
                            composable(Routes.DOWNLOADS) { DownloadsScreen(vm, nav) }
                            composable(Routes.LIVE) { legacyContent() }
                            composable(
                                "doc/{id}?page={page}",
                                arguments = listOf(
                                    navArgument("id") { type = NavType.StringType },
                                    navArgument("page") { type = NavType.IntType; defaultValue = -1 },
                                ),
                            ) { e ->
                                DocumentScreen(vm, nav, e.arguments?.getString("id").orEmpty(), e.arguments?.getInt("page")?.takeIf { it >= 0 })
                            }
                            composable("job/{id}") { e -> JobDetailScreen(vm, nav, e.arguments?.getString("id").orEmpty()) }
                            composable("summary/{id}") { e -> SummaryScreen(vm, nav, e.arguments?.getString("id").orEmpty()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionBar(text: String, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.weight(1f))
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Open session") }
        }
    }
}

/** Standard screen header with optional back button. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}
