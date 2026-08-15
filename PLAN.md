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
**Status:** ✅ Complete (commit 0f18be0)
- Gradle multi-module skeleton: `gpaper-core`, `gpaper-onyx`, `gpaper-ratta`, `demo` (app).
- `.gitignore`, seed `README.md`, `CLAUDE.md` (project intelligence, grows each phase), this `PLAN.md`.
- `device-build-install` skill (serials/tiers mirrored from Notesprout).
- Toolchain pinning (AGP, Kotlin, JDK 17), repo hygiene.
- **Test:** `./gradlew build` green with empty modules; demo shell installs and launches.

**Outcome (2026-08-14):** Built as planned, no deviations. Multi-module skeleton at repo root
(core/onyx/ratta libraries + demo app); adapters `api`-depend on core. Toolchain mirrored from
Notesprout (Gradle 8.14 wrapper copied over, AGP 8.11.1, Kotlin 2.2.20, JDK 17 Temurin pin,
compileSdk 35 / minSdk 29). Jetifier + BOOX maven repo deliberately deferred to Phase 3.
`./gradlew build` green; demo shell verified installed, resumed, and rendering on the Supernote
Nomad via adb + screencap. Demo is a plain `android.app.Activity` with a placeholder TextView —
Phase 2 replaces it. Build facts and device rules folded into `CLAUDE.md`; serial/tier table lives
in `.claude/skills/device-build-install/SKILL.md`.

### Phase 1 — Public API Contract
**Status:** ✅ Complete (commit a99dca3)
- Design the full public surface: `PaperView` interface, `Stroke`/`StrokePoint` model, tools
  (pen/eraser/lasso/none), listener callbacks (`onStrokeCommitted`, `onStrokeErased`, `onPenLifted`,
  selection/drag events, raw input passthrough), template/background API, page-size handling,
  content-renderer extension point, engine factory/registration, lifecycle contract
  (resume/handoff/release — the host-facing shape of the ownership guards).
- Written as code (interfaces + models + KDoc) plus `docs/api.md`.
- **Test:** JVM unit tests for models/geometry utilities; compiles into all modules.
- **Checkpoint:** user reviews the API before any engine work begins.

**Outcome (2026-08-14):** Built as planned, reviewed and approved by the user, with one addition
beyond the original bullet: **pen types**. The review surfaced that the single-pen draft ignored the
device-native stroke styles, so `StrokeStyle` (8 abstract values incl. the `DASH`/`CROSS` trail
appearances) was added to `Stroke.style` + `PaperView.penStyle`, with mapping tables and per-phase
implementation bullets (details in the Phase-1 decision entries under Standing Open Questions).
The full surface lives in `gpaper-core` (`PaperView`, pure-JVM models in `model/`, `PaperListener`/
`RawInputListener`, `ContentRenderer`, `GPaper` registry, `Geometry` utils) with `docs/api.md` as the
guided tour; 28 JVM tests green; `./gradlew build` green. Notable contract choices vs Notesprout:
ARGB Int color, batched `onStrokesErased`, listener interfaces with default no-ops, `clear()` fires
no erase callbacks, `clearForContentSwap()` is the page-turn path, `renderToBitmap()` included.
**For Phase 2:** implement `PaperView` in core's `CanvasPaperView`; install the generic provider via
the `GPaper.genericProviderFactory` internal hook (registry is ready and waiting); committed style
renderers first slice per the Phase 2 bullets (richer styles render as `PEN` until implemented).

### Phase 2 — Generic Engine + Demo v1
**Status:** ✅ Complete (commit 90ceadd)
- Port/redesign `GenericNotebookView` into core's `CanvasPaperView`: stylus-only input capture,
  RenderNode committed-content model, live-stroke drawing, eraser hit-testing (AABB pre-filter,
  throttled redraw), template rendering into page rect, pen-activity gate, host-renderer layer.
- Committed style renderers, first slice: `PEN` baseline for all styles, plus the cheap ones where
  practical (`MARKER` translucent flat-cap, `DASH` dashed paint, `CROSS` x-marks along path,
  `FOUNTAIN` pressure-width). Textured
  `PENCIL`/`BRUSH`/`CALLIGRAPHY` may defer (render as `PEN`) — must be hand-rolled portable Canvas
  code, never an SDK dependency in core.
- Demo v1: full-screen paper, pen/eraser/width/color/style controls, clear, stroke feed readout
  (proves the data-out API), one host-rendered sample object (proves the render-in API).
- **Test:** JVM tests (erase geometry, stroke model, bounds); on-device on generic Android
  (tablet/emulator) via adb.

**Outcome (2026-08-14):** Built as planned. `CanvasPaperView` + `StrokeRenderer` +
`GenericPaperEngineProvider` live in `gpaper-core`'s `core/canvas/` (public-but-not-host-API;
Ratta subclasses it in Phase 4 — `drawCommittedContent` is `protected open`, `redrawCommitted`
`protected`). Committed styles first slice as planned: PEN/MARKER/DASH/CROSS/FOUNTAIN real,
PENCIL/BRUSH/CALLIGRAPHY render as PEN. Eraser narrow phase upgraded over Notesprout to
segment-to-segment distance (fast sweeps can't jump strokes between samples; pure-JVM in
`geometry/EraseHitTest`). 51 JVM tests green. Demo v1 in e-ink-first minimal style (decided
this phase), zero non-core dependencies. Verified on the Supernote Nomad (generic engine —
its ink runs the normal View pipeline, so screencap sees it): draw/styles/erase/clear/feed +
host-object move all pass; user eyes-on. **One device-found fix:** palm rejection initially
failed because the palm lands before the pen tip; the gate now counts stylus *hover*
(and the fix uncovered that hover arrives via `onHoverEvent`, not `onGenericMotionEvent`).
`isPenActive` contract updated: writing ∨ hovering + 350 ms tail; tap-like host gestures must
re-check the gate at finger-up. **For Phase 3:** demo already shows `engineId` in its status
line; adb `input` injection can't synthesize stylus toolTypes (UNKNOWN on Supernote) — pen
paths need real hands.

### Phase 3 — Onyx (BOOX) Engine
**Status:** ✅ Complete (commit e9551e7)
- `gpaper-onyx`: TouchHelper raw-drawing pipeline, EPD rules (fast-mode app-scope pin +
  clear-on-close, handwritingRepaint handoffs, updList sizing), process-global `penOwner` guard,
  `resumeDrawing`/`releaseForHandoff` semantics, toolbar/chrome exclusion rects, barrel-button erase,
  leaked-pin healing hook for the host's Application class, HiddenApiBypass init requirement documented
  and wrapped.
- Live style mapping: arm the firmware style per `StrokeStyle` (PEN→0 PENCIL, FOUNTAIN→1, MARKER→2,
  BRUSH→3 NEO_BRUSH, PENCIL→4 CHARCOAL, DASH→5, CALLIGRAPHY→7 SQUARE_PEN, CROSS→4 CHARCOAL as the
  nearest live texture — bake corrects to true x-marks) — `setStrokeStyle` is proven no-restart and
  fast-mode-safe on all five Tier-1 BOOX devices. Verify live↔committed agreement per style.
- Demo: engine indicator, same feature set running on the Onyx overlay.
- **Test:** on-device BOOX checklist (first-stroke latency, erase handoff, no ghosting, exclusion
  zones, app-switch release). User eyes-on since screencap can't see the overlay.

**Outcome (2026-08-15):** Built as planned; full checklist verified eyes-on on the NA5C (first-stroke
latency, all 8 styles live+baked, width/color, eraser, barrel erase, palm rejection, chrome release,
clear, app-switch — all pass). `OnyxPaperView` **subclasses `CanvasPaperView`** (no sibling copy);
core gained protected hooks (`commitCapturedStroke`, `eraseAlong`/`beginEraseSweep`/
`finalizeEraseRedraw`, `markPenDown/Up/InRange/OutOfRange`, `emitRawInput`, `firePenLifted`,
`exclusionRects`). One improvement over the reference: same-frame content swaps coalesce into a
single `handwritingRepaint`. Host entry is `OnyxEngine.register(application)` (bypass + leaked-pin
heal + registration in one). **Three device-found discoveries** (details in CLAUDE.md): (1) BOOX
emits NO pen-approach signal until `TouchHelper.setPostInputEvent(true)` — then the SDK bus posts
`PenActiveEvent`/`PenDeactivateEvent`, now feeding the shared gate; (2) tap-*actions* need a
`PEN_ACTIVE_TAIL_MS` escrow (palm micro-taps beat hover range by ~190 ms; contract in
`PaperView`/api.md, reference impl in the demo — contact size is no discriminator, NA5C reports
none); (3) BOOX hosts need system-bar insets + chrome `releaseRender()` wiring (demo is the
reference). Tilt deliberately captured as 0 on Onyx (per-device scales, no SDK normalizer).
**For Phase 4:** Ratta subclasses the same base; the gate hooks and escrow pattern are ready;
`onPenUpRefresh`/`setPostInputEvent` analogues don't apply (Ratta hover arrives as MotionEvents).

### Phase 4 — Ratta (Supernote) Engine
**Status:** ✅ Complete (commit f464f5b)
- `gpaper-ratta`: `SupernoteInk` binder client + ink map, engine as a subclass of core's canvas view
  (firmware live ink + deferred bake handoff), the three overlay laws, clear-retry ladder,
  hover-based suppressors (barrel/eraser-end/drag), disable-area complement bands + chrome exclusion,
  pen-approach re-arm, registration compensation, process-global `inkOwner` guard.
- Live style mapping from the pen-code sweep: PEN/MARKER/PENCIL→NEEDLE(10), FOUNTAIN/BRUSH→INK(16),
  DASH→4, CROSS→3 (native x stream), CALLIGRAPHY→15 (confirm on-device vs 14; never arm 12 — broken).
  EMR sizing per style from the measured formulas.
- **Test:** on-device Nomad/Manta checklist (live ink, deferred bake at boundaries, erase, ladder
  behavior, suppressors). User eyes-on.

**Outcome (2026-08-15):** Built as planned. `RattaPaperView` **subclasses `CanvasPaperView`**
(sibling-copy trap stays dead); core gained the seams `recordCommitted()`/open `redrawCommitted`,
`bakeAfterCommit()` (deferred-bake), `rendersLiveStrokes`, `isPenDown`, plus the model-side
exclusion split `setExclusionRects` always promised. `gpaper-ratta` is **zero-dependency**
(direct firmware Binder — `SupernoteInk` client; `RattaInkMap` is pure Kotlin with its grey
thresholds pinned by 7 JVM tests). Live style mapping as planned (PEN/MARKER/PENCIL→NEEDLE 10,
FOUNTAIN/BRUSH→INK 16, DASH→4, CROSS→3, CALLIGRAPHY→15 — 15 looked right on the Nomad, 14 remains
the fallback). Host entry is `RattaEngine.register()` (no Application needed). Demo needed only
the registration line — its toolbar sits above the paper, so the complement bands shield it with
no exclusion rects. **One device-found discovery** (now in CLAUDE.md as the fourth overlay law):
the bake handoff must issue `clearAll` *between* node record and `invalidate`, else the clear can
pair with a stale in-flight frame and the just-written ink vanishes until a later repaint (seen
with the demo's input-rate status updates; the reference never hit it); the clear ladder now also
arms after every bake handoff as a ≤450 ms self-heal. Verified eyes-on on the **Nomad**: live
ink, all 8 styles, bake at tool/toolbar boundaries, erase + ladder, barrel erase, palm gate,
clear, app-switch. **Manta verified eyes-on too** (same session, once connected): engine selected
via the min-dim ≥ 1600 split (+3 px registration branch active, session claimed 1920×2399),
registration/bake/erase/styles all pass — both Ratta Tier-1 devices are green.
**For Phase 5:** trail codes are already in `SupernoteInk.Pen` (`DASH` 4 at EMR 300, `CROSS` 3);
drag-move must suppress from the hover stream (law 3); `armOverlayClearLadder()` is the lift-wipe.

### Phase 5 — Selection & Drag Helpers
**Status:** ✅ Complete (commit a32c3ce)
- Lasso capture in all three engines: canvas trail (generic), hardware trails (BOOX `DASH`, Ratta
  `LASSO_DASH`), selection box overlay, tap-to-dismiss, drag-move mechanics (A2 mode on BOOX,
  hover suppress on Ratta) — all firing callbacks with stroke ids + translated geometry; host
  applies the move to its data and confirms. Host content participates via renderer hit bounds.
- Demo: lasso select strokes + the sample host object, drag them, show the callback payloads.
- **Test:** JVM hit-test tests; on-device passes on all three engine types.

**Outcome (2026-08-15):** Built as planned, plus two user-approved scope additions. The whole
selection/drag state machine lives once in `CanvasPaperView` (outline capture, 8 dp tap-vs-outline
extent classifier, box overlay on inflated bounds, drag with hidden-strokes re-record + translated
drag layer), driven through protected seams (`lassoTryBeginDrag`/`lassoOutlineStart`/
`completeLassoOutline`/`lassoDrag*`/`selectionBoxContains`/`onSelectionDragVisual`); pure-JVM
`LassoHitTest` (strokes any-point-in-polygon, host `HitTarget`s polygon-rect) — 72 JVM tests green.
Onyx drives the gesture from the raw callbacks with the firmware DASH trail (A2 fast mode during
drag); Ratta arms the firmware dash pen (code 4, EMR 300) with the law-3 hover drag-suppress and
ladder trail wipes. **Additions:** (1) `ContentRenderer` live-drag pair — exclusion-aware
`draw(canvas, excludedContentIds)` + `drawObject(canvas, contentId)` — so opted-in host objects
truly drag (demo implements it); (2) finger interaction with the active selection (single-finger
drag inside the box, finger tap outside dismisses; palm-gated: `isPenActive` refusal, mid-drag pen
cancel, multi-touch kill, escrowed dismissal). **Three device-found discoveries** (in CLAUDE.md):
Onyx trail render must arm at first *move*, never pen-down; Onyx frames generated during a live raw
contact are withheld and a damage-free pen-up invalidate never repaints (tap-dismiss needs explicit
`handwritingRepaint` + 250 ms retry); Ratta armed-clear flush must move from ACTION_DOWN to the
hover stream (a down-time clear pairs with a frame into the new trail and eats its first dashes —
Nomad-visible, Manta-invisible; latent in the reference). Verified eyes-on on NA5C + Nomad + Manta:
outline/select, drag (pen + finger), host-object live drag, tap-to-dismiss (pen + finger), barrel
erase in lasso, tool-switch dismissal — all pass. **For Phase 6:** the generic engine's software
dashed trail (`rendersLiveTrail` base path) is the one Phase-5 surface not yet eyes-on-verified —
cover it in the parity audit on a generic device (MIP11).

### Phase 6 — Hardening & Publishing
**Status:** ✅ Complete (commit 0f23f5c)
- Parity audit: shared logic truly shared (no sibling drift), lifecycle/rotation/multi-view checks,
  perf rules verified (no per-stroke re-tessellation, erase throttling).
- `maven-publish` setup so apps can consume via JitPack (or mavenLocal for development).
- Demo polish: per-device capability notes screen.
- **Test:** full build + all tests + a consuming-app smoke test (demo consumes published artifacts
  path, or a scratch consumer project).

**Outcome (2026-08-15):** Built as planned. **Parity audit:** the shared-base design is holding —
the whole selection/drag/erase/commit state machine lives once in `CanvasPaperView`, both device
engines drive only the protected seams, and the perf rules verified (committed layer re-records only
on content mutation, 60 ms erase/lasso throttles, `onDraw` blits the RenderNode). One gap found and
fixed: `OnyxPaperView` never re-applied `setLimitRect` after the pipeline opened, so a resize
(rotation, insets change) would clip raw input to stale bounds — now re-applied in `onSizeChanged`
(Ratta already re-ran its whole setup there). **Publishing:** mavenLocal-only (decided this phase);
`maven-publish` on the three library modules, coordinates
`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.0` centralized in `gradle.properties`,
sources jars included; POMs verified correct (core at compile scope, Onyx SDK deps at runtime
scope). **Smoke test:** `consumer-smoke/` is a committed standalone consumer project (own
settings/properties, not part of the root build — see its README) that builds a real host app
against the published artifacts, including the full BOOX consumer story (boox repo, jetifier, label
override, pickFirsts). **Demo:** capability-notes screen (Notes button → per-device sheet: panel
info, registered engines with availability, engine-specific notes, common contracts); while open the
engine parks in `Tool.NONE` so firmware ink can't paint under the overlay, previous tool restored on
close. **Verified eyes-on:** MIP11 (generic engine — the software lasso trail, the last unverified
Phase-5 surface: outline trail, box, drag pen+finger, dismiss — all pass), G102 (Onyx regression +
notes screen + pen-does-not-ink-while-notes-open), plus NA5C/Nomad/Manta installs and notes screen.
G102 reproduced the BOOX install-race disabled-package trap (`enabled=3`, healed with `pm enable`) —
now known beyond NA5C. **For Phase 7:** the Onyx resize fix is the only Phase-6 engine-code change;
review the whole library per the phase plan.

### Phase 7 — Code Review
**Status:** 🔄 In progress
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

### Phase 9 — Gesture Recognizers: Smart Lasso & Scribble Erase (post-v0.1.0)
**Status:** ⬜ Not started
- Two pen-gesture recognizers, ported from Notesprout and improved: **smart lasso** (a quick
  closed pen stroke — non-lasso tool — detected as a lasso attempt) and **scribble erase**
  (a dense zigzag over content erases it). Both are opt-in, default **off**
  (`smartLassoEnabled` / `scribbleEraseEnabled`), active only in `Tool.PEN`.
- Reference: Notesprout `docs/lasso-and-gestures.md` (full spec + thresholds) and
  `apps/notesprout_android/.../notebook/NotebookConstants.kt`. **The reference triplicates the
  recognizer logic across its three sibling NotebookViews — do not copy that.** In g-paper the
  recognizers are pure-JVM `geometry/` code (JVM-testable) and the wiring lives once in
  `CanvasPaperView`'s commit path; device engines contribute only their ink-retraction chrome.
- Recognizer gates (from the reference, re-verify while porting): smart-lasso = velocity
  ≥ 0.5 px/ms + first-to-last closure ≤ 50 dp + winding ≥ 270° around centroid; scribble =
  bbox diagonal ≥ 40 dp + pathLength/diagonal ≥ 3.0 + ≥ 2 direction reversals (noise-filtered).
  Precedence: smart-lasso → scribble → normal stroke; an empty hit test falls through to a
  normal committed stroke (writing "o" over blank paper stays ink).
- Hit tests reuse existing machinery: smart-lasso feeds `LassoHitTest` + the Phase-5 selection
  state machine (fires `onSelectionCreated`); scribble reuses the erase hit-test path
  (whole-stroke, host content via existing hit targets) and fires erase callbacks. Hosts own
  undo, so decide at phase start how the gesture stroke itself is reported (Notesprout
  saves-then-deletes it for undo; g-paper likely never commits it but may need a callback
  carrying its geometry so hosts can offer restore).
- **The hard part is EPD ink retraction:** the recognized gesture stroke's live ink is already
  on-panel and must be wiped — Onyx `handwritingRepaint` + retry (withheld-frame rules), Ratta
  clear-ladder/bake machinery. New territory for the ladder; needs eyes-on on Nomad **and** Manta.
- **Test:** JVM recognizer + hit-test tests; on-device across all three engine types
  (false-positive checks while writing normally are part of the checklist).

## Standing Open Questions (ask as they become relevant)

- ~~Pressure/tilt~~ **Decided (Phase 1):** capture both pressure and tilt in `StrokePoint`; rendering may ignore them initially.
- ~~Multi-page semantics~~ **Decided (Phase 1):** confirmed — one surface; host swaps content for "pages".
- ~~Undo/redo~~ **Decided (Phase 1):** confirmed host-owned; component exposes deterministic load/add/remove.
- **Pen types — Decided (Phase 1, API review):** abstract `StrokeStyle` enum
  (`PEN`/`FOUNTAIN`/`MARKER`/`PENCIL`/`BRUSH`/`CALLIGRAPHY`/`DASH`/`CROSS`) on `Stroke.style` + `PaperView.penStyle`
  from day one, so the host data shape never breaks. The set is the union of what the hardware can
  approximate live, from the device surveys (Onyx: 9 firmware styles verified on 5 devices, Notesprout
  `docs/onyx-pen-tools.md`; Ratta: the 0…31 pen-code sweep — solid 0/5/8/10/11, pressure 1/2/16,
  dash 4, x-stream 3, calligraphy 14/15, 12 broken, 6/7/9/13 dead, 17–31 alias 16 — recorded in
  Notesprout's debug `AndroidManifest.xml` comment). Native codes never surface in the public API.
  Committed appearance is core-rendered and portable; live ink maps per engine (mapping table in
  `StrokeStyle` KDoc and `docs/api.md`), confirmed on-device in Phases 3/4. Both lasso-trail
  *appearances* are host-usable pen types — `DASH` (native live on both) and `CROSS` (Ratta code 3
  native; Onyx approximates live with CHARCOAL, exact when baked) — while the trail chrome the engines
  arm during lasso gestures stays internal. Rendering lands incrementally: engines may render richer
  styles as `PEN` until their committed renderer exists.
- ~~Publishing target~~ **Decided (Phase 6):** mavenLocal-only.
- ~~Demo app visual language~~ **Decided (Phase 2):** e-ink-first minimal — black-on-white,
  flat 2px-bordered buttons (selected = solid black), no Material theming/deps; reads
  correctly on EPD panels in Phases 3/4 and on LCD alike.
