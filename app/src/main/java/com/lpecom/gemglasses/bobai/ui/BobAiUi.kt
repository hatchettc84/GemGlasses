package com.lpecom.gemglasses.bobai.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// =========================================================================
// Industrial Brand Presets (Grainger MRO, DeWalt Pro, Milwaukee ONE-KEY, BobAI Core)
// =========================================================================

enum class IndustrialBrand(
    val id: String,
    val displayName: String,
    val badgeCode: String,
    val divisionTag: String,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val accentHazard: Color,
) {
    BOBAI_CORE(
        id = "bobai",
        displayName = "BobAI Field Core",
        badgeCode = "BAI-PRO",
        divisionTag = "INDUSTRIAL FIELD OPS · OSHA/LOTO",
        primaryDark = Color(0xFFFF8C00),   // Safety Signal Orange
        onPrimaryDark = Color(0xFF101317),
        primaryLight = Color(0xFFD65A00),
        onPrimaryLight = Color.White,
        accentHazard = Color(0xFFFFB800),
    ),
    GRAINGER_MRO(
        id = "grainger",
        displayName = "Grainger MRO Connect",
        badgeCode = "GWW-MRO",
        divisionTag = "KEEPING THE WORLD WORKING · MRO SUPPLY",
        primaryDark = Color(0xFFE1251B),   // Grainger Red
        onPrimaryDark = Color.White,
        primaryLight = Color(0xFFC4161C),
        onPrimaryLight = Color.White,
        accentHazard = Color(0xFFFFB800),
    ),
    DEWALT_PRO(
        id = "dewalt",
        displayName = "DEWALT SiteManager",
        badgeCode = "DW-20V",
        divisionTag = "GUARANTEED TOUGH · COMMERCIAL FLEET",
        primaryDark = Color(0xFFFEBD17),   // DeWalt High-Vis Yellow
        onPrimaryDark = Color(0xFF101215),
        primaryLight = Color(0xFFD99B00),
        onPrimaryLight = Color(0xFF101215),
        accentHazard = Color(0xFFFEBD17),
    ),
    MILWAUKEE_ONEKEY(
        id = "milwaukee",
        displayName = "Milwaukee ONE-KEY Field",
        badgeCode = "MW-M18",
        divisionTag = "NOTHING BUT HEAVY DUTY · ASSET DIAGNOSTICS",
        primaryDark = Color(0xFFDB011C),   // Milwaukee Heavy Duty Red
        onPrimaryDark = Color.White,
        primaryLight = Color(0xFFB80017),
        onPrimaryLight = Color.White,
        accentHazard = Color(0xFFFF9E00),
    ),
}

val LocalIndustrialBrand = staticCompositionLocalOf { IndustrialBrand.BOBAI_CORE }
val LocalBrandSetter = staticCompositionLocalOf<(IndustrialBrand) -> Unit> { {} }
val LocalDarkThemeOverride = staticCompositionLocalOf<Boolean?> { true } // Default to dark tactical slate for field readability
val LocalDarkThemeSetter = staticCompositionLocalOf<(Boolean?) -> Unit> { {} }

private val IndustrialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private val IndustrialTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Black,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp,
    ),
)

private fun buildColorScheme(brand: IndustrialBrand, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = brand.primaryDark,
        onPrimary = brand.onPrimaryDark,
        secondary = Color(0xFF2EA043),       // ANSI Safety Verified Green
        onSecondary = Color.White,
        tertiary = brand.accentHazard,       // OSHA Caution Amber
        onTertiary = Color(0xFF101317),
        background = Color(0xFF0B0E12),      // Deep Carbon Chassis
        surface = Color(0xFF141920),         // Rugged Panel Slate
        onSurface = Color(0xFFF3F6FA),       // High-Vis Crisp White
        surfaceVariant = Color(0xFF212832),  // Machined Steel Bezel
        onSurfaceVariant = Color(0xFFB8C4D0),
        error = Color(0xFFFF4D4D),           // Lockout / Stop Red
        onError = Color(0xFF140505),
    )
} else {
    lightColorScheme(
        primary = brand.primaryLight,
        onPrimary = brand.onPrimaryLight,
        secondary = Color(0xFF1A7F37),
        onSecondary = Color.White,
        tertiary = Color(0xFF9A6700),
        onTertiary = Color.White,
        background = Color(0xFFF0F3F6),      // Daylight Shop Floor Grey
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0E1318),
        surfaceVariant = Color(0xFFDCE2E9),
        onSurfaceVariant = Color(0xFF323C47),
        error = Color(0xFFCF222E),
        onError = Color.White,
    )
}

@Composable
fun BobAiTheme(content: @Composable () -> Unit) {
    var brandName by rememberSaveable { mutableStateOf(IndustrialBrand.BOBAI_CORE.name) }
    // Default to rugged dark mode for industrial contrast, with option to toggle daylight/system
    var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(true) }
    val brand = IndustrialBrand.entries.firstOrNull { it.name == brandName } ?: IndustrialBrand.BOBAI_CORE
    val isDark = darkOverride ?: isSystemInDarkTheme()

    CompositionLocalProvider(
        LocalIndustrialBrand provides brand,
        LocalBrandSetter provides { brandName = it.name },
        LocalDarkThemeOverride provides darkOverride,
        LocalDarkThemeSetter provides { darkOverride = it },
    ) {
        MaterialTheme(
            colorScheme = buildColorScheme(brand, isDark),
            shapes = IndustrialShapes,
            typography = IndustrialTypography,
            content = content,
        )
    }
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
    val fg = toneColor(tone)
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.5.dp, fg.copy(alpha = 0.75f)),
        color = fg.copy(alpha = 0.12f),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Monospace technical tag for part numbers, SKU codes, revisions, and serials. */
@Composable
fun TechnicalBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
fun MockBanner(modifier: Modifier = Modifier) {
    val brand = LocalIndustrialBrand.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Top tactical brand & tenant telemetry bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            brand.badgeCode,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        brand.displayName.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    brand.divisionTag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // High-visibility OSHA caution strip for Mock Data disclosure
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Text(
                    "MOCK DATA — fictional equipment and documents for UI testing. Not for field use.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
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
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        FlowRow(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(glasses.label, if (glasses.ok) Tone.OK else Tone.WARN)
            StatusChip(mode.mode.label, mode.tone())
            StatusChip("Camera off · Mic off", Tone.NEUTRAL)
        }
    }
}

/**
 * Ruggedized industrial bezel card with left accent bar and uppercase technical header.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accentTone: Tone? = null,
    trailingHeader: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val barColor = when (accentTone) {
        Tone.OK -> MaterialTheme.colorScheme.secondary
        Tone.WARN -> MaterialTheme.colorScheme.tertiary
        Tone.BAD -> MaterialTheme.colorScheme.error
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            // Rugged Header Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (trailingHeader != null) {
                    // Share the row with the title so neither squeezes the other
                    // into a sliver (titles broke mid-word on the unfolded panes).
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f, fill = false)) { trailingHeader() }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

/** Glove-ready high-visibility primary button (56dp min height, bold industrial label). */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** Glove-ready industrial secondary button (48dp min height, reinforced border). */
@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.5.dp,
            if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Quick Enterprise Partner Skin Switcher strip for demos and field customization. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandProfileSelector(modifier: Modifier = Modifier) {
    val currentBrand = LocalIndustrialBrand.current
    val setBrand = LocalBrandSetter.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IndustrialBrand.entries.forEach { b ->
            val selected = b == currentBrand
            FilterChip(
                selected = selected,
                onClick = { setBrand(b) },
                label = {
                    Text(
                        "${b.badgeCode} · ${b.displayName}",
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    selectedBorderWidth = 1.5.dp,
                ),
            )
        }
    }
}

object Routes {
    const val HOME = "home"
    const val JOBS = "jobs"
    const val OFFICE = "office"
    const val NOTES = "notes"
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

internal enum class TopTab(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home),
    JOBS(Routes.JOBS, "Jobs", Icons.Filled.Work),
    OFFICE(Routes.OFFICE, "Office", Icons.Filled.Business),
    NOTES(Routes.NOTES, "Notes", Icons.Filled.Edit),
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
    // One Office WebView for the whole BobAI UI, so switching tabs keeps the page and sign-in.
    val officeHost = remember { OfficeWebHost() }
    androidx.compose.runtime.DisposableEffect(officeHost) { onDispose { officeHost.destroy() } }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        CompositionLocalProvider(LocalExpanded provides expanded) {
            val isTop = TopTab.entries.any { it.route == route }
            Row(Modifier.fillMaxSize()) {
                if (expanded && route != Routes.LIVE) {
                    NavigationRail(
                        modifier = Modifier.statusBarsPadding(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        TopTab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = route == t.route,
                                onClick = { nav.goTop(t.route) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label, fontWeight = FontWeight.Bold) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                ),
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
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 4.dp,
                                ) {
                                    TopTab.entries.forEach { t ->
                                        NavigationBarItem(
                                            selected = route == t.route,
                                            onClick = { nav.goTop(t.route) },
                                            icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                                            // Six tabs on the ~360dp Fold cover screen: keep each label on one
                                            // line at a slightly smaller, untracked label size.
                                            label = {
                                                Text(
                                                    t.label,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Clip,
                                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                                        .copy(fontSize = 10.sp, letterSpacing = 0.sp),
                                                )
                                            },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primary,
                                            ),
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
                            composable(Routes.OFFICE) { OfficeScreen(officeHost) }
                            composable(Routes.NOTES) { NotesScreen(vm) }
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
    Surface(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "LIVE WORK ORDER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Button(
                onClick = onOpen,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimary,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Open session", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Standard screen header with optional back button and industrial divider. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing()
        }
    }
}
