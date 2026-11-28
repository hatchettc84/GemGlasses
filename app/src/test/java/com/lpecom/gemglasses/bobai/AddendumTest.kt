package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.live.AssistanceFacts
import com.lpecom.gemglasses.bobai.live.AssistanceMode
import com.lpecom.gemglasses.bobai.live.AssistanceModes
import com.lpecom.gemglasses.bobai.live.FrameSampler
import com.lpecom.gemglasses.bobai.live.LatestFrameBuffer
import com.lpecom.gemglasses.bobai.live.TimedFrame
import com.lpecom.gemglasses.bobai.live.VisualContext
import com.lpecom.gemglasses.bobai.sync.AtomicPackStore
import com.lpecom.gemglasses.bobai.sync.InstallResult
import com.lpecom.gemglasses.bobai.sync.PackFileEntry
import com.lpecom.gemglasses.bobai.sync.PackManifest
import com.lpecom.gemglasses.bobai.workmode.ActivationTrigger
import com.lpecom.gemglasses.bobai.workmode.EngineStart
import com.lpecom.gemglasses.bobai.workmode.MicRoute
import com.lpecom.gemglasses.bobai.workmode.NoWakeWordEngine
import com.lpecom.gemglasses.bobai.workmode.RouteKind
import com.lpecom.gemglasses.bobai.workmode.WorkEvent
import com.lpecom.gemglasses.bobai.workmode.WorkModeMachine
import com.lpecom.gemglasses.bobai.workmode.WorkModeState
import com.lpecom.gemglasses.bobai.workmode.WorkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

private val GLASSES = MicRoute(RouteKind.BLUETOOTH_HEADSET, "RB Meta 001B")

private fun run(vararg events: WorkEvent, from: WorkModeState = WorkModeState()) =
    events.fold(from) { s, e -> WorkModeMachine.reduce(s, e) }

class WorkModeMachineTest {

    @Test fun `full wake lifecycle returns to armed`() {
        var s = run(WorkEvent.Enable)
        assertEquals(WorkPhase.ARMING, s.phase)
        assertFalse("not armed until the engine starts", s.heyBobArmed)

        s = run(WorkEvent.EngineReady(GLASSES), from = s)
        assertEquals(WorkPhase.ARMED, s.phase)
        assertTrue(s.heyBobArmed)
        assertTrue(s.detectorShouldListen)

        s = run(WorkEvent.WakeDetected, WorkEvent.Acknowledged, from = s)
        assertEquals(WorkPhase.ACTIVE, s.phase)
        assertEquals(ActivationTrigger.WAKE_WORD, s.trigger)
        assertFalse("detector is muted while Bob is active", s.detectorShouldListen)

        s = run(WorkEvent.IdleTimeout, from = s)
        assertEquals(WorkPhase.IDLE_TIMEOUT, s.phase)
        assertFalse(s.heyBobArmed)

        s = run(WorkEvent.EngineReady(GLASSES), from = s)
        assertEquals(WorkPhase.ARMED, s.phase)
    }

    @Test fun `own speech cannot re-trigger an active session`() {
        val active = run(WorkEvent.Enable, WorkEvent.EngineReady(GLASSES), WorkEvent.WakeDetected, WorkEvent.Acknowledged)
        assertSame(active, WorkModeMachine.reduce(active, WorkEvent.WakeDetected))
    }

    @Test fun `no engine means unavailable with manual fallback, never armed`() {
        val result = NoWakeWordEngine.start(GLASSES, onWake = {})
        assertTrue(result is EngineStart.Failed)
        val s = run(WorkEvent.Enable, WorkEvent.EngineFailed((result as EngineStart.Failed).reason, GLASSES))
        assertEquals(WorkPhase.UNAVAILABLE, s.phase)
        assertFalse(s.heyBobArmed)
        assertTrue(s.workModeOn)
        assertTrue(s.canStartManually)
        assertEquals(NoWakeWordEngine.REASON, s.unavailableReason)

        val manual = run(WorkEvent.ManualActivate, from = s)
        assertEquals(WorkPhase.ACTIVE, manual.phase)
        assertEquals(ActivationTrigger.MANUAL, manual.trigger)

        // Session end tries to re-arm; with no engine it is unavailable again, not armed.
        val after = run(WorkEvent.SessionEnded, WorkEvent.EngineFailed(NoWakeWordEngine.REASON), from = manual)
        assertEquals(WorkPhase.UNAVAILABLE, after.phase)
        assertFalse(after.heyBobArmed)
    }

    @Test fun `losing permission or the microphone clears the armed indicator`() {
        val armed = run(WorkEvent.Enable, WorkEvent.EngineReady(GLASSES))
        val lost = run(WorkEvent.ResourceLost("Microphone permission not granted"), from = armed)
        assertEquals(WorkPhase.UNAVAILABLE, lost.phase)
        assertFalse(lost.heyBobArmed)
        assertFalse(lost.detectorShouldListen)
        assertEquals("Microphone permission not granted", lost.unavailableReason)

        val activeThenLost = run(
            WorkEvent.ManualActivate, WorkEvent.ResourceLost("Glasses disconnected"), from = armed,
        )
        assertEquals(WorkPhase.UNAVAILABLE, activeThenLost.phase)
        assertNull(activeThenLost.trigger)
    }

    @Test fun `disable from any state stops everything`() {
        val active = run(WorkEvent.Enable, WorkEvent.EngineReady(GLASSES), WorkEvent.ManualActivate)
        val off = run(WorkEvent.Disable, from = active)
        assertEquals(WorkModeState(), off)
        assertFalse(off.workModeOn)
        // Events after disable don't resurrect it.
        assertEquals(off, run(WorkEvent.EngineReady(GLASSES), WorkEvent.ResourceLost("x"), WorkEvent.ManualActivate, from = off))
    }

    @Test fun `manual start is refused while disabled or arming`() {
        assertEquals(WorkPhase.DISABLED, run(WorkEvent.ManualActivate).phase)
        assertEquals(WorkPhase.ARMING, run(WorkEvent.Enable, WorkEvent.ManualActivate).phase)
    }

    @Test fun `route label says unverified until audio is captured`() {
        assertTrue(GLASSES.label().endsWith("not yet verified"))
        assertFalse(GLASSES.copy(verified = true).label().contains("not yet verified"))
        assertEquals("No microphone available", MicRoute(RouteKind.NONE).label())
    }
}

class LiveAssistanceTest {

    private fun frame(t: Long) = TimedFrame(ByteArray(1), t)

    @Test fun `latest frame buffer keeps only the newest frame`() {
        val b = LatestFrameBuffer()
        b.offer(frame(1)); b.offer(frame(2)); b.offer(frame(3))
        assertEquals(3L, b.take()?.capturedAt)
        assertEquals(2, b.dropped)
        assertNull(b.take())
    }

    @Test fun `sampler rate-limits uploads independently of capture rate`() {
        val s = FrameSampler(minIntervalMs = 1_000, maxAgeMs = 2_000)
        s.offer(frame(0))
        assertEquals(0L, s.nextForUpload(10)?.capturedAt)
        s.offer(frame(100)); s.offer(frame(500))
        assertNull("too soon after the last upload", s.nextForUpload(600))
        s.offer(frame(900))
        assertEquals(900L, s.nextForUpload(1_010)?.capturedAt)
    }

    @Test fun `stale frames are never released`() {
        val s = FrameSampler(minIntervalMs = 0, maxAgeMs = 2_000)
        s.offer(frame(0))
        assertNull(s.nextForUpload(5_000))
    }

    @Test fun `invalidation discards frames from before an interruption`() {
        val s = FrameSampler(minIntervalMs = 0, maxAgeMs = 10_000)
        s.offer(frame(100))
        s.invalidate(200)
        assertNull(s.nextForUpload(300))
        s.offer(frame(150)) // captured before the interruption but delivered late
        assertNull(s.nextForUpload(310))
        s.offer(frame(250))
        assertEquals(250L, s.nextForUpload(320)?.capturedAt)
    }

    @Test fun `visual context expires`() {
        val v = VisualContext(capturedAt = 1_000, maxAgeMs = 5_000)
        assertFalse(v.isStale(5_000))
        assertTrue(v.isStale(6_001))
    }

    @Test fun `assistance modes report only what is built`() {
        val modes = AssistanceModes.resolve(AssistanceFacts(online = true, glassesConnected = true, usableReferencePacks = 1))
            .associateBy { it.mode }
        assertFalse(modes.getValue(AssistanceMode.VOICE).available)
        assertTrue(modes.getValue(AssistanceMode.LOOK_NOW).available)
        assertFalse(modes.getValue(AssistanceMode.GUIDED_LIVE_VIEW).available)
        assertFalse("downloaded manuals never imply offline AI", modes.getValue(AssistanceMode.OFFLINE_ASSISTED).available)
        assertTrue(modes.getValue(AssistanceMode.OFFLINE_REFERENCE).available)

        val offline = AssistanceModes.resolve(AssistanceFacts(online = false, glassesConnected = false, usableReferencePacks = 0))
        assertTrue(offline.none { it.available })
    }
}

class AtomicPackStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tenant = "tenant-x"

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifest(version: Int, files: Map<String, ByteArray>, approval: String = "APPROVED", tenantId: String = tenant) =
        PackManifest(
            packId = "pack-demo",
            tenantId = tenantId,
            version = version,
            schemaVersion = 1,
            generatedAt = 1_794_000_000_000L,
            documentIds = listOf("doc-1"),
            revisions = mapOf("doc-1" to "v$version"),
            equipmentApplicability = mapOf("doc-1" to listOf("ABC-123")),
            approvalStatus = approval,
            files = files.map { (p, b) -> PackFileEntry(p, sha(b), b.size.toLong()) },
            indexVersion = "1",
        )

    private fun source(files: Map<String, ByteArray>): (PackFileEntry) -> java.io.InputStream? =
        { e -> files[e.path]?.let(::ByteArrayInputStream) }

    private val v1 = mapOf("doc-1.txt" to "Rev 1 text".toByteArray(), "index/terms.json" to "{}".toByteArray())
    private val v2 = mapOf("doc-1.txt" to "Rev 2 text, longer".toByteArray(), "index/terms.json" to "{\"a\":1}".toByteArray())

    @Test fun `verified install becomes active and keeps the previous generation`() {
        val store = AtomicPackStore(tmp.root)
        assertNull(store.active("pack-demo"))

        assertEquals(InstallResult.Installed(1, null), store.install(manifest(1, v1), tenant, source(v1)))
        assertEquals(InstallResult.Installed(2, 1), store.install(manifest(2, v2), tenant, source(v2)))

        val active = activeOf(store)
        assertEquals(2, active.manifest.version)
        assertEquals("Rev 2 text, longer", active.file("doc-1.txt").readText())
        assertTrue(store.verifyActive("pack-demo").isEmpty())

        assertTrue(store.rollback("pack-demo"))
        assertEquals(1, activeOf(store).manifest.version)
        assertFalse("only one rollback step is kept", store.rollback("pack-demo"))
    }

    @Test fun `corrupt or interrupted download keeps the last good pack`() {
        val store = AtomicPackStore(tmp.root)
        store.install(manifest(1, v1), tenant, source(v1))

        val corrupt = v2 + ("doc-1.txt" to "Rev 2 text, LONGER".toByteArray()) // same size, wrong bytes
        assertTrue(store.install(manifest(2, v2), tenant, source(corrupt)) is InstallResult.Rejected)

        val truncated = v2 + ("doc-1.txt" to "Rev 2".toByteArray())
        assertTrue(store.install(manifest(2, v2), tenant, source(truncated)) is InstallResult.Rejected)

        val missing = v2 - "index/terms.json"
        assertTrue(store.install(manifest(2, v2), tenant, source(missing)) is InstallResult.Rejected)

        val active = activeOf(store)
        assertEquals(1, active.manifest.version)
        assertEquals("Rev 1 text", active.file("doc-1.txt").readText())
        assertTrue(store.verifyActive("pack-demo").isEmpty())
        assertTrue("no staging left behind", File(tmp.root, "staging").listFiles().isNullOrEmpty())
    }

    @Test fun `unapproved, foreign-tenant, unsafe or older packs are refused`() {
        val store = AtomicPackStore(tmp.root)
        assertTrue(store.install(manifest(1, v1, approval = "DRAFT"), tenant, source(v1)) is InstallResult.Rejected)
        assertTrue(store.install(manifest(1, v1, tenantId = "other"), tenant, source(v1)) is InstallResult.Rejected)
        val evil = mapOf("../escape.txt" to "x".toByteArray())
        assertTrue(store.install(manifest(1, evil), tenant, source(evil)) is InstallResult.Rejected)
        assertFalse(File(tmp.root.parentFile, "escape.txt").exists())
        assertNull(store.active("pack-demo"))

        store.install(manifest(2, v2), tenant, source(v2))
        assertEquals(InstallResult.AlreadyCurrent(2), store.install(manifest(2, v2), tenant, source(v2)))
        assertTrue(store.install(manifest(1, v1), tenant, source(v1)) is InstallResult.Rejected)
        assertEquals(2, activeOf(store).manifest.version)
    }

    @Test fun `tampering after install is detected`() {
        val store = AtomicPackStore(tmp.root)
        store.install(manifest(1, v1), tenant, source(v1))
        activeOf(store).file("doc-1.txt").writeText("tampered!!")
        assertEquals(listOf("Hash mismatch doc-1.txt"), store.verifyActive("pack-demo"))
    }

    @Test fun `unsafe paths are recognised`() {
        listOf("", "/abs", "a/../b", "./a", "a//b", "a\\b", "manifest.json").forEach {
            assertFalse(it, AtomicPackStore.isSafePath(it))
        }
        assertTrue(AtomicPackStore.isSafePath("docs/manual-rev-b.pdf"))
    }

    private fun activeOf(store: AtomicPackStore) = store.active("pack-demo").also { assertNotNull(it) }!!
}
