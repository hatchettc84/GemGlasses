package com.lpecom.gemglasses.bobai.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.data.MockContent
import com.lpecom.gemglasses.bobai.live.MediaStatus
import com.lpecom.gemglasses.bobai.live.VoiceStatus
import com.lpecom.gemglasses.bobai.workmode.ActivationTrigger
import com.lpecom.gemglasses.bobai.workmode.WorkModeState
import com.lpecom.gemglasses.bobai.workmode.WorkPhase
import com.lpecom.gemglasses.bobai.data.isMockPhoto
import com.lpecom.gemglasses.bobai.domain.ApprovalStatus
import com.lpecom.gemglasses.bobai.domain.DocAvailability
import com.lpecom.gemglasses.bobai.domain.EquipmentResolver
import com.lpecom.gemglasses.bobai.domain.KnowledgePack
import com.lpecom.gemglasses.bobai.domain.NodeKind
import com.lpecom.gemglasses.bobai.domain.Observation
import com.lpecom.gemglasses.bobai.domain.ObservationKind
import com.lpecom.gemglasses.bobai.domain.PackState
import com.lpecom.gemglasses.bobai.domain.Session
import com.lpecom.gemglasses.bobai.domain.SessionStatus
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.bobai.domain.StepResult
import com.lpecom.gemglasses.bobai.domain.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun fmtDate(t: Long?): String = t?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—"
private fun mb(bytes: Long) = "${bytes / 1_000_000} MB"

@Composable
private fun ScrollPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** Two equal panes with a gap in the middle so nothing sits on the Fold's central hinge. */
@Composable
private fun SplitPanes(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { left() }
        Spacer(Modifier.width(HINGE_GAP))
        Box(Modifier.weight(1f)) { right() }
    }
}

private val HINGE_GAP = 24.dp

// =========================================================================
// Home — Field Dispatch & Industrial Command Console
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val glasses by vm.glasses.collectAsStateWithLifecycle()
    val brand = LocalIndustrialBrand.current
    val active = vm.controller.activeSession()
    val activeJob = active?.let { vm.controller.job(it.jobId) }
    val now = System.currentTimeMillis()
    val usablePacks = snapshot.packs.count { it.isUsable(now) }

    fun start(then: String? = null) {
        val s = vm.controller.startOrResume()
        nav.navigate(if (s.equipment == null) Routes.EQUIPMENT else then ?: Routes.ASSISTANT)
    }

    val ready: @Composable () -> Unit = {
        SectionCard(
            title = "Ready for a service call?",
            trailingHeader = {
                TechnicalBadge("OPS", if (active == null) "STANDBY" else "ACTIVE")
            },
        ) {
            PrimaryAction(if (active == null) "Start assistant" else "Resume current job", { start() })
            if (active == null) {
                Text(
                    "Starts an unassigned session. Camera and microphone stay off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkModeCard(vm, onStartAssistance = { start() })
        SectionCard(
            title = "Current job",
            accentTone = if (active != null) Tone.OK else Tone.NEUTRAL,
            trailingHeader = {
                if (activeJob != null) {
                    TechnicalBadge("WO#", activeJob.id.takeLast(8).uppercase())
                }
            },
        ) {
            if (active == null) {
                Text("No active job.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    activeJob?.site ?: "Unassigned session",
                    style = MaterialTheme.typography.titleLarge,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TechnicalBadge("ASSET", active.equipment?.model ?: "UNCONFIRMED")
                    active.equipment?.serial?.takeIf { it.isNotBlank() }?.let { sn ->
                        TechnicalBadge("S/N", sn)
                    }
                    StatusChip(
                        "Equipment: " + (active.equipment?.let { "${it.model} — confirmed" } ?: "Not confirmed"),
                        if (active.equipment?.isConfirmed == true) Tone.OK else Tone.WARN,
                    )
                }
                SecondaryAction("Open job", { nav.navigate(Routes.job(active.jobId)) })
            }
        }
        SectionCard(
            title = "Enterprise OEM / MRO Partner Profile",
            trailingHeader = { TechnicalBadge("SKIN", brand.badgeCode) },
        ) {
            Text(
                "Switch industrial visual profile (Grainger MRO, DeWalt SiteManager, Milwaukee ONE-KEY, or BobAI Core):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrandProfileSelector()
        }
    }
    val tools: @Composable () -> Unit = {
        SectionCard(
            title = "Quick actions",
            trailingHeader = { TechnicalBadge("FIELD", "4 TOOLS") },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryAction("Identify", { vm.controller.startOrResume(); nav.navigate(Routes.EQUIPMENT) })
                SecondaryAction("Troubleshoot", { start() })
                SecondaryAction("Find manual", { nav.goTop(Routes.LIBRARY) })
                SecondaryAction("Capture note", { start(Routes.ASSISTANT) })
            }
        }
        SectionCard(
            title = "Offline readiness",
            accentTone = if (usablePacks > 0) Tone.OK else Tone.BAD,
            trailingHeader = { TechnicalBadge("CACHE", "$usablePacks/${snapshot.packs.size} PACKS") },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(
                    if (usablePacks > 0) "References ready ($usablePacks pack${if (usablePacks == 1) "" else "s"})" else "No usable references",
                    if (usablePacks > 0) Tone.OK else Tone.BAD,
                )
                val off by vm.offline.state.collectAsStateWithLifecycle()
                StatusChip(
                    off.status.label,
                    when (off.status) {
                        com.lpecom.gemglasses.bobai.offline.ModelStatus.READY -> Tone.OK
                        com.lpecom.gemglasses.bobai.offline.ModelStatus.FAILED,
                        com.lpecom.gemglasses.bobai.offline.ModelStatus.SELF_TEST_FAILED -> Tone.BAD
                        else -> Tone.NEUTRAL
                    },
                )
            }
            mode.unavailable.forEach {
                Text(
                    "• Unavailable: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SecondaryAction("Manage downloads", { nav.navigate(Routes.DOWNLOADS) })
        }
    }

    ScrollPage {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("BobAI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    "${brand.displayName} · Industrial Field Console",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TechnicalBadge("TENANT", MockContent.TENANT.uppercase())
        }
        StatusStrip(glasses, mode)
        if (LocalExpanded.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(HINGE_GAP)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { ready() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { tools() }
            }
        } else {
            ready()
            tools()
        }
    }
}

// =========================================================================
// Equipment confirmation — Industrial Nameplate & Rating Plate Verification
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EquipmentScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    snapshot.activeSessionId
    val session = vm.controller.activeSession()
    var smudged by rememberSaveable { mutableStateOf(false) }
    val label = if (smudged) MockContent.MOCK_AMBIGUOUS_LABEL_TEXT else MockContent.MOCK_LABEL_TEXT
    val candidates = vm.controller.candidates(label)
    val ambiguous = vm.controller.resolver.isAmbiguous(candidates)
    var model by rememberSaveable { mutableStateOf(session?.equipment?.model ?: "") }
    var serial by rememberSaveable { mutableStateOf(session?.equipment?.serial ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Confirm equipment", onBack = { nav.popBackStack() }) {
            TechnicalBadge("STEP 1", "ASSET VERIFY")
            Spacer(Modifier.width(8.dp))
        }
        ScrollPage {
            if (session == null) {
                Text("No active session.")
                PrimaryAction("Start a session", { vm.controller.startOrResume() })
                return@ScrollPage
            }
            SectionCard(
                title = "Label photo (mock)",
                trailingHeader = { TechnicalBadge("OCR", "SIMULATED") },
            ) {
                // Machined Rating Plate Visual Simulator
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "EQUIPMENT RATING PLATE · STAMPED METAL",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "● REV-SPEC",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        Text(
                            label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
                Text(
                    "No camera was used. Real label capture and OCR are not implemented yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = smudged, role = Role.Switch, onValueChange = { smudged = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = smudged, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Simulate a hard-to-read label", fontWeight = FontWeight.Medium)
                }
            }
            SectionCard(
                title = "Extracted candidates",
                accentTone = if (ambiguous) Tone.WARN else Tone.OK,
                trailingHeader = { TechnicalBadge("MATCHES", "${candidates.size}") },
            ) {
                if (ambiguous) {
                    StatusChip("Ambiguous model — choose or type the exact model", Tone.WARN)
                }
                candidates.forEach { c ->
                    OutlinedButton(
                        onClick = { model = c.model; serial = c.serial ?: serial; error = null },
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    ) {
                        Text(
                            "${c.model}  ·  ${c.source}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Text(
                    "Selecting a candidate only fills the fields. Nothing is confirmed until you tap Confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(
                title = "Model and serial",
                trailingHeader = { TechnicalBadge("GATE", "HUMAN CONFIRM") },
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; error = null },
                    label = { Text("Model number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serial,
                    onValueChange = { serial = it },
                    label = { Text("Serial number (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (model.isNotBlank()) {
                    val known = vm.controller.resolver.isKnownModel(model)
                    val normalized = vm.controller.resolver.normalizeModel(model)
                    StatusChip(
                        if (known) "Known model: ${MockContent.knownModels[normalized]}"
                        else "Unknown model — no approved manual. Reference-only or escalate.",
                        if (known) Tone.OK else Tone.WARN,
                    )
                    if (known) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TechnicalBadge("OEM", MockContent.knownModels[normalized] ?: "—")
                            TechnicalBadge("SKU/CAT", "MRO-${normalized}")
                            TechnicalBadge("VOLTAGE", "120V 60Hz")
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                PrimaryAction(
                    "Confirm this is the equipment",
                    {
                        when (val r = vm.controller.confirmEquipment(model, serial)) {
                            is EquipmentResolver.Confirmation.Confirmed -> {
                                nav.navigate(Routes.ASSISTANT) { popUpTo(Routes.EQUIPMENT) { inclusive = true } }
                            }
                            is EquipmentResolver.Confirmation.Rejected -> error = r.reason
                        }
                    },
                    enabled = model.isNotBlank(),
                )
            }
        }
    }
}

// =========================================================================
// Assistant — Dual-Pane Tactical Troubleshooting & LOTO Procedure Workstation
// =========================================================================

@Composable
fun AssistantScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    snapshot.sessions
    val session = vm.controller.activeSession()
    // Hoisted above the narrow/expanded branch so scroll position survives fold/unfold.
    val listState = rememberLazyListState()

    if (session == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Assistant", onBack = { nav.popBackStack() })
            ScrollPage {
                Text("No active session.")
                PrimaryAction("Start assistant", { vm.controller.startOrResume(); nav.navigate(Routes.EQUIPMENT) })
            }
        }
        return
    }

    val endSession = {
        val ended = vm.controller.endSession()
        if (ended != null) nav.navigate(Routes.summary(ended.id)) { popUpTo(Routes.HOME) }
    }
    val paused = session.status == SessionStatus.PAUSED

    Column(Modifier.fillMaxSize()) {
        val expandedLayout = LocalExpanded.current
        ScreenHeader("Assistant", onBack = { nav.popBackStack() }) {
            if (expandedLayout) {
                session.equipment?.let {
                    TechnicalBadge("MODEL", it.model)
                    Spacer(Modifier.width(8.dp))
                }
                StatusChip(mode.mode.label, mode.tone())
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = { vm.controller.setPaused(!paused) }) {
                Text(if (paused) "Resume" else "Pause", fontWeight = FontWeight.ExtraBold)
            }
        }
        if (!expandedLayout) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(mode.mode.label, mode.tone())
                session.equipment?.let { StatusChip(it.model + " confirmed", Tone.OK) }
            }
        }
        if (vm.capturing) {
            Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Glasses camera on — capturing one photo. It turns off when the photo is saved.",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (paused) {
            Surface(color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Guidance paused. Nothing will advance until you resume.",
                    color = MaterialTheme.colorScheme.onTertiary,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (LocalExpanded.current) {
            Box(Modifier.weight(1f)) {
                SplitPanes(
                    left = { WorkflowPane(vm, nav, session, listState, expanded = true) },
                    right = { ReferencePane(vm, session) },
                )
            }
            // Bottom actions grouped away from the hinge (Fold7 ergonomic split).
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryAction(if (paused) "Resume" else "Pause", { vm.controller.setPaused(!paused) })
                        SecondaryAction(captureLabel(vm), { vm.capture() }, enabled = !vm.capturing)
                    }
                    Spacer(Modifier.width(HINGE_GAP))
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        SecondaryAction("Save draft", {
                            vm.controller.addNote(vm.draftInput); vm.draftInput = ""; vm.message = "Saved on this device."
                        })
                        Button(
                            onClick = endSession,
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("End session", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) { WorkflowPane(vm, nav, session, listState, expanded = false) }
            ControlBar(vm, session, onEnd = endSession)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkflowPane(
    vm: BobAiViewModel,
    nav: NavHostController,
    session: Session,
    listState: LazyListState,
    expanded: Boolean,
) {
    val c = vm.controller
    val procedure = c.procedureFor(session)
    val engine = c.engine(session)
    val ps = session.procedureState

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BobVoiceCard(vm) }
        item { OfflineAskCard(vm) }
        item {
            SectionCard(
                title = "Equipment",
                accentTone = if (session.equipment?.isConfirmed == true) Tone.OK else Tone.WARN,
                trailingHeader = {
                    session.equipment?.serial?.takeIf { it.isNotBlank() }?.let {
                        TechnicalBadge("S/N", it)
                    }
                },
            ) {
                val eq = session.equipment
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        eq?.model ?: "Not confirmed",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        if (eq?.isConfirmed == true) "Confirmed" else "Not confirmed",
                        if (eq?.isConfirmed == true) Tone.OK else Tone.WARN,
                    )
                }
                eq?.manufacturer?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SecondaryAction(
                    if (eq == null) "Confirm equipment" else "Correct equipment",
                    { nav.navigate(Routes.EQUIPMENT) },
                )
            }
        }
        if (!expanded) {
            item {
                SectionCard(
                    title = "Camera",
                    trailingHeader = { TechnicalBadge("MEDIA", "${session.mediaReferences.size} SAVED") },
                ) {
                    val last = session.mediaReferences.lastOrNull()
                    if (last == null) Text("No image captured. Camera is off unless you capture.")
                    else CapturedPhoto(vm, last)
                    SecondaryAction(captureLabel(vm), { vm.capture() }, enabled = !vm.capturing)
                }
            }
        }
        item {
            val stepTone = when {
                ps != null && engine != null && engine.prerequisitesMet(ps) -> when (engine.current(ps).kind) {
                    NodeKind.STOP -> Tone.BAD
                    NodeKind.COMPLETE -> Tone.OK
                    NodeKind.STEP -> Tone.OK
                }
                else -> Tone.WARN
            }
            SectionCard(
                title = "Current step",
                accentTone = stepTone,
                trailingHeader = {
                    if (ps != null && engine != null && engine.prerequisitesMet(ps)) {
                        TechnicalBadge("NODE", engine.current(ps).id.uppercase())
                    } else if (ps != null) {
                        TechnicalBadge("LOTO", "${ps.acknowledgedPrerequisites.size}/${procedure?.prerequisites?.size ?: 0}")
                    }
                },
            ) {
                when {
                    session.equipment == null -> Text("Confirm the exact model before guidance can start.")
                    procedure == null || engine == null -> {
                        StatusChip("No approved procedure for ${session.equipment.model}", Tone.WARN)
                        Text("You can still search approved manuals, ask a question (answers are quoted with sources), take notes, or stop and escalate.")
                    }
                    ps == null -> {
                        Text(procedure.title, style = MaterialTheme.typography.titleLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TechnicalBadge("REV", procedure.version)
                            TechnicalBadge("AUTH", procedure.requiredAuthorization)
                        }
                        Text("Version ${procedure.version} · Requires: ${procedure.requiredAuthorization}")
                        PrimaryAction("Start procedure", { c.startProcedure() })
                    }
                    !engine.prerequisitesMet(ps) -> {
                        // OSHA / Lockout-Tagout Safety Gate Box
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "OSHA / LOTO SAFETY PREREQUISITE GATE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    "Before you begin, confirm each prerequisite:",
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                        }
                        procedure.prerequisites.forEachIndexed { i, p ->
                            val checked = i in ps.acknowledgedPrerequisites
                            Surface(
                                color = if (checked) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(
                                    1.dp,
                                    if (checked) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clickable(role = Role.Checkbox) { c.acknowledge(i, !checked) }
                                    .semantics { stateDescription = if (checked) "Confirmed" else "Not confirmed" },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Spacer(Modifier.width(10.dp))
                                    Text(p, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    else -> {
                        val node = engine.current(ps)
                        // Breadcrumb path trail
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TechnicalBadge("PROGRESS", engine.stepLabel(ps))
                            TechnicalBadge("PATH", ps.path.joinToString(" → ").uppercase())
                        }
                        if (node.kind == NodeKind.STOP) StatusChip("Stop — escalation required", Tone.BAD)
                        if (node.kind == NodeKind.COMPLETE) StatusChip("Procedure complete", Tone.OK)
                        Text(node.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                node.instruction,
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                        node.citation?.let { cit ->
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Source: ${cit.label()}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        vm.openReference(cit.documentId, cit.page)
                                        if (!expanded) nav.navigate(Routes.doc(cit.documentId, cit.page))
                                    }) {
                                        Text("Open", fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                        if (node.kind == NodeKind.STEP) {
                            Text("BobAI: What result did you observe?", fontWeight = FontWeight.ExtraBold)
                            node.options.forEach { o ->
                                Button(
                                    onClick = {
                                        (c.chooseOption(o.id) as? StepResult.NotUnderstood)?.let { vm.message = it.reason }
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                    enabled = !ps.paused,
                                ) {
                                    Text(o.label, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    c.notSure()
                                    vm.message = "Not advancing. Re-check, open the source, or stop and escalate."
                                },
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) {
                                Text("I am not sure", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (ps.path.size > 1) {
                            TextButton(onClick = { c.back() }) {
                                Text("Previous step", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Ask or record",
                trailingHeader = { TechnicalBadge("EVIDENCE", "EXACT CITATION") },
            ) {
                OutlinedTextField(
                    value = vm.draftInput,
                    onValueChange = { vm.draftInput = it },
                    label = { Text("Type a question, a result, or a note") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryAction(
                        "Ask sources",
                        { c.ask(vm.draftInput); vm.draftInput = "" },
                        enabled = vm.draftInput.isNotBlank(),
                    )
                    SecondaryAction(
                        "Report result",
                        {
                            when (val r = c.interpret(vm.draftInput)) {
                                is StepResult.Moved -> vm.draftInput = ""
                                is StepResult.NotUnderstood -> vm.message = r.reason
                            }
                        },
                        enabled = vm.draftInput.isNotBlank() && ps != null,
                    )
                    SecondaryAction(
                        "Save note",
                        { c.addNote(vm.draftInput); vm.draftInput = "" },
                        enabled = vm.draftInput.isNotBlank(),
                    )
                }
                Text(
                    "Answers quote approved sources only. Typed results advance only on an exact match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Session timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                TechnicalBadge("EVENTS", "${session.observations.size}")
            }
        }
        items(session.observations.asReversed()) { o ->
            TimelineItem(o) { cit ->
                vm.openReference(cit.documentId, cit.page)
                if (!expanded) nav.navigate(Routes.doc(cit.documentId, cit.page))
            }
        }
    }
}

@Composable
private fun TimelineItem(
    o: Observation,
    onCitation: (com.lpecom.gemglasses.bobai.domain.EvidenceCitation) -> Unit,
) {
    val (who, badge, tone) = when (o.kind) {
        ObservationKind.TECHNICIAN_REPORT -> Triple("You (technician report)", "TECH", Tone.NEUTRAL)
        ObservationKind.VISUAL_CAPTURE -> Triple("Photo", "OPTIC", Tone.WARN)
        ObservationKind.SOURCE_STATEMENT -> Triple("Approved source (quoted)", "SPEC", Tone.OK)
        ObservationKind.SYSTEM -> Triple("BobAI (procedure)", "SYS", Tone.NEUTRAL)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (o.kind == ObservationKind.TECHNICIAN_REPORT)
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, toneColor(tone).copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(who, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                TechnicalBadge("LOG", badge)
            }
            Text(o.text, style = MaterialTheme.typography.bodyMedium)
            o.citation?.let { cit ->
                TextButton(onClick = { onCitation(cit) }) {
                    Text("Source: ${cit.label()}", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun ControlBar(vm: BobAiViewModel, session: Session, onEnd: () -> Unit) {
    val c = vm.controller
    Surface(
        tonalElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mod = Modifier.weight(1f).heightIn(min = 52.dp)
            val pad = PaddingValues(horizontal = 4.dp)
            OutlinedButton(
                onClick = {
                    vm.message = "Voice control isn't connected to BobAI workflows yet. Type below, or use Settings → Live voice for the existing assistant."
                },
                shape = MaterialTheme.shapes.small,
                modifier = mod,
                contentPadding = pad,
            ) { Text("Speak", maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = {
                    val e = c.engine(session); val ps = session.procedureState
                    vm.message = if (e != null && ps != null) e.current(ps).instruction else "No current step to repeat."
                },
                shape = MaterialTheme.shapes.small,
                modifier = mod,
                contentPadding = pad,
            ) { Text("Repeat", maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { vm.capture() },
                enabled = !vm.capturing,
                shape = MaterialTheme.shapes.small,
                modifier = mod,
                contentPadding = pad,
            ) {
                Text(
                    if (vm.capturing) "Capturing…" else "Capture",
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = onEnd,
                shape = MaterialTheme.shapes.small,
                modifier = mod,
                contentPadding = pad,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("End", maxLines = 1, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun ReferencePane(vm: BobAiViewModel, session: Session) {
    val c = vm.controller
    // Default the reference to the current step's citation.
    val stepCitation = c.engine(session)?.let { e -> session.procedureState?.let { e.current(it).citation } }
    val docId = vm.referenceDocId ?: stepCitation?.documentId
    val page = vm.referencePage ?: stepCitation?.page
    Column(Modifier.fillMaxSize().padding(top = 16.dp, end = 16.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TabRow(
                selectedTabIndex = vm.referenceTab,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                listOf("Manual", "Photos", "Notes").forEachIndexed { i, t ->
                    Tab(
                        selected = vm.referenceTab == i,
                        onClick = { vm.referenceTab = i },
                        text = { Text(t, fontWeight = FontWeight.ExtraBold) },
                    )
                }
            }
        }
        when (vm.referenceTab) {
            0 -> {
                val doc = docId?.let { c.knowledge.document(it) }
                if (doc == null) {
                    ScrollPage { Text("No source selected. Open a source from the current step or the timeline.") }
                } else {
                    DocumentBody(vm, doc, page, onPage = { vm.referencePage = it }, returnToStep = null)
                }
            }
            1 -> ScrollPage {
                if (session.mediaReferences.isEmpty()) Text("No photos in this session. Capture requires an explicit tap.")
                session.mediaReferences.asReversed().forEach { CapturedPhoto(vm, it) }
            }
            else -> ScrollPage {
                val notes = session.observations.filter { it.kind == ObservationKind.TECHNICIAN_REPORT }
                if (notes.isEmpty()) Text("No technician notes yet.")
                notes.forEach {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("• ${it.text}", modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

// =========================================================================
// Library + Schematic Blueprint Document Viewer
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    snapshot.packs
    val c = vm.controller
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    val equipment = c.activeSession()?.equipment
    val applicable = equipment?.let { c.knowledge.applicableDocuments(it).map { d -> d.id }.toSet() } ?: emptySet()
    val terms = com.lpecom.gemglasses.bobai.domain.KnowledgeRepository.tokenize(query)
    val docs = c.knowledge.allDocuments().filter { d ->
        when (filter) {
            1 -> d.id in applicable
            2 -> c.knowledge.availability(d).readable
            else -> true
        }
    }.filter { d ->
        terms.isEmpty() || terms.any { t -> (d.title + " " + d.pages.joinToString(" ") { it.heading + " " + it.body }).lowercase().contains(t) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Library") {
            TechnicalBadge("DOCS", "${docs.size}/${c.knowledge.allDocuments().size}")
            Spacer(Modifier.width(8.dp))
        }
        ScrollPage {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search manuals") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("All", "Applies to ${equipment?.model ?: "current equipment"}", "Downloaded").forEachIndexed { i, l ->
                    FilterChip(
                        selected = filter == i,
                        onClick = { filter = i },
                        label = { Text(l, fontWeight = FontWeight.Bold) },
                        enabled = i != 1 || equipment != null,
                    )
                }
            }
            if (docs.isEmpty()) Text("No documents match. Try another search, or check Downloads.")
            docs.forEach { d -> DocCard(vm, d) { nav.navigate(Routes.doc(d.id)) } }
            SecondaryAction("Manage downloads", { nav.navigate(Routes.DOWNLOADS) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocCard(vm: BobAiViewModel, d: SourceDocument, onOpen: () -> Unit) {
    val avail = vm.controller.knowledge.availability(d)
    val tone = when (d.approvalStatus) {
        ApprovalStatus.APPROVED -> Tone.OK
        ApprovalStatus.DRAFT -> Tone.WARN
        ApprovalStatus.REVOKED -> Tone.BAD
    }
    SectionCard(
        title = d.title,
        accentTone = tone,
        trailingHeader = { TechnicalBadge("REV", d.revision) },
    ) {
        Text("Revision ${d.revision} · Applies to: ${d.applicableModels.joinToString()}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (d.approvalStatus) {
                ApprovalStatus.APPROVED -> StatusChip("Approved", Tone.OK)
                ApprovalStatus.DRAFT -> StatusChip("Draft — unreviewed, not used for guidance", Tone.WARN)
                ApprovalStatus.REVOKED -> StatusChip("Revoked — not used for guidance", Tone.BAD)
            }
            StatusChip(
                avail.label,
                when (avail) {
                    DocAvailability.LOCAL -> Tone.OK
                    DocAvailability.STALE -> Tone.WARN
                    else -> Tone.BAD
                },
            )
            TechnicalBadge("PAGES", "${d.pages.size}")
        }
        SecondaryAction(if (avail.readable) "Open" else "View details", onOpen)
    }
}

@Composable
fun DocumentScreen(vm: BobAiViewModel, nav: NavHostController, docId: String, initialPage: Int?) {
    val doc = vm.controller.knowledge.document(docId)
    var page by rememberSaveable { mutableStateOf(initialPage) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(doc?.title ?: "Document", onBack = { nav.popBackStack() }) {
            doc?.let {
                TechnicalBadge("REV", it.revision)
                Spacer(Modifier.width(8.dp))
            }
        }
        if (doc == null) {
            ScrollPage { Text("This document no longer exists in the library.") }
        } else {
            val back = if (vm.controller.activeSession() != null) ({ nav.navigate(Routes.ASSISTANT) { popUpTo(Routes.ASSISTANT) { inclusive = true } } }) else null
            DocumentBody(vm, doc, page, onPage = { page = it }, returnToStep = back)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentBody(
    vm: BobAiViewModel,
    doc: SourceDocument,
    page: Int?,
    onPage: (Int) -> Unit,
    returnToStep: (() -> Unit)?,
) {
    val avail = vm.controller.knowledge.availability(doc)
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    ScrollPage {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TechnicalBadge("DOC-ID", doc.id.uppercase())
            TechnicalBadge("REV", doc.revision)
            TechnicalBadge("MODELS", doc.applicableModels.joinToString())
        }
        Text(
            "Revision ${doc.revision} · ${doc.approvalStatus.name.lowercase()} · Applies to ${doc.applicableModels.joinToString()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (doc.approvalStatus != ApprovalStatus.APPROVED) {
            StatusChip("Not approved — reference only, never used for guidance", Tone.BAD)
        }
        if (!avail.readable) {
            StatusChip(avail.label, Tone.BAD)
            Text("This document isn't available on the device. Open Downloads and download or repair its pack.")
            return@ScrollPage
        }
        if (avail == DocAvailability.STALE) {
            StatusChip("Pack expired — refresh before relying on this content", Tone.WARN)
        }
        val pages = doc.pages
        val idx = pages.indexOfFirst { it.number == page }.takeIf { it >= 0 } ?: 0
        val p = pages[idx]
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onPage(pages[idx - 1].number) }, enabled = idx > 0) {
                    Text("◀ Prev", fontWeight = FontWeight.ExtraBold)
                }
                Text(
                    "Page ${p.number}",
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                TextButton(onClick = { onPage(pages[idx + 1].number) }, enabled = idx < pages.size - 1) {
                    Text("Next ▶", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Zoom", fontWeight = FontWeight.Bold)
            Slider(zoom, { zoom = it }, valueRange = 1f..2f, modifier = Modifier.weight(1f))
            TechnicalBadge("SCALE", "${(zoom * 100).toInt()}%")
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SECTION · PAGE ${p.number}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "REV ${doc.revision} SPEC",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(p.heading, fontWeight = FontWeight.ExtraBold, fontSize = (20 * zoom).sp)
                Text(p.body, fontSize = (16 * zoom).sp, lineHeight = (24 * zoom).sp)
                // Technical Blueprint Schematic Box
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "CAD / SCHEMATIC FIGURE ${p.number}.1 — TERMINAL J3 & CIRCUIT BLOCK",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "[Original page image / diagram placeholder — mock]",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        returnToStep?.let { PrimaryAction("Return to current step", it) }
    }
}

// =========================================================================
// Jobs, Work Order Detail, & Compliance Sign-Off Summary Review
// =========================================================================

private fun SyncState.label() = when (this) {
    SyncState.SAVED_LOCALLY -> "Saved locally"
    SyncState.SYNC_PENDING -> "Sync pending"
    SyncState.SYNCED -> "Synced"
    SyncState.CONFLICT -> "Sync conflict"
    SyncState.SYNC_FAILED -> "Sync failed"
}

@Composable
fun JobsScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Jobs") {
            TechnicalBadge("TOTAL", "${snapshot.jobs.size} WO")
            Spacer(Modifier.width(8.dp))
        }
        ScrollPage {
            Text(
                "Jobs are stored on this device only. Sync is not implemented yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.jobs.isEmpty()) Text("No jobs yet. Start the assistant from Home to create one.")
            snapshot.jobs.sortedByDescending { it.updatedAt }.forEach { j ->
                val done = j.status.name == "COMPLETED"
                SectionCard(
                    title = j.site,
                    accentTone = if (done) Tone.OK else Tone.WARN,
                    trailingHeader = { TechnicalBadge("WO#", j.id.takeLast(8).uppercase()) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            j.status.name.lowercase().replace('_', ' '),
                            if (done) Tone.OK else Tone.NEUTRAL,
                        )
                        StatusChip(j.syncState.label(), Tone.NEUTRAL)
                    }
                    Text("Updated ${fmtDate(j.updatedAt)} · ${snapshot.sessions.count { it.jobId == j.id }} session(s)")
                    SecondaryAction("Open job", { nav.navigate(Routes.job(j.id)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobDetailScreen(vm: BobAiViewModel, nav: NavHostController, jobId: String) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val job = snapshot.jobs.firstOrNull { it.id == jobId }
    val sessions = snapshot.sessions.filter { it.jobId == jobId }.sortedByDescending { it.updatedAt }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(job?.site ?: "Job", onBack = { nav.popBackStack() }) {
            job?.let {
                TechnicalBadge("WO#", it.id.takeLast(8).uppercase())
                Spacer(Modifier.width(8.dp))
            }
        }
        ScrollPage {
            if (job == null) {
                Text("Job not found.")
                return@ScrollPage
            }
            val eq = sessions.firstNotNullOfOrNull { it.equipment }
            SectionCard(
                title = "Job",
                accentTone = if (job.status.name == "COMPLETED") Tone.OK else Tone.WARN,
                trailingHeader = { TechnicalBadge("TECH", job.assignedUser.uppercase()) },
            ) {
                Text("Status: ${job.status.name.lowercase().replace('_', ' ')} · ${job.syncState.label()}")
                Text("Equipment: " + (eq?.let { "${it.model} (S/N ${it.serial.ifBlank { "n/a" }})" } ?: "Not confirmed"))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (job.isMock) StatusChip("Mock job", Tone.WARN)
                    eq?.let { TechnicalBadge("MODEL", it.model) }
                }
                val active = vm.controller.activeSession()
                if (active?.jobId == job.id) {
                    PrimaryAction("Resume session", { nav.navigate(if (active.equipment == null) Routes.EQUIPMENT else Routes.ASSISTANT) })
                } else if (active == null && job.status.name != "COMPLETED") {
                    PrimaryAction("Start session for this job", {
                        vm.controller.startOrResume(job.id); nav.navigate(Routes.EQUIPMENT)
                    })
                }
            }
            if (sessions.isEmpty()) Text("No sessions yet.")
            sessions.forEach { s ->
                SectionCard(
                    title = "Session · ${fmtDate(s.createdAt)}",
                    accentTone = if (s.summaryReviewed) Tone.OK else Tone.NEUTRAL,
                    trailingHeader = { TechnicalBadge("STATE", s.status.name) },
                ) {
                    Text("${s.status.name.lowercase()} · ${s.observations.size} timeline entries · ${s.mediaReferences.size} photo(s)")
                    s.observations.takeLast(4).forEach {
                        Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                    if (s.status == SessionStatus.ENDED) {
                        SecondaryAction(
                            if (s.summaryReviewed) "View reviewed summary" else "Review job summary",
                            { nav.navigate(Routes.summary(s.id)) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen(vm: BobAiViewModel, nav: NavHostController, sessionId: String) {
    val session = vm.controller.session(sessionId)
    var text by rememberSaveable { mutableStateOf(session?.draftSummary ?: "") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Job summary review", onBack = { nav.popBackStack() }) {
            TechnicalBadge("AUDIT", "SIGN-OFF")
            Spacer(Modifier.width(8.dp))
        }
        ScrollPage {
            if (session == null) {
                Text("Session not found.")
                return@ScrollPage
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(
                    if (session.summaryReviewed) "Reviewed · saved locally" else "Draft · saved locally",
                    if (session.summaryReviewed) Tone.OK else Tone.WARN,
                )
                session.equipment?.let { TechnicalBadge("ASSET", it.model) }
                TechnicalBadge("CITATIONS", "${session.observations.mapNotNull { it.citation }.distinct().size}")
            }
            Text(
                "Edit anything that's wrong. Nothing is shared or synced from this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Summary") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction("Save draft", {
                    vm.controller.saveSummary(sessionId, text, reviewed = false)
                    vm.message = "Draft saved on this device."
                })
                Button(
                    onClick = {
                        vm.controller.saveSummary(sessionId, text, reviewed = true)
                        vm.message = "Summary marked reviewed and saved locally."
                        nav.goTop(Routes.JOBS)
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Mark reviewed & save", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// =========================================================================
// Downloads — Knowledge Pack Integrity & Storage Telemetry
// =========================================================================

@Composable
fun DownloadsScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val free = remember(snapshot) { StatFs(context.filesDir.path).availableBytes }
    val total = remember(snapshot) { StatFs(context.filesDir.path).totalBytes.coerceAtLeast(1L) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads", onBack = { nav.popBackStack() }) {
            TechnicalBadge("PACKS", "${snapshot.packs.size}")
            Spacer(Modifier.width(8.dp))
        }
        ScrollPage {
            SectionCard(
                title = "Device Storage Telemetry",
                trailingHeader = { TechnicalBadge("FREE", "${free / 1_000_000_000} GB") },
            ) {
                Text("Free space on device: ${free / 1_000_000_000} GB", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { ((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Text(
                    "Downloads here are simulated (no files are transferred). States and storage checks are real UI states.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot.packs.forEach { p -> PackCard(p, now, free, vm) { confirmDelete = p.id } }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove pack?") },
            text = { Text("Manuals in this pack won't be available offline until downloaded again.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.controller.setPackState(id, PackState.NOT_DOWNLOADED)
                    confirmDelete = null
                }) { Text("Remove", fontWeight = FontWeight.ExtraBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PackCard(p: KnowledgePack, now: Long, free: Long, vm: BobAiViewModel, onDelete: () -> Unit) {
    val c = vm.controller
    val (label, tone) = when {
        p.state == PackState.COMPLETE && p.isExpired(now) -> "Expired ${fmtDate(p.expiresAt)} — refresh required" to Tone.WARN
        p.state == PackState.COMPLETE -> "Downloaded · verified" to Tone.OK
        p.state == PackState.PENDING -> "Download pending" to Tone.NEUTRAL
        p.state == PackState.CORRUPTED -> "Corrupted — integrity check failed" to Tone.BAD
        p.state == PackState.INSUFFICIENT_STORAGE -> "Insufficient storage" to Tone.BAD
        else -> "Not downloaded" to Tone.NEUTRAL
    }
    SectionCard(
        title = p.name,
        accentTone = tone,
        trailingHeader = { TechnicalBadge("VER", p.version) },
    ) {
        Text("Version ${p.version} · ${mb(p.sizeBytes)} · ${p.documentIds.size} document(s)")
        StatusChip(label, tone)
        if (p.downloadedAt != null) {
            Text(
                "Last verified ${fmtDate(p.lastVerifiedAt)} · Expires ${fmtDate(p.expiresAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val download = {
            c.setPackState(p.id, if (p.sizeBytes > free) PackState.INSUFFICIENT_STORAGE else PackState.COMPLETE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (p.state) {
                PackState.COMPLETE -> {
                    if (p.isExpired(now)) SecondaryAction("Refresh", download)
                    else SecondaryAction("Verify", {
                        c.setPackState(p.id, PackState.COMPLETE)
                        vm.message = "Pack verified (simulated checksum)."
                    })
                    SecondaryAction("Remove", onDelete)
                }
                PackState.CORRUPTED -> {
                    SecondaryAction("Re-download", download)
                    SecondaryAction("Remove", onDelete)
                }
                PackState.INSUFFICIENT_STORAGE -> SecondaryAction("Retry after freeing space", download)
                else -> SecondaryAction("Download (simulated)", download)
            }
        }
    }
}

// =========================================================================
// Settings — Enterprise Brand Skinning, Hardware DAT & Field Diagnostics
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: BobAiViewModel, nav: NavHostController) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val glasses by vm.glasses.collectAsStateWithLifecycle()
    val config = LocalConfiguration.current
    val brand = LocalIndustrialBrand.current
    val darkOverride = LocalDarkThemeOverride.current
    val setDarkOverride = LocalDarkThemeSetter.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings") {
            TechnicalBadge("PROFILE", brand.badgeCode)
            Spacer(Modifier.width(8.dp))
        }
        ScrollPage {
            SectionCard(
                title = "Enterprise Brand & Field Display Mode",
                trailingHeader = { TechnicalBadge("THEME", if (darkOverride == false) "DAYLIGHT" else "TACTICAL DARK") },
            ) {
                Text(
                    "Select an industrial OEM / MRO distributor skin and lighting contrast profile:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BrandProfileSelector()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = darkOverride == true,
                        onClick = { setDarkOverride(true) },
                        label = { Text("Tactical Slate (Dark)", fontWeight = FontWeight.Bold) },
                    )
                    FilterChip(
                        selected = darkOverride == false,
                        onClick = { setDarkOverride(false) },
                        label = { Text("Daylight Shop Floor (Light)", fontWeight = FontWeight.Bold) },
                    )
                    FilterChip(
                        selected = darkOverride == null,
                        onClick = { setDarkOverride(null) },
                        label = { Text("Follow System", fontWeight = FontWeight.Bold) },
                    )
                }
            }
            WorkModeSettingsCard(vm)
            AssistanceModesCard(vm)
            KnowledgeSyncCard()
            OfficeConsoleCard(vm)
            SectionCard(
                title = "Hardware",
                accentTone = if (glasses.ok) Tone.OK else Tone.WARN,
                trailingHeader = { TechnicalBadge("DAT", if (BuildConfig.USE_REAL_GLASSES) "1.0.1" else "MOCK") },
            ) {
                StatusChip(glasses.label, if (glasses.ok) Tone.OK else Tone.WARN)
                Text(glasses.detail)
                Text("Glasses backend: ${if (BuildConfig.USE_REAL_GLASSES) "Meta DAT SDK" else "Mock (built with gemglasses.useRealGlasses=false)"}")
                if (BuildConfig.USE_REAL_GLASSES) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!glasses.ok) SecondaryAction("Register with Meta AI", { vm.registerGlasses() })
                        SecondaryAction("Allow glasses camera", { vm.requestGlassesCamera() })
                        if (glasses.ok) SecondaryAction("Test glasses camera", { vm.testGlassesCamera() })
                        if (glasses.ok) SecondaryAction("Unregister from Meta AI", { vm.unregisterGlasses() })
                    }
                }
                Text(
                    "Glasses camera: one photo per Capture tap, saved to the active job. Glasses audio: not wired yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionCard(
                title = "Assistant and inference",
                accentTone = mode.tone(),
                trailingHeader = { TechnicalBadge("ROUTER", mode.mode.name) },
            ) {
                StatusChip("Mode: ${mode.mode.label}", mode.tone())
                mode.available.forEach { Text("✓ $it", fontWeight = FontWeight.Medium) }
                mode.unavailable.forEach { Text("✕ $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Local model: none configured. Requires benchmarks on the exact phone (Phase 4).")
                Text("Offline speech / voices: none downloaded.")
            }
            SectionCard(
                title = "Live voice (existing GemGlasses)",
                trailingHeader = { TechnicalBadge("GEMINI", "LIVE WS") },
            ) {
                Text("The original Gemini Live voice assistant is unchanged. It needs internet and the configured backend.")
                SecondaryAction("Open Live voice", { nav.navigate(Routes.LIVE) })
            }
            SectionCard(
                title = "Privacy",
                trailingHeader = { TechnicalBadge("POLICY", "LOCAL-FIRST") },
            ) {
                Text("• Camera and microphone are never used in the background by BobAI.")
                Text("• Photos are captured only when you tap Capture.")
                Text("• Jobs, notes and summaries are stored only on this device. Nothing is shared without review.")
                Text("• Retention controls: not configured yet.")
            }
            SectionCard(
                title = "Accessibility",
                trailingHeader = { TechnicalBadge("WCAG", "AAA GLOVE") },
            ) {
                Text("Follows the system font size and dark theme. All status indicators include text and icons.")
            }
            SectionCard(
                title = "Diagnostics",
                trailingHeader = { TechnicalBadge("API", "${Build.VERSION.SDK_INT}") },
            ) {
                Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})", fontFamily = FontFamily.Monospace)
                Text("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", fontFamily = FontFamily.Monospace)
                Text("Window: ${config.screenWidthDp}×${config.screenHeightDp} dp · layout ${if (LocalExpanded.current) "expanded (two-pane)" else "compact (single-pane)"}", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun OfficeConsoleCard(vm: BobAiViewModel) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshPairing() }
    LaunchedEffect(pairing.paired) { if (pairing.paired) code = "" }
    SectionCard(
        title = "Office console",
        accentTone = if (pairing.paired) Tone.OK else Tone.WARN,
        trailingHeader = { TechnicalBadge("SYNC", if (pairing.paired) "PAIRED" else "NOT PAIRED") },
    ) {
        if (pairing.paired) {
            StatusChip("Paired to ${pairing.userName}", Tone.OK)
            Text(
                "This phone signs in to BobAI with its own key. Jobs and manuals will come from the office console.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryAction("Unpair this phone", { vm.unpairPhone() })
        } else {
            Text(
                "Ask your admin for a 6-digit pairing code from the BobAI console, then enter it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            SecondaryAction(
                if (pairing.busy) "Pairing…" else "Pair this phone",
                { vm.pairPhone(code) },
                enabled = !pairing.busy && code.length == 6,
            )
        }
        pairing.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }

private fun captureLabel(vm: BobAiViewModel): String = when {
    vm.capturing -> "Capturing…"
    BuildConfig.USE_REAL_GLASSES -> "Capture"
    else -> "Capture (mock)"
}

/** One job photo: the saved image, or a clear label for mock placeholders and missing files. */
@Composable
private fun CapturedPhoto(vm: BobAiViewModel, ref: String) {
    if (isMockPhoto(ref)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "• $ref (mock placeholder)",
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(10.dp),
            )
        }
        return
    }
    val image by produceState<Pair<Boolean, ImageBitmap?>>(false to null, ref) {
        value = true to withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(vm.captureFile(ref).path)?.asImageBitmap()
        }
    }
    val (loaded, bitmap) = image
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = "Photo captured from the glasses",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        )
        loaded -> Text("Photo file missing on this device: ${ref.substringAfterLast('/')}")
        else -> Text("Loading photo…")
    }
}


// ---- Addendum: Work Mode, "Hey Bob", assistance modes, knowledge sync -------

/**
 * Home card for Work Mode (addendum §8). Every value is real measured state;
 * "Hey Bob armed" only appears while a detector is running, which today it
 * never is.
 */
/** Gemini Live voice assistance, with an explicit, time-limited live view (addendum §5). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BobVoiceCard(vm: BobAiViewModel) {
    val v by vm.voice.state.collectAsStateWithLifecycle()
    val glasses by vm.glasses.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun micGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun startVoice() { if (micGranted()) vm.voice.start() }

    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startVoice() }

    SectionCard(
        title = "Talk to Bob",
        accentTone = when {
            !v.active -> Tone.NEUTRAL
            v.lastError != null -> Tone.WARN
            else -> Tone.OK
        },
        trailingHeader = { TechnicalBadge("AI", "GEMINI LIVE") },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(
                "Voice: ${v.status.label}",
                when (v.status) {
                    VoiceStatus.OFF -> Tone.NEUTRAL
                    VoiceStatus.CONNECTING, VoiceStatus.RECONNECTING, VoiceStatus.PAUSED -> Tone.WARN
                    else -> Tone.OK
                },
            )
            StatusChip(
                v.media.label + if (v.media == MediaStatus.CLOUD_TRANSMISSION) " (${v.framesSent} sent)" else "",
                if (v.media == MediaStatus.CAMERA_OFF) Tone.NEUTRAL else Tone.WARN,
            )
        }
        if (!vm.voice.configured) {
            Text(
                "This build has no token service (GEMGLASSES_BACKEND_URL), so Gemini Live can't connect.",
                style = MaterialTheme.typography.bodyMedium,
                color = toneColor(Tone.WARN),
            )
        }
        v.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = toneColor(Tone.WARN))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!v.active) {
                PrimaryAction("Start voice", {
                    if (micGranted()) startVoice() else permission.launch(android.Manifest.permission.RECORD_AUDIO)
                })
            } else {
                SecondaryAction("End voice", { vm.voice.stop() })
                if (v.status == VoiceStatus.PAUSED) {
                    SecondaryAction("Resume", { vm.voice.setPaused(false) })
                } else {
                    SecondaryAction("Pause", { vm.voice.setPaused(true) })
                }
                if (v.media == MediaStatus.CLOUD_TRANSMISSION) {
                    SecondaryAction("Stop camera", { vm.voice.stopLiveView() })
                } else {
                    SecondaryAction("Start live view", { vm.voice.startLiveView() }, enabled = glasses.ok)
                }
            }
        }
        v.transcript.takeLast(6).forEach { line ->
            Text(
                (if (line.fromTechnician) "You: " else "Bob: ") + line.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (line.fromTechnician) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            "While voice is on, your microphone audio goes to Google Gemini. Live view sends about one glasses " +
                "frame per second for up to 60 seconds. Nothing is recorded, and the conversation isn't saved " +
                "to the job. Bob only states facts from approved manuals; always verify with the procedure and " +
                "your meter.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Offline AI spike: asks the on-device model, which may only rephrase the
 * approved excerpt that manual search found. The label always says whether the
 * text is the model's wording or the manual quoted verbatim.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineAskCard(vm: BobAiViewModel) {
    val o by vm.offline.state.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    val ready = o.status == com.lpecom.gemglasses.bobai.offline.ModelStatus.READY

    SectionCard(
        title = "Ask Bob offline",
        accentTone = if (ready) Tone.OK else Tone.NEUTRAL,
        trailingHeader = { TechnicalBadge("ON-DEVICE", o.backend ?: "NO MODEL") },
    ) {
        StatusChip(
            o.status.label,
            when (o.status) {
                com.lpecom.gemglasses.bobai.offline.ModelStatus.READY -> Tone.OK
                com.lpecom.gemglasses.bobai.offline.ModelStatus.FAILED,
                com.lpecom.gemglasses.bobai.offline.ModelStatus.SELF_TEST_FAILED -> Tone.BAD
                else -> Tone.NEUTRAL
            },
        )
        o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (o.status) {
                com.lpecom.gemglasses.bobai.offline.ModelStatus.NO_MODEL ->
                    SecondaryAction("Check for model", { vm.offline.refreshPresence() })
                com.lpecom.gemglasses.bobai.offline.ModelStatus.LOADING -> Unit
                com.lpecom.gemglasses.bobai.offline.ModelStatus.READY -> Unit
                else -> SecondaryAction("Load model", { vm.loadOfflineModel() })
            }
        }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Question about this equipment") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction(
            if (o.busy) "Thinking on-device…" else "Ask",
            { vm.askOffline(question) },
            enabled = question.isNotBlank() && !o.busy,
        )
        o.lastAnswer?.let { a ->
            val (tag, body) = when (a) {
                is com.lpecom.gemglasses.bobai.offline.OfflineAnswer.Phrased ->
                    "On-device model wording, checked against the source" to a.text
                is com.lpecom.gemglasses.bobai.offline.OfflineAnswer.Verbatim ->
                    "Quoted from the manual (${a.reason})" to a.excerpt
                is com.lpecom.gemglasses.bobai.offline.OfflineAnswer.NotInSource ->
                    "Model: the matching page doesn't answer this" to "Not in the approved manual. Check the manual or escalate."
                is com.lpecom.gemglasses.bobai.offline.OfflineAnswer.NoAnswer -> "No approved source" to a.message
            }
            StatusChip(tag, if (a is com.lpecom.gemglasses.bobai.offline.OfflineAnswer.Phrased) Tone.OK else Tone.NEUTRAL)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            a.citations.forEach { c ->
                Text("Source: ${c.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            o.lastLatencyMs?.let {
                Text("Answered in ${it} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "Runs entirely on this phone; nothing is sent anywhere. The model only rewords the manual page that " +
                "search found and is checked before it's shown; if the check fails you see the manual text itself. " +
                "Only the manual excerpt is saved to the job.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkModeCard(vm: BobAiViewModel, onStartAssistance: () -> Unit) {
    val wm by vm.workModeState.collectAsStateWithLifecycle()
    val glasses by vm.glasses.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.workMode.enable() }

    // Permission can be revoked while the app is in the background.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.workMode.recheck()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SectionCard(
        title = "Work Mode",
        accentTone = when {
            !wm.workModeOn -> Tone.NEUTRAL
            wm.heyBobArmed || wm.isActive -> Tone.OK
            else -> Tone.WARN
        },
        trailingHeader = { TechnicalBadge("MODE", if (wm.workModeOn) "ON" else "OFF") },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(heyBobLabel(wm), heyBobTone(wm))
            StatusChip(if (glasses.ok) "Glasses: connected" else "Glasses: not connected", if (glasses.ok) Tone.OK else Tone.WARN)
            StatusChip("AI mode: ${mode.mode.label}", mode.tone())
            StatusChip(vm.mediaStatus.label, if (vm.mediaStatus != MediaStatus.CAMERA_OFF) Tone.WARN else Tone.NEUTRAL)
        }
        if (wm.workModeOn) {
            Text(
                "Microphone route: " + (wm.route?.label() ?: "Checking…"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        wm.unavailableReason?.takeIf { wm.phase == WorkPhase.UNAVAILABLE }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = toneColor(Tone.WARN))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!wm.workModeOn) {
                SecondaryAction("Enable Work Mode", {
                    if (vm.workMode.hasMicPermission()) {
                        vm.workMode.enable()
                    } else {
                        permission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                })
            } else {
                if (wm.isActive) {
                    SecondaryAction("End assistance", { vm.workMode.endAssistance() })
                } else if (wm.canStartManually) {
                    SecondaryAction("Start assistance", {
                        if (vm.workMode.startAssistance()) onStartAssistance()
                    })
                }
                SecondaryAction("Disable Work Mode", { vm.workMode.disable() })
            }
        }
        Text(
            "Work Mode never records or uploads idle audio. Custom \"Hey Bob\" listening needs a wake-word " +
                "engine that hasn't been selected yet, so assistance starts from the button for now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun heyBobLabel(wm: WorkModeState): String {
    return when (wm.phase) {
        WorkPhase.DISABLED -> "Hey Bob: off"
        WorkPhase.ARMING -> "Hey Bob: starting…"
        WorkPhase.ARMED -> if (wm.heyBobArmed) "Hey Bob: armed" else "Hey Bob: unavailable"
        WorkPhase.WAKE_DETECTED -> "Hey Bob: heard you"
        WorkPhase.ACTIVE -> if (wm.trigger == ActivationTrigger.MANUAL) {
            "Assistance active (started manually)"
        } else {
            "Assistance active"
        }
        WorkPhase.IDLE_TIMEOUT -> "Hey Bob: re-arming…"
        WorkPhase.UNAVAILABLE -> "Hey Bob: unavailable"
    }
}

private fun heyBobTone(wm: WorkModeState): Tone = when {
    !wm.workModeOn -> Tone.NEUTRAL
    wm.heyBobArmed || wm.isActive -> Tone.OK
    else -> Tone.WARN
}

@Composable
private fun WorkModeSettingsCard(vm: BobAiViewModel) {
    val wm by vm.workModeState.collectAsStateWithLifecycle()
    SectionCard(
        title = "Work Mode & Hey Bob",
        accentTone = Tone.WARN,
        trailingHeader = { TechnicalBadge("WAKE", "NOT INSTALLED") },
    ) {
        Text("Wake-word engine: ${vm.workMode.engineName}", style = MaterialTheme.typography.bodyMedium)
        Text("Status: ${heyBobLabel(wm)}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "• Custom \"Hey Bob\" needs an on-device keyword-spotting engine. None has been chosen or tested yet " +
                "(licence, offline start-up, false triggers, battery).\n" +
                "• Whether the glasses microphone can be heard continuously, or only the phone microphone, is not " +
                "yet tested.\n" +
                "• Meta's own \"Hey Meta\" voice hand-off is a separate option in the Meta SDK. It is not the same " +
                "as \"Hey Bob\" and is not wired up.\n" +
                "• Listening will not resume after the app is force-stopped or the phone restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssistanceModesCard(vm: BobAiViewModel) {
    val modes by vm.assistanceModes.collectAsStateWithLifecycle()
    SectionCard(
        title = "Assistance modes",
        trailingHeader = { TechnicalBadge("READY", "${modes.count { it.available }}/${modes.size}") },
    ) {
        modes.forEach { m ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatusChip(
                    m.mode.label + if (m.available) " — available" else " — unavailable",
                    if (m.available) Tone.OK else Tone.NEUTRAL,
                )
                Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KnowledgeSyncCard() {
    SectionCard(
        title = "Knowledge sync",
        accentTone = Tone.WARN,
        trailingHeader = { TechnicalBadge("SOURCE", "NONE") },
    ) {
        StatusChip("No sync source connected", Tone.WARN)
        Text(
            "Packs in the Library are simulated mock packs. Verified pack installs are built (each file is " +
                "checked against the approved manifest, the switch is atomic, and the last good version is kept), " +
                "but no business folder, server, or repository is connected yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryAction("Sync now", {}, enabled = false)
    }
}
