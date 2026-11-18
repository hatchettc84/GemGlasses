# BobAI Addendum — Capability Audit, Plan and Test Strategy

Covers *BobAI Addendum: Hey Bob Activation, Live AI Assistance, and Synchronized Local Knowledge* (§12 "first task").
Branch: `bobai-work-mode-scaffold` (built on `bobai-industrial-ui`). Audit date: 2026-11-18.

Legend: **Works** = built and tested on hardware · **Built** = implemented and unit-tested, not hardware-tested · **Scaffold** = honest UI/state only · **Missing** = not started.

## 1. Existing vs required

| ID | Requirement | Status | What exists today |
|---|---|---|---|
| WAKE-01 | "Hey Bob" during explicit Work Mode | **Scaffold** | Work Mode on/off, full state machine (`bobai/workmode/WorkMode.kt`), `WakeWordEngine` interface. Engine = `NoWakeWordEngine`, so Work Mode reports *Hey Bob: unavailable* and offers **Start assistance**. |
| WAKE-02 | Local wake detection, no idle upload | **Scaffold** | Interface contract requires on-device, transient audio. No engine selected, no microphone opened. |
| LIVE-01 | Live spoken AI conversation | **Exists separately** | Legacy Gemini Live voice (`gemini/LiveSession.kt`, `audio/MicStreamer.kt`, `BluetoothAudioRouter`) under Settings → Live voice. Not connected to BobAI procedures/retrieval. |
| LIVE-02 | Sustained visual context from glasses | **Built (pieces)** | Glasses camera → phone **works on device** (DAT 1.0.1, 360×640 frames, ~5 s to first frame, first stream after idle often fails once). Bounded latest-frame buffer, AI-rate sampler, staleness/invalidation (`bobai/live/LiveAssistance.kt`). No provider upload. |
| LIVE-03 | Capture/transmission status, immediate stop | **Partial** | `MediaStatus` shown on Home (Camera off / local capture). Capture is tap-only and closes the stream after one frame. No cloud transmission exists, so nothing to stop yet. |
| KNOW-01 | Retrieve approved, equipment-specific local knowledge | **Built (mock data)** | `KnowledgeRepository`: tenant, approval, exact-model, pack-availability filtering; cited answers; revision conflict detection. Content is mock. |
| SYNC-01 | Sync versioned approved knowledge | **Built (installer only)** | `AtomicPackStore` + `PackManifest` (`bobai/sync/PackSync.kt`). `KnowledgeSourceAdapter` interface, **no connector**. Library packs are still simulated. |
| SYNC-02 | Keep last verified version on failed update | **Built** | Staged install, per-file size + SHA-256 check, atomic pointer switch, previous generation kept, rollback, tamper re-verify. Unit-tested. |
| OFF-01 | Honest offline reference without assumed local AI | **Built** | `ModeResolver` + new `AssistanceModes`: downloaded manuals never imply offline AI. |

## 2. Hardware and platform findings (this session)

- **Glasses camera:** works through DAT 1.0.1 in Developer Mode. Real App ID path fails ("Caller Identity … is not trusted"), see earlier PRs.
- **Glasses microphone:** the DAT SDK has **no microphone/audio API**. Glasses audio reaches Android only via Bluetooth hands-free (HFP/SCO), which the legacy Gemini path already routes to. During testing Android reported **no active HFP device** for the glasses (only the DAT data link), so Work Mode correctly showed *Phone microphone*. Continuous glasses-mic listening is **unverified**.
- **Native voice invocation:** DAT 1.0.1 contains `startVoiceInvocationsStream` (Meta's own wake phrase handing off to an app). This is a **separate** option, not custom "Hey Bob", and is untested.
- **Android rules:** background microphone use needs a running `microphone`-type foreground service started while the app is visible; it cannot start from the background, does not survive force-stop, and the app may not auto-start after reboot for mic use. The existing `AgentForegroundService` is microphone-typed and can be reused when an engine exists.

## 3. Unresolved questions (need answers or tests)

1. Exact glasses generation/firmware and the Fold's Android version and RAM (Fold reports 1968×2184 inner display; model string not yet recorded).
2. Can HFP to the glasses stay open for hours of idle listening, and what does that cost in glasses battery? Does it interfere with Meta's own assistant?
3. Wake-word engine choice: custom-phrase, on-device, commercial licence (e.g. Picovoice Porcupine needs an access key and paid licence for commercial use; openWakeWord/Vosk are open but need a trained "Hey Bob" model and evaluation). Verify current terms before choosing.
4. Which AI provider carries BobAI voice + frames (the legacy app uses Gemini Live), its frame-rate limits, session length and cost.
5. Where approved knowledge actually lives (phone folder, business PC, NAS, Git, mixed) and who approves it.
6. Revocation/expiry policy for offline phones and critical content.

## 4. Minimal change plan (incremental, nothing rewritten)

| Step | Change | State |
|---|---|---|
| 1 | Audit (this document) | Done |
| 2 | Honest UI + lifecycle scaffold: Work Mode card (Home), Work Mode & Hey Bob, Assistance modes, Knowledge sync cards (Settings) | **Done** |
| 3 | Manual assistance end-to-end: Start assistance opens the job assistant (done); add a microphone-route check that briefly captures audio on the chosen route and marks it `verified` | Next |
| 4 | Prototype one wake-word engine behind `WakeWordEngine`, run inside `AgentForegroundService`, measure false triggers/latency/battery on glasses vs phone mic | Needs engine decision |
| 5 | Guided live view: feed `FrameSampler` from `GlassesCameraSource`, send to the provider, stop control, reconnect | Needs provider decision |
| 6 | First `KnowledgeSourceAdapter` (likely an HTTPS publisher or a phone-folder import) + approval/publish step upstream | Needs source decision |
| 7 | Replace simulated packs with `AtomicPackStore` generations; point `KnowledgeRepository` at the active generation; WorkManager sync triggers (manual, session start, reconnect) | After 6 |
| 8–10 | Offline validation, optional local models, supervised pilot | Later |

## 5. Test strategy

**Automated (JVM, `testDebugUnitTest`, 56 tests pass):**
- `WorkModeMachineTest`: lifecycle, no false "armed", self-trigger suppression, permission/mic loss, disable, manual fallback.
- `LiveAssistanceTest`: newest-frame-only buffer, AI-rate limiting, stale-frame rejection, invalidation after interruption, honest assistance modes.
- `AtomicPackStoreTest`: verified install, rollback, corrupt/truncated/missing files keep last good pack, unapproved/foreign-tenant/path-traversal/older packs refused, tamper detection.
- Existing domain tests (retrieval, procedures, confirmation, modes) unchanged.

**On-device (manual, record glasses-vs-phone mic for each):** Work Mode enable/deny permission, revoke permission while backgrounded, screen lock, calls, Bluetooth on/off, fold/unfold during an active session, force-stop (indicator must not survive), glasses camera first-stream retry, capture during weak link.

**When an engine/provider exists:** false-activation rate in shop noise and pocket placement, wake latency, battery and thermal over a shift, frame upload rate and latency, stop-to-UI latency, provider session expiry and reconnect.

## 6. Verified on the Z Fold7 (2026-11-18)

- Build installs and runs. Enabling Work Mode requests microphone permission, then shows *Hey Bob: unavailable* with the reason, *Microphone route: Phone microphone — not yet verified*, and Start/Disable buttons. Never shows "armed".
- Settings shows the Work Mode & Hey Bob limitations, five assistance modes (only *Look now* available), and Knowledge sync with no source and a disabled *Sync now*.
