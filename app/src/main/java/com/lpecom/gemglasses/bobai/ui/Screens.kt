package com.lpecom.gemglasses.bobai.ui

import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.data.MockContent
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
// Home
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val glasses by vm.glasses.collectAsStateWithLifecycle()
    val active = vm.controller.activeSession()
    val activeJob = active?.let { vm.controller.job(it.jobId) }
    val now = System.currentTimeMillis()
    val usablePacks = snapshot.packs.count { it.isUsable(now) }

    fun start(then: String? = null) {
        val s = vm.controller.startOrResume()
        nav.navigate(if (s.equipment == null) Routes.EQUIPMENT else then ?: Routes.ASSISTANT)
    }

    val ready: @Composable () -> Unit = {
        SectionCard("Ready for a service call?") {
            PrimaryAction(if (active == null) "Start assistant" else "Resume current job", { start() })
            if (active == null) Text("Starts an unassigned session. Camera and microphone stay off.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Current job") {
            if (active == null) {
                Text("No active job.")
            } else {
                Text(activeJob?.site ?: "Unassigned session", style = MaterialTheme.typography.bodyLarge)
                Text("Equipment: " + (active.equipment?.let { "${it.model} — confirmed" } ?: "Not confirmed"))
                SecondaryAction("Open job", { nav.navigate(Routes.job(active.jobId)) })
            }
        }
    }
    val tools: @Composable () -> Unit = {
        SectionCard("Quick actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction("Identify", { vm.controller.startOrResume(); nav.navigate(Routes.EQUIPMENT) })
                SecondaryAction("Troubleshoot", { start() })
                SecondaryAction("Find manual", { nav.goTop(Routes.LIBRARY) })
                SecondaryAction("Capture note", { start(Routes.ASSISTANT) })
            }
        }
        SectionCard("Offline readiness") {
            StatusChip(
                if (usablePacks > 0) "References ready ($usablePacks pack${if (usablePacks == 1) "" else "s"})" else "No usable references",
                if (usablePacks > 0) Tone.OK else Tone.BAD,
            )
            StatusChip("Offline AI not configured", Tone.NEUTRAL)
            mode.unavailable.forEach { Text("• Unavailable: $it", style = MaterialTheme.typography.bodyMedium) }
            SecondaryAction("Manage downloads", { nav.navigate(Routes.DOWNLOADS) })
        }
    }

    ScrollPage {
        Text("BobAI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
// Equipment confirmation
// =========================================================================

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
        ScreenHeader("Confirm equipment", onBack = { nav.popBackStack() })
        ScrollPage {
            if (session == null) {
                Text("No active session.")
                PrimaryAction("Start a session", { vm.controller.startOrResume() })
                return@ScrollPage
            }
            SectionCard("Label photo (mock)") {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(label, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                }
                Text("No camera was used. Real label capture and OCR are not implemented yet.", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = smudged, role = Role.Switch, onValueChange = { smudged = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = smudged, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Simulate a hard-to-read label")
                }
            }
            SectionCard("Extracted candidates") {
                if (ambiguous) {
                    StatusChip("Ambiguous model — choose or type the exact model", Tone.WARN)
                }
                candidates.forEach { c ->
                    OutlinedButton(
                        onClick = { model = c.model; serial = c.serial ?: serial; error = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("${c.model}  ·  ${c.source}") }
                }
                Text("Selecting a candidate only fills the fields. Nothing is confirmed until you tap Confirm.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Model and serial") {
                OutlinedTextField(model, { model = it; error = null }, label = { Text("Model number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(serial, { serial = it }, label = { Text("Serial number (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (model.isNotBlank()) {
                    val known = vm.controller.resolver.isKnownModel(model)
                    StatusChip(
                        if (known) "Known model: ${MockContent.knownModels[vm.controller.resolver.normalizeModel(model)]}"
                        else "Unknown model — no approved manual. Reference-only or escalate.",
                        if (known) Tone.OK else Tone.WARN,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryAction("Confirm this is the equipment", {
                    when (val r = vm.controller.confirmEquipment(model, serial)) {
                        is EquipmentResolver.Confirmation.Confirmed -> {
                            nav.navigate(Routes.ASSISTANT) { popUpTo(Routes.EQUIPMENT) { inclusive = true } }
                        }
                        is EquipmentResolver.Confirmation.Rejected -> error = r.reason
                    }
                }, enabled = model.isNotBlank())
            }
        }
    }
}

// =========================================================================
// Assistant
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
                StatusChip(mode.mode.label, mode.tone())
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = { vm.controller.setPaused(!paused) }) { Text(if (paused) "Resume" else "Pause") }
        }
        if (!expandedLayout) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(mode.mode.label, mode.tone())
                session.equipment?.let { StatusChip(it.model + " confirmed", Tone.OK) }
            }
        }
        if (paused) {
            Surface(color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.fillMaxWidth()) {
                Text("Guidance paused. Nothing will advance until you resume.", color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(8.dp))
            }
        }
        if (LocalExpanded.current) {
            Box(Modifier.weight(1f)) {
                SplitPanes(
                    left = { WorkflowPane(vm, nav, session, listState, expanded = true) },
                    right = { ReferencePane(vm, session) },
                )
            }
            // Bottom actions grouped away from the hinge.
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction(if (paused) "Resume" else "Pause", { vm.controller.setPaused(!paused) })
                    SecondaryAction("Capture (mock)", { vm.controller.mockCapture() })
                }
                Spacer(Modifier.width(HINGE_GAP))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    SecondaryAction("Save draft", {
                        vm.controller.addNote(vm.draftInput); vm.draftInput = ""; vm.message = "Saved on this device."
                    })
                    Button(onClick = endSession, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.heightIn(min = 48.dp)) { Text("End session") }
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
private fun WorkflowPane(vm: BobAiViewModel, nav: NavHostController, session: Session, listState: LazyListState, expanded: Boolean) {
    val c = vm.controller
    val procedure = c.procedureFor(session)
    val engine = c.engine(session)
    val ps = session.procedureState

    LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Equipment") {
                val eq = session.equipment
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(eq?.model ?: "Not confirmed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    StatusChip(if (eq?.isConfirmed == true) "Confirmed" else "Not confirmed", if (eq?.isConfirmed == true) Tone.OK else Tone.WARN)
                }
                eq?.manufacturer?.let { Text(it) }
                SecondaryAction(if (eq == null) "Confirm equipment" else "Correct equipment", { nav.navigate(Routes.EQUIPMENT) })
            }
        }
        if (!expanded) {
            item {
                SectionCard("Camera") {
                    Text(
                        if (session.mediaReferences.isEmpty()) "No image captured. Camera is off unless you capture."
                        else "Last capture: ${session.mediaReferences.last()} (mock placeholder)",
                    )
                    SecondaryAction("Capture (mock)", { c.mockCapture() })
                }
            }
        }
        item {
            SectionCard("Current step") {
                when {
                    session.equipment == null -> Text("Confirm the exact model before guidance can start.")
                    procedure == null || engine == null -> {
                        StatusChip("No approved procedure for ${session.equipment.model}", Tone.WARN)
                        Text("You can still search approved manuals, ask a question (answers are quoted with sources), take notes, or stop and escalate.")
                    }
                    ps == null -> {
                        Text(procedure.title, style = MaterialTheme.typography.titleMedium)
                        Text("Version ${procedure.version} · Requires: ${procedure.requiredAuthorization}")
                        PrimaryAction("Start procedure", { c.startProcedure() })
                    }
                    !engine.prerequisitesMet(ps) -> {
                        Text("Before you begin, confirm each prerequisite:", fontWeight = FontWeight.Bold)
                        procedure.prerequisites.forEachIndexed { i, p ->
                            val checked = i in ps.acknowledgedPrerequisites
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { c.acknowledge(i, !checked) }
                                    .semantics { stateDescription = if (checked) "Confirmed" else "Not confirmed" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(p)
                            }
                        }
                    }
                    else -> {
                        val node = engine.current(ps)
                        Text(engine.stepLabel(ps), style = MaterialTheme.typography.labelLarge)
                        if (node.kind == NodeKind.STOP) StatusChip("Stop — escalation required", Tone.BAD)
                        if (node.kind == NodeKind.COMPLETE) StatusChip("Procedure complete", Tone.OK)
                        Text(node.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(node.instruction, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp)
                        node.citation?.let { cit ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Source: ${cit.label()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    vm.openReference(cit.documentId, cit.page)
                                    if (!expanded) nav.navigate(Routes.doc(cit.documentId, cit.page))
                                }) { Text("Open") }
                            }
                        }
                        if (node.kind == NodeKind.STEP) {
                            Text("BobAI: What result did you observe?", fontWeight = FontWeight.Bold)
                            node.options.forEach { o ->
                                Button(onClick = { (c.chooseOption(o.id) as? StepResult.NotUnderstood)?.let { vm.message = it.reason } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !ps.paused) { Text(o.label) }
                            }
                            OutlinedButton(onClick = { c.notSure(); vm.message = "Not advancing. Re-check, open the source, or stop and escalate." }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("I am not sure") }
                        }
                        if (ps.path.size > 1) TextButton(onClick = { c.back() }) { Text("Previous step") }
                    }
                }
            }
        }
        item {
            SectionCard("Ask or record") {
                OutlinedTextField(
                    vm.draftInput, { vm.draftInput = it },
                    label = { Text("Type a question, a result, or a note") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction("Ask sources", {
                        c.ask(vm.draftInput); vm.draftInput = ""
                    }, enabled = vm.draftInput.isNotBlank())
                    SecondaryAction("Report result", {
                        when (val r = c.interpret(vm.draftInput)) {
                            is StepResult.Moved -> vm.draftInput = ""
                            is StepResult.NotUnderstood -> vm.message = r.reason
                        }
                    }, enabled = vm.draftInput.isNotBlank() && ps != null)
                    SecondaryAction("Save note", { c.addNote(vm.draftInput); vm.draftInput = "" }, enabled = vm.draftInput.isNotBlank())
                }
                Text("Answers quote approved sources only. Typed results advance only on an exact match.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Session timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(session.observations.asReversed()) { o -> TimelineItem(o) { cit -> vm.openReference(cit.documentId, cit.page); if (!expanded) nav.navigate(Routes.doc(cit.documentId, cit.page)) } }
    }
}

@Composable
private fun TimelineItem(o: Observation, onCitation: (com.lpecom.gemglasses.bobai.domain.EvidenceCitation) -> Unit) {
    val who = when (o.kind) {
        ObservationKind.TECHNICIAN_REPORT -> "You (technician report)"
        ObservationKind.VISUAL_CAPTURE -> "Photo"
        ObservationKind.SOURCE_STATEMENT -> "Approved source (quoted)"
        ObservationKind.SYSTEM -> "BobAI (procedure)"
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (o.kind == ObservationKind.TECHNICIAN_REPORT) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(who, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(o.text)
            o.citation?.let { cit ->
                TextButton(onClick = { onCitation(cit) }) { Text("Source: ${cit.label()}") }
            }
        }
    }
}

@Composable
private fun ControlBar(vm: BobAiViewModel, session: Session, onEnd: () -> Unit) {
    val c = vm.controller
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mod = Modifier.weight(1f).heightIn(min = 52.dp)
            val pad = PaddingValues(horizontal = 4.dp)
            OutlinedButton(onClick = {
                vm.message = "Voice control isn't connected to BobAI workflows yet. Type below, or use Settings → Live voice for the existing assistant."
            }, modifier = mod, contentPadding = pad) { Text("Speak", maxLines = 1, overflow = TextOverflow.Clip) }
            OutlinedButton(onClick = {
                val e = c.engine(session); val ps = session.procedureState
                vm.message = if (e != null && ps != null) e.current(ps).instruction else "No current step to repeat."
            }, modifier = mod, contentPadding = pad) { Text("Repeat", maxLines = 1, overflow = TextOverflow.Clip) }
            OutlinedButton(onClick = { c.mockCapture() }, modifier = mod, contentPadding = pad) { Text("Capture", maxLines = 1, overflow = TextOverflow.Clip) }
            Button(onClick = onEnd, modifier = mod, contentPadding = pad, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("End", maxLines = 1) }
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
        TabRow(selectedTabIndex = vm.referenceTab) {
            listOf("Manual", "Photos", "Notes").forEachIndexed { i, t ->
                Tab(selected = vm.referenceTab == i, onClick = { vm.referenceTab = i }, text = { Text(t) })
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
                session.mediaReferences.forEach { Text("• $it (mock placeholder)") }
            }
            else -> ScrollPage {
                val notes = session.observations.filter { it.kind == ObservationKind.TECHNICIAN_REPORT }
                if (notes.isEmpty()) Text("No technician notes yet.")
                notes.forEach { Text("• ${it.text}") }
            }
        }
    }
}

// =========================================================================
// Library + document viewer
// =========================================================================

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
        ScreenHeader("Library")
        ScrollPage {
            OutlinedTextField(query, { query = it }, label = { Text("Search manuals") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Applies to ${equipment?.model ?: "current equipment"}", "Downloaded").forEachIndexed { i, l ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) }, enabled = i != 1 || equipment != null)
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
    SectionCard(d.title) {
        Text("Revision ${d.revision} · Applies to: ${d.applicableModels.joinToString()}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (d.approvalStatus) {
                ApprovalStatus.APPROVED -> StatusChip("Approved", Tone.OK)
                ApprovalStatus.DRAFT -> StatusChip("Draft — unreviewed, not used for guidance", Tone.WARN)
                ApprovalStatus.REVOKED -> StatusChip("Revoked — not used for guidance", Tone.BAD)
            }
            StatusChip(avail.label, when (avail) { DocAvailability.LOCAL -> Tone.OK; DocAvailability.STALE -> Tone.WARN; else -> Tone.BAD })
        }
        SecondaryAction(if (avail.readable) "Open" else "View details", onOpen)
    }
}

@Composable
fun DocumentScreen(vm: BobAiViewModel, nav: NavHostController, docId: String, initialPage: Int?) {
    val doc = vm.controller.knowledge.document(docId)
    var page by rememberSaveable { mutableStateOf(initialPage) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(doc?.title ?: "Document", onBack = { nav.popBackStack() })
        if (doc == null) {
            ScrollPage { Text("This document no longer exists in the library.") }
        } else {
            val back = if (vm.controller.activeSession() != null) ({ nav.navigate(Routes.ASSISTANT) { popUpTo(Routes.ASSISTANT) { inclusive = true } } }) else null
            DocumentBody(vm, doc, page, onPage = { page = it }, returnToStep = back)
        }
    }
}

@Composable
private fun DocumentBody(vm: BobAiViewModel, doc: SourceDocument, page: Int?, onPage: (Int) -> Unit, returnToStep: (() -> Unit)?) {
    val avail = vm.controller.knowledge.availability(doc)
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    ScrollPage {
        Text("Revision ${doc.revision} · ${doc.approvalStatus.name.lowercase()} · Applies to ${doc.applicableModels.joinToString()}", style = MaterialTheme.typography.bodyMedium)
        if (doc.approvalStatus != ApprovalStatus.APPROVED) StatusChip("Not approved — reference only, never used for guidance", Tone.BAD)
        if (!avail.readable) {
            StatusChip(avail.label, Tone.BAD)
            Text("This document isn't available on the device. Open Downloads and download or repair its pack.")
            return@ScrollPage
        }
        if (avail == DocAvailability.STALE) StatusChip("Pack expired — refresh before relying on this content", Tone.WARN)
        val pages = doc.pages
        val idx = pages.indexOfFirst { it.number == page }.takeIf { it >= 0 } ?: 0
        val p = pages[idx]
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onPage(pages[idx - 1].number) }, enabled = idx > 0) { Text("◀ Prev") }
            Text("Page ${p.number}", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            TextButton(onClick = { onPage(pages[idx + 1].number) }, enabled = idx < pages.size - 1) { Text("Next ▶") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom")
            Slider(zoom, { zoom = it }, valueRange = 1f..2f, modifier = Modifier.weight(1f))
        }
        Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(p.heading, fontWeight = FontWeight.Bold, fontSize = (20 * zoom).sp)
                Spacer(Modifier.padding(4.dp))
                Text(p.body, fontSize = (16 * zoom).sp, lineHeight = (24 * zoom).sp)
                Spacer(Modifier.padding(4.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text("[Original page image / diagram placeholder — mock]")
                }
            }
        }
        returnToStep?.let { PrimaryAction("Return to current step", it) }
    }
}

// =========================================================================
// Jobs, job detail, summary review
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
        ScreenHeader("Jobs")
        ScrollPage {
            Text("Jobs are stored on this device only. Sync is not implemented yet.", style = MaterialTheme.typography.bodyMedium)
            if (snapshot.jobs.isEmpty()) Text("No jobs yet. Start the assistant from Home to create one.")
            snapshot.jobs.sortedByDescending { it.updatedAt }.forEach { j ->
                SectionCard(j.site) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(j.status.name.lowercase().replace('_', ' '), if (j.status.name == "COMPLETED") Tone.OK else Tone.NEUTRAL)
                        StatusChip(j.syncState.label(), Tone.NEUTRAL)
                    }
                    Text("Updated ${fmtDate(j.updatedAt)} · ${snapshot.sessions.count { it.jobId == j.id }} session(s)")
                    SecondaryAction("Open job", { nav.navigate(Routes.job(j.id)) })
                }
            }
        }
    }
}

@Composable
fun JobDetailScreen(vm: BobAiViewModel, nav: NavHostController, jobId: String) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val job = snapshot.jobs.firstOrNull { it.id == jobId }
    val sessions = snapshot.sessions.filter { it.jobId == jobId }.sortedByDescending { it.updatedAt }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(job?.site ?: "Job", onBack = { nav.popBackStack() })
        ScrollPage {
            if (job == null) { Text("Job not found."); return@ScrollPage }
            SectionCard("Job") {
                Text("Status: ${job.status.name.lowercase().replace('_', ' ')} · ${job.syncState.label()}")
                Text("Equipment: " + (sessions.firstNotNullOfOrNull { it.equipment }?.let { "${it.model} (S/N ${it.serial.ifBlank { "n/a" }})" } ?: "Not confirmed"))
                if (job.isMock) StatusChip("Mock job", Tone.WARN)
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
                SectionCard("Session · ${fmtDate(s.createdAt)}") {
                    Text("${s.status.name.lowercase()} · ${s.observations.size} timeline entries · ${s.mediaReferences.size} photo(s)")
                    s.observations.takeLast(4).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                    if (s.status == SessionStatus.ENDED) {
                        SecondaryAction(if (s.summaryReviewed) "View reviewed summary" else "Review job summary", { nav.navigate(Routes.summary(s.id)) })
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(vm: BobAiViewModel, nav: NavHostController, sessionId: String) {
    val session = vm.controller.session(sessionId)
    var text by rememberSaveable { mutableStateOf(session?.draftSummary ?: "") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Job summary review", onBack = { nav.popBackStack() })
        ScrollPage {
            if (session == null) { Text("Session not found."); return@ScrollPage }
            StatusChip(if (session.summaryReviewed) "Reviewed · saved locally" else "Draft · saved locally", if (session.summaryReviewed) Tone.OK else Tone.WARN)
            Text("Edit anything that's wrong. Nothing is shared or synced from this screen.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it }, label = { Text("Summary") }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryAction("Save draft", { vm.controller.saveSummary(sessionId, text, reviewed = false); vm.message = "Draft saved on this device." })
                Button(onClick = {
                    vm.controller.saveSummary(sessionId, text, reviewed = true)
                    vm.message = "Summary marked reviewed and saved locally."
                    nav.goTop(Routes.JOBS)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Mark reviewed & save") }
            }
        }
    }
}

// =========================================================================
// Downloads
// =========================================================================

@Composable
fun DownloadsScreen(vm: BobAiViewModel, nav: NavHostController) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val free = remember(snapshot) { StatFs(context.filesDir.path).availableBytes }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads", onBack = { nav.popBackStack() })
        ScrollPage {
            Text("Free space on device: ${free / 1_000_000_000} GB", style = MaterialTheme.typography.bodyLarge)
            Text("Downloads here are simulated (no files are transferred). States and storage checks are real UI states.", style = MaterialTheme.typography.bodySmall)
            snapshot.packs.forEach { p -> PackCard(p, now, free, vm) { confirmDelete = p.id } }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove pack?") },
            text = { Text("Manuals in this pack won't be available offline until downloaded again.") },
            confirmButton = { TextButton(onClick = { vm.controller.setPackState(id, PackState.NOT_DOWNLOADED); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackCard(p: KnowledgePack, now: Long, free: Long, vm: BobAiViewModel, onDelete: () -> Unit) {
    val c = vm.controller
    SectionCard(p.name) {
        Text("Version ${p.version} · ${mb(p.sizeBytes)} · ${p.documentIds.size} document(s)")
        val (label, tone) = when {
            p.state == PackState.COMPLETE && p.isExpired(now) -> "Expired ${fmtDate(p.expiresAt)} — refresh required" to Tone.WARN
            p.state == PackState.COMPLETE -> "Downloaded · verified" to Tone.OK
            p.state == PackState.PENDING -> "Download pending" to Tone.NEUTRAL
            p.state == PackState.CORRUPTED -> "Corrupted — integrity check failed" to Tone.BAD
            p.state == PackState.INSUFFICIENT_STORAGE -> "Insufficient storage" to Tone.BAD
            else -> "Not downloaded" to Tone.NEUTRAL
        }
        StatusChip(label, tone)
        if (p.downloadedAt != null) Text("Last verified ${fmtDate(p.lastVerifiedAt)} · Expires ${fmtDate(p.expiresAt)}")
        val download = {
            c.setPackState(p.id, if (p.sizeBytes > free) PackState.INSUFFICIENT_STORAGE else PackState.COMPLETE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (p.state) {
                PackState.COMPLETE -> {
                    if (p.isExpired(now)) SecondaryAction("Refresh", download)
                    else SecondaryAction("Verify", { c.setPackState(p.id, PackState.COMPLETE); vm.message = "Pack verified (simulated checksum)." })
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
// Settings
// =========================================================================

@Composable
fun SettingsScreen(vm: BobAiViewModel, nav: NavHostController) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val glasses by vm.glasses.collectAsStateWithLifecycle()
    val config = LocalConfiguration.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings")
        ScrollPage {
            SectionCard("Hardware") {
                StatusChip(glasses.label, if (glasses.ok) Tone.OK else Tone.WARN)
                Text(glasses.detail)
                Text("Glasses backend: ${if (BuildConfig.USE_REAL_GLASSES) "Meta DAT SDK" else "Mock (built with gemglasses.useRealGlasses=false)"}")
                Text("Glasses camera/audio in BobAI workflows: not wired yet (Phase 2, needs hardware testing).", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("Assistant and inference") {
                StatusChip("Mode: ${mode.mode.label}", mode.tone())
                mode.available.forEach { Text("✓ $it") }
                mode.unavailable.forEach { Text("✕ $it") }
                Text("Local model: none configured. Requires benchmarks on the exact phone (Phase 4).")
                Text("Offline speech / voices: none downloaded.")
            }
            SectionCard("Live voice (existing GemGlasses)") {
                Text("The original Gemini Live voice assistant is unchanged. It needs internet and the configured backend.")
                SecondaryAction("Open Live voice", { nav.navigate(Routes.LIVE) })
            }
            SectionCard("Privacy") {
                Text("• Camera and microphone are never used in the background by BobAI.")
                Text("• Photos are captured only when you tap Capture.")
                Text("• Jobs, notes and summaries are stored only on this device. Nothing is shared without review.")
                Text("• Retention controls: not configured yet.")
            }
            SectionCard("Accessibility") {
                Text("Follows the system font size and dark theme. All status indicators include text and icons.")
            }
            SectionCard("Diagnostics") {
                Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                Text("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                Text("Window: ${config.screenWidthDp}×${config.screenHeightDp} dp · layout ${if (LocalExpanded.current) "expanded (two-pane)" else "compact (single-pane)"}")
            }
        }
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }
