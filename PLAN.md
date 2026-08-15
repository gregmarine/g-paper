# g-paper — Build Plan

**g-paper** is an Android library that embeds a writable/drawable "paper" surface in any app.
It captures pen input and renders content — nothing more. The hosting app owns all data,
persistence, gestures, and app logic. Extracted and redesigned from the Notesprout drawing
engines (`~/git/Notesprout`), which support Generic Android, Onyx (BOOX), and Ratta (Supernote).

## Locked Decisions

| Decision | Choice |
|---|---|
| API scope | Minimal core (stroke capture/render, eraser, templates, pen-activity gate, host content extension point) **plus** selection/drag helpers (lasso capture + drag-move mechanics — the EPD-tricky parts — with callbacks; host owns the data) |
| Modules | Three: `gpaper-core` (generic engine + public API), `gpaper-onyx` (BOOX adapter), `gpaper-ratta` (Supernote adapter) |
| Package / group | `com.symmetricalpalmtree.gpaper` (`.core`, `.onyx`, `.ratta`) |
| Device testing | Build + install demo over adb to connected BOOX / Supernote / generic devices; user verifies ink behavior by eye (EPD overlays are invisible to screencap) and reports back |
| Language / toolchain | Kotlin, JDK 17 (Temurin, pinned via `org.gradle.java.home`), minSdk 29 |
| Component philosophy | The component does not own content. It is paper: it captures pen input and renders what it is told to render. Heavy lifting stays in the host. |

## Architecture Principles (carried from Notesprout, improved)

- **Kill the sibling-copy trap by design.** Notesprout's `RattaNotebookView` is a hand-maintained
  copy of `GenericNotebookView`. In g-paper the shared canvas logic lives once in core
  (a `CanvasPaperView` base); the Ratta engine extends it, overriding only the firmware-ink parts.
- **Engine adapters register themselves.** Core exposes a factory; `gpaper-onyx` / `gpaper-ratta`
  register engine providers (ServiceLoader or explicit registration) so core never depends on them.
  Apps add only the modules for the devices they target. Explicit engine override is supported.
- **Plain data, no serialization opinions.** Strokes are plain Kotlin data classes
  (id, points with x/y/pressure/tilt/time, color, width). The host persists them however it likes.
  Core has near-zero dependencies; the Onyx module carries the BOOX SDK baggage
  (insecure maven repo, jetifier, hiddenapibypass) so generic-only consumers never see it.
- **Host content = renderer extension point.** The host registers content renderers that draw into
  the committed layer (z-ordered relative to strokes) and can provide hit bounds so the selection
  helpers can include host objects. The component invalidates/re-records on request
  (`notifyContentChanged()`).
- **Port the hard-won device knowledge intact**: EPD rules, first-stroke fast-mode pin,
  process-global pen/ink ownership guards, the three Ratta overlay laws, clear-retry ladder,
  hover-based suppressors, registration compensation, pen-activity gate (palm rejection),
  RenderNode committed-content model, erase-path performance rules.

## Working Protocol — Phases, Status, and Context Resets

Each phase = plan → build → test (JVM tests + on-device where applicable) → **commit & push when
green**. Frugal with background agents throughout; work happens mostly inline.

**This file is the project's memory across sessions.** Context is cleared between phases to stay
lean, so PLAN.md must always be self-sufficient:

1. **Status markers.** Every phase carries a status: `⬜ Not started` · `🔄 In progress` ·
   `🧪 Awaiting device verification` · `✅ Complete (commit <hash>)`. Update the marker the moment
   the state changes — never leave it stale at a commit.
2. **Phase close-out ritual** (before the user clears context):
   - All tests green; on-device checks verified by the user where required.
   - Update this phase's status to ✅ with the commit hash, and append a short **Outcome** note
     under the phase: what was built, deviations from plan, and anything the next phase must know.
   - Fold durable, always-relevant knowledge into `CLAUDE.md` (device traps, invariants, build
     facts) — PLAN.md holds status and history; CLAUDE.md holds standing rules.
   - Commit & push. Then the user runs `/clear`.
3. **Phase start ritual** (fresh context): read `CLAUDE.md` and `PLAN.md`, confirm the next
   `⬜`/`🔄` phase with the user, flip it to 🔄, and resolve that phase's open questions before
   writing code. No knowledge from prior conversations may be assumed — if it isn't in the repo
   (or project memory), it doesn't exist.

## Phases

### Phase 0 — Foundation
**Status:** 🔄 In progress
- Gradle multi-module skeleton: `gpaper-core`, `gpaper-onyx`, `gpaper-ratta`, `demo` (app).
- `.gitignore`, seed `README.md`, `CLAUDE.md` (project intelligence, grows each phase), this `PLAN.md`.
- `device-build-install` skill (serials/tiers mirrored from Notesprout).
- Toolchain pinning (AGP, Kotlin, JDK 17), repo hygiene.
- **Test:** `./gradlew build` green with empty modules; demo shell installs and launches.

### Phase 1 — Public API Contract
**Status:** ⬜ Not started
- Design the full public surface: `PaperView` interface, `Stroke`/`StrokePoint` model, tools
  (pen/eraser/lasso/none), listener callbacks (`onStrokeCommitted`, `onStrokeErased`, `onPenLifted`,
  selection/drag events, raw input passthrough), template/background API, page-size handling,
  content-renderer extension point, engine factory/registration, lifecycle contract
  (resume/handoff/release — the host-facing shape of the ownership guards).
- Written as code (interfaces + models + KDoc) plus `docs/api.md`.
- **Test:** JVM unit tests for models/geometry utilities; compiles into all modules.
- **Checkpoint:** user reviews the API before any engine work begins.

### Phase 2 — Generic Engine + Demo v1
**Status:** ⬜ Not started
- Port/redesign `GenericNotebookView` into core's `CanvasPaperView`: stylus-only input capture,
  RenderNode committed-content model, live-stroke drawing, eraser hit-testing (AABB pre-filter,
  throttled redraw), template rendering into page rect, pen-activity gate, host-renderer layer.
- Demo v1: full-screen paper, pen/eraser/width/color controls, clear, stroke feed readout
  (proves the data-out API), one host-rendered sample object (proves the render-in API).
- **Test:** JVM tests (erase geometry, stroke model, bounds); on-device on generic Android
  (tablet/emulator) via adb.

### Phase 3 — Onyx (BOOX) Engine
**Status:** ⬜ Not started
- `gpaper-onyx`: TouchHelper raw-drawing pipeline, EPD rules (fast-mode app-scope pin +
  clear-on-close, handwritingRepaint handoffs, updList sizing), process-global `penOwner` guard,
  `resumeDrawing`/`releaseForHandoff` semantics, toolbar/chrome exclusion rects, barrel-button erase,
  leaked-pin healing hook for the host's Application class, HiddenApiBypass init requirement documented
  and wrapped.
- Demo: engine indicator, same feature set running on the Onyx overlay.
- **Test:** on-device BOOX checklist (first-stroke latency, erase handoff, no ghosting, exclusion
  zones, app-switch release). User eyes-on since screencap can't see the overlay.

### Phase 4 — Ratta (Supernote) Engine
**Status:** ⬜ Not started
- `gpaper-ratta`: `SupernoteInk` binder client + ink map, engine as a subclass of core's canvas view
  (firmware live ink + deferred bake handoff), the three overlay laws, clear-retry ladder,
  hover-based suppressors (barrel/eraser-end/drag), disable-area complement bands + chrome exclusion,
  pen-approach re-arm, registration compensation, process-global `inkOwner` guard.
- **Test:** on-device Nomad/Manta checklist (live ink, deferred bake at boundaries, erase, ladder
  behavior, suppressors). User eyes-on.

### Phase 5 — Selection & Drag Helpers
**Status:** ⬜ Not started
- Lasso capture in all three engines: canvas trail (generic), hardware trails (BOOX `DASH`, Ratta
  `LASSO_DASH`), selection box overlay, tap-to-dismiss, drag-move mechanics (A2 mode on BOOX,
  hover suppress on Ratta) — all firing callbacks with stroke ids + translated geometry; host
  applies the move to its data and confirms. Host content participates via renderer hit bounds.
- Demo: lasso select strokes + the sample host object, drag them, show the callback payloads.
- **Test:** JVM hit-test tests; on-device passes on all three engine types.

### Phase 6 — Hardening & Publishing
**Status:** ⬜ Not started
- Parity audit: shared logic truly shared (no sibling drift), lifecycle/rotation/multi-view checks,
  perf rules verified (no per-stroke re-tessellation, erase throttling).
- `maven-publish` setup so apps can consume via JitPack (or mavenLocal for development).
- Demo polish: per-device capability notes screen.
- **Test:** full build + all tests + a consuming-app smoke test (demo consumes published artifacts
  path, or a scratch consumer project).

### Phase 7 — Code Review
**Status:** ⬜ Not started
- `/code-review` over the whole library (frugal on agents), fix findings, re-run tests on-device
  where the finding warrants.
- **Test:** everything green after fixes; commit & push.

### Phase 8 — Documentation & Release
**Status:** ⬜ Not started
- Full `README.md` (the front door: what/why/quickstart), `docs/integration-guide.md` (per-device
  module setup incl. BOOX repo/jetifier/hiddenapibypass and Supernote zero-dep story),
  `docs/architecture.md`, `docs/api.md` finalized, host-responsibilities guide (gestures, undo/redo,
  persistence), finalize `CLAUDE.md`.
- Tag `v0.1.0`. Commit & push.

## Standing Open Questions (ask as they become relevant)

- Pressure/tilt: capture and deliver on hardware that reports it (Notesprout never wired this) — decide in Phase 1.
- Multi-page semantics: g-paper is one surface; host swaps content for "pages". Confirm in Phase 1.
- Undo/redo: host-owned (component replays via load APIs). Confirm in Phase 1.
- Publishing target (JitPack vs GitHub Packages vs mavenLocal-only) — decide in Phase 6.
- Demo app visual language: e-ink-first like Notesprout's design system, or plain Material-free minimal — decide in Phase 2.
