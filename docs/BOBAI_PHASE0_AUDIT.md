# BobAI — Phase 0 audit and Phase 1 scaffold

Branch: `bobai-ui-scaffold` (on a fresh clone of `hatchettc84/GemGlasses`, commit `5663438`).

## 1. Existing implementation vs proposed features

### What exists (verified by reading the source and building)
| Area | Current state |
|---|---|
| Build | Gradle 8 / AGP 8.5.2, Kotlin 2.0.20, compileSdk/targetSdk 35, minSdk 29, JDK 17 target. `./gradlew assembleDebug testDebugUnitTest` passes on a clean clone (mock backend). |
| UI | Jetpack Compose + Material 3, Hilt, Navigation Compose. Three Portuguese screens: Session, Transcript, Settings. |
| Glasses | `GlassesBackend` interface; `MockGlassesBackend` (default) and `RealGlassesBackend` (`src/realGlasses`, only compiled with `-Pgemglasses.useRealGlasses=true` + a GitHub Packages token). DAT SDK pinned at `com.meta.wearable:device-access-toolkit:0.5.0`. The real backend's header says it was written from docs and must be verified against the installed SDK. |
| AI | Gemini Live over one raw OkHttp WebSocket; ephemeral tokens from a Cloudflare Worker (`backend/`). Four tools: vision burst, Maps navigation, grounded places, message draft. |
| Secrets | Gemini key only on the Worker. App gets `BACKEND_URL` / `APP_SECRET` / Meta IDs from `local.properties` into `BuildConfig`/manifest. Note: `APP_SECRET` is still a shared secret inside the APK — fine for a dev preview, not for a commercial rollout. |
| Permissions | INTERNET, RECORD_AUDIO, FINE_LOCATION, Bluetooth (legacy + CONNECT/SCAN), FOREGROUND_SERVICE(_MICROPHONE), POST_NOTIFICATIONS. BobAI adds ACCESS_NETWORK_STATE. |
| Tests | 3 JVM unit test classes (tool registry, schemas, conversation store). No instrumented tests. |

### Not present before this branch
Equipment identification/confirmation, document library, knowledge packs, procedure engine, job/session persistence, offline modes, BobAI navigation, Fold-aware layouts.

## 2. Change plan (implemented in this branch)
All new code is in `app/src/main/java/com/lpecom/gemglasses/bobai/`. Existing GemGlasses code is untouched except:
- `MainActivity.kt` — launcher shows `BobAiRoot`; original root is reachable at Settings → Live voice (made `internal`, otherwise unchanged).
- `di/AppModule.kt` — provides a singleton `BobAiController` (single background writer thread).
- `AndroidManifest.xml` — `ACCESS_NETWORK_STATE` for honest online/offline status.
- `strings.xml` — launcher label `BobAI`.

| File | Brief component |
|---|---|
| `domain/Models.kt` | §11 data model (Equipment, SourceDocument, EvidenceCitation, KnowledgePack, Job, Session, Observation) |
| `domain/ProcedureEngine.kt` | ProcedureEngine — prerequisites, branches, stop/complete nodes, exact-match only |
| `domain/KnowledgeRepository.kt` | KnowledgeRepository — tenant/approval/model/pack filtering, keyword search, citations, conflict + stale detection |
| `domain/EquipmentResolver.kt` | EquipmentResolver — candidates from label text, explicit human confirmation |
| `domain/OperatingMode.kt` | InferenceRouter (mode part) — never claims offline AI from downloaded manuals |
| `data/JobSessionStore.kt` | JobSessionStore — atomic JSON file in app-private storage, corrupt-file quarantine |
| `data/BobAiController.kt` | Vertical-slice orchestration (Android-free, unit tested) |
| `data/MockContent.kt` | **All mock content**, `[MOCK]`-prefixed |
| `ui/*` | Compose screens, adaptive navigation |

## 3. Unknowns and integration risks
- **Hardware not confirmed:** exact Ray-Ban Meta generation, Z Fold model/Android version/RAM/storage. (Diagnostics in Settings shows the phone's model and Android version once installed.)
- **Pilot trade** not chosen — mock content uses a fictional heating unit only to exercise the UI.
- **DAT SDK 0.5.0** API surface in `RealGlassesBackend` is unverified against a real build; a GitHub PAT with `read:packages` is required. BobAI does not yet route glasses camera/audio into its workflows.
- **Meta developer terms:** the README notes the developer preview only allows distribution to testers in your org. Must be re-verified before any commercial pilot.
- **Offline glasses operation** (pairing, auth, camera, audio with internet off) is untested and may depend on the Meta AI app.
- **Local models** (LiteRT-LM, whisper.cpp): not added. Need on-device benchmarks (latency, memory, thermals, battery) on the exact phone first.
- **APP_SECRET in APK** — replace with per-user auth before rollout.
- **Real OCR** not implemented; label text is mock.

## 4. Responsive Compose plan (implemented)
- Width-based, not device-based: `BoxWithConstraints` → `LocalExpanded` when available width ≥ 600dp.
- Compact (cover screen): bottom `NavigationBar`, single-pane screens, assistant control bar (Speak / Repeat / Capture / End).
- Expanded (inner screen): `NavigationRail`, Home in two columns, Assistant as list-detail (workflow left, reference right with Manual / Photos / Notes tabs). Panes are equal width with a 24dp center gap so no control sits on the hinge; bottom actions are split into left/right groups.
- Fold/unfold continuity: session, step, selected source/page, reference tab and draft input live in the activity-scoped ViewModel/store; the assistant list state is hoisted above the layout switch so scroll position survives; nav back stack is saved.
- Follow-up: use `androidx.window` `FoldingFeature` to align panes to a separating hinge in half-opened (book/tabletop) posture.

## 5. First vertical slice (implemented and unit tested)
Confirm equipment → retrieve approved source → answer with citation → save local session.
`VerticalSliceTest` covers it end to end, including restore after a simulated process death.

## Phase 1 acceptance status
| Criterion | Status |
|---|---|
| Home, Assistant, Library, Equipment Confirmation, Downloads, Settings | Done (+ Jobs, Job detail, Summary review, Document viewer) |
| Narrow and expanded layouts | Implemented; verify on device |
| Accessible controls | 48–56dp targets, text+icon statuses, headings, checkbox state descriptions |
| Mock labels | Global MOCK banner, `[MOCK]` titles, mock capture/OCR labelled |
| Empty / error states | No session, no procedure, no evidence, conflict, stale, corrupted, insufficient storage, not downloaded |
| Restored session state | File store + unit test; verify on device |
| No fabricated hardware status | Mock build shows "Mock glasses"; camera/mic reported off because BobAI never opens them |
| Onboarding / device setup screen | **Not done** — next step |

## Device test — 2026-11-16, Galaxy Z Fold7 (SM-F966U), Android 16 / API 36, ~11 GB RAM
Debug build, mock glasses backend. The phone had no active network during the run, so this was also the offline test.

| Check | Result |
|---|---|
| Cover screen (≈411dp wide): Home, Library, Equipment, Assistant | Pass after fixes (title wrap, control bar under system nav bar, overflowing action row, label switch hit target) |
| Mode shown with no network | "Offline reference" with 1 usable pack (correct; offline AI shown as not configured) |
| Equipment: candidate fills fields, confirm required | Pass |
| Prerequisites gate the procedure | Pass |
| "I am not sure", "yeah I think so", "maybe" do not advance | Pass (verified in persisted store) |
| Branch path thermostat → error_code (E1) → igniter | Pass |
| Typed question answered from approved source with citation | Pass (rev B, page 42) |
| Process death (reinstall) restores active session | Pass |
| Unfold mid-session → two-pane workspace, step and source kept | Pass (reference pane opened page 42; rail nav) |
| End session → editable local summary | Pass (wording fixed for unfinished procedures) |
| Glasses hardware, camera, microphone | Not tested — mock build, no glasses connected |
| Half-folded (book/tabletop) posture | Not tested |
