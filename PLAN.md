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
**Status:** ✅ Complete (commit 95fb86c)
- `/code-review` over the whole library (frugal on agents), fix findings, re-run tests on-device
  where the finding warrants.
- **Test:** everything green after fixes; commit & push.

**Outcome (2026-08-15):** `/code-review high` over the three library modules produced 9 verified
findings; all fixed. Core: pointer-source hover was processed twice (the platform delivers the same
MotionEvent to `onHoverEvent` then, unconsumed, to `onGenericMotionEvent` — hosts saw every HOVER
duplicated; now deduped via the shared `isPointerSourceHover` predicate), and a stylus DOWN inside
an exclusion rect now pulses the gate tail. Onyx: EventBus keep rules now ship via
`consumer-rules.pro`; the async limit-rect restore compensation moved inside `openRawDrawing` (all
reopen paths covered); focus-gain routes through `resumeDrawing` (light re-arm after dialogs);
dismissal-repaint retry guarded; `emitRaw` dedup. Ratta: registration compensation/suppressors no
longer run twice per hover sample (offset was doubling to +4/+6 px); cancelled draw contacts wipe
their phantom firmware ink via the gesture-trace ladder (`contactInking` latch);
`flushArmedOverlayClear` enforces the `inkOwner` guard. **One device-found discovery during
verification** (NA5C, now in CLAUDE.md): the SDK's `onPenActive` callback is Handler-marshalled, so
a backlog of stale reports can flush after the raw-thread `PenDeactivateEvent` and permanently
re-latch the palm gate (finger selection drag/dismiss refused) — the callback now contributes
nothing while the bus is subscribed, pulse-tail fallback otherwise. Verified eyes-on: Nomad + MIP11
full pass first round; NA5C full pass after the gate fix. 72 JVM tests green. **For Phase 8:** no
open engine work; documentation only.

### Phase 8 — Documentation & Release
**Status:** ✅ Complete (commit c4f6fa0, tag v0.1.0)
- Full `README.md` (the front door: what/why/quickstart), `docs/integration-guide.md` (per-device
  module setup incl. BOOX repo/jetifier/hiddenapibypass and Supernote zero-dep story),
  `docs/architecture.md`, `docs/api.md` finalized, host-responsibilities guide (gestures, undo/redo,
  persistence), finalize `CLAUDE.md`.
- Tag `v0.1.0`. Commit & push.

**Outcome (2026-08-15):** Documentation-only phase, built as planned, no code changes. README
rewritten as the front door (features, module table, quickstart, doc index, build); three new
docs: `integration-guide.md` (mavenLocal artifacts; per-family setup — generic bare,
Ratta zero-dep, the four BOOX consumer pieces: repo/jetifier/label override/jniLibs packaging —
plus view wiring and the lifecycle table; `consumer-smoke/` cross-referenced as the working
example), `architecture.md` (module graph, shared-base design, rendering model, both engines'
device knowledge incl. all four Ratta overlay laws, gate, testing strategy),
`host-responsibilities.md` (persistence patterns, page turns, an undo/redo replay table, the
full palm-gating contract incl. escrow, content-renderer duties, chrome cooperation, a
"must NOT do" list). `api.md` reframed from Phase-1 contract to v0.1.0 surface (phase jargon
removed, docs cross-linked). CLAUDE.md gained the doc list + the rule that public-surface/build
changes update the matching doc in the same commit. `./gradlew build` green (72 JVM tests).
Tagged `v0.1.0` at the close-out commit. **For Phase 9:** library is released at 0.1.0;
recognizers land as a post-release feature per the phase plan.

### Phase 9 — Gesture Recognizers: Smart Lasso & Scribble Erase (post-v0.1.0)
**Status:** ✅ Complete (commit d7ddd0a)
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

**Outcome (2026-08-15):** Built as planned with three phase-start decisions (user-approved:
smart lasso auto-switches tool→LASSO and restores PEN when the session's selection lifecycle
ends; scribble reports through the existing `onStrokesErased`, strokes only, one batch; the
gesture stroke is never committed or reported) and **one deviation from the planned
precedence** (device-found on the Nomad): scribble-shape is classified FIRST and is exclusive —
real zigzag scribbles routinely satisfy the loop gates too (a spiky coil passing BOTH gate sets
is pinned in a JVM test), so a scribble-shaped stroke is never a smart lasso and an empty-hit
scribble falls to ink, never to lasso. Related recognizer fact: the winding gate cannot reject
closed retraces (nearly any closed path winds ≥360° around its own centroid) — the
empty-hit-test fallthrough is the real guard. Architecture as planned: pure-JVM
`geometry/GestureRecognizer` + one detection point in `commitCapturedStroke`; engines contribute
only ink retraction via the new `onGestureStrokeConsumed` seam (Onyx render-off + dismissal
repaint + retry at contact end; Ratta `releaseGestureTrace` ladder — verified on Nomad AND
Manta). **API addition beyond plan:** `PaperListener.onToolChanged` — a pen tap-away dismisses
at pen-down but restores PEN at pen-up, so reading `tool` inside selection callbacks is unsound;
component-initiated tool changes are now announced (demo toolbar sync is the pattern).
**Biggest device-found discovery (Nomad, via a Notesprout control test):** progressive ink lag
while writing, unrelated to the recognizers — the demo presented app frames mid-writing, and on
Supernote every such frame pays a masking cost against the frozen overlay pixels that grows with
the accumulated unbaked ink; the input-rate raw HOVER stream (which slipped the demo's `!= MOVE`
filter) made it ~100 Hz (Manta measured 1182 frames / 65% janky in one short session). Fix +
new standing rule: hosts present NO frames while `isPenActive` (demo defers status via the gate;
~30–50 frames/session after). 86 JVM tests green (14 new). Verified eyes-on: MIP11, NA5C,
Nomad, Manta — full gesture checklist incl. false-positive writing on all four.

### Phase 10 — Graphite: the textured PENCIL committed renderer (post-v0.1.0)
**Status:** 🧪 Awaiting device verification

Requested by **Paintsprout Onyx** (`~/git/Paintsprout`, branch `onyx`, `apps/paintsprout_onyx/ONYX_PLAN.md`),
whose entire arc 1 is a graphite pencil on white paper. Today `StrokeStyle.PENCIL` falls through to
the `PEN` branch (`core/canvas/StrokeRenderer.kt:61`) — uniform width, no grain. Phase 2 deferred
the textured styles deliberately; this phase pays `PENCIL` off. `BRUSH` and `CALLIGRAPHY` stay
deferred.

- **Real grain in `StrokeRenderer`** for `PENCIL`: graphite texture along the path, and
  **pressure → darkness**. Portable Canvas code only — core has near-zero dependencies and no
  device SDK may enter it.
- **Deterministic grain.** Seed the texture from the stroke id, never from a running RNG: a host
  reloading a page re-renders every committed stroke, and a grain that reshuffles on reload is a
  drawing that changes behind the artist's back. This is the single most important constraint in
  the phase.
- **Pressure carries everything; tilt carries nothing.** `OnyxPaperView.kt:618` hard-zeroes tilt —
  the fleet survey found per-device tilt scales with no SDK normalizer, so tilt is unusable until
  calibrated. Ratta's firmware path supplies tilt, so the renderer may *read* it, but must look
  right with `tilt = 0` because that is what BOOX will always deliver.
- **Draw grain as geometry, not as a mask filter.** Carried knowledge from Paintsprout's Wacom app
  (`apps/paintsprout_android`, `paint/Tool.kt:215` holds its pencil profile): grain and bristle
  marks are drawn as meshes with per-vertex colour because a `BlurMaskFilter` on a software canvas
  measured twice the single largest per-frame cost there. Geometry is also the only way a mark can
  carry strength that varies along its length.
- **`StrokeRasterizer` must match the live view exactly** — the offline door renders through the
  same code, so a host compositing its own content bakes pixel-identical graphite.
- **Live vs. baked mismatch is a finding, not a defect to hide.** Onyx maps live `PENCIL` to the
  firmware's `STROKE_STYLE_CHARCOAL` while the bake is ours; Ratta maps it to `NEEDLE`, which is a
  plain solid line and will disagree more. Measure the pen-up "pop" on both platforms and write
  down what it looks like — the hosts need to know before they decide whether to care.
- Demo gains a pencil control; `docs/api.md`'s committed-renderer status line and the
  `StrokeStyle` KDoc table update in the same commit (public-surface rule).

**Test:** JVM tests for the deterministic parts (grain sampling is pure — same stroke id and points
in, same texture out; a re-render must be byte-identical). On-device: **NA5C** (the Paintsprout
Onyx target) and one mono BOOX panel for contrast, plus a Supernote for the `NEEDLE` mismatch, plus
MIP11 for the generic engine on LCD. Live ink is the user's eye; committed grain screenshot-verifies.

**Publishes:** 0.1.7, then **0.1.8** with the live-mapping fix the device pass found. Both published.

**Outcome (code complete; the panel has not seen it yet).** `PENCIL` renders as graphite:
`geometry/GraphiteGrain.kt` works out which specks of the paper's tooth caught the lead,
`StrokeRenderer.drawPencil` puts them down. 15 new JVM tests, whole suite green,
`build` green, 0.1.7 in mavenLocal.

- **Graphite is spatial, not tonal, and that is the decision the phase turns on.** The mark
  is flecks with bare paper between them; pressure fills in more of the tooth rather than
  tinting a solid line more deeply. Chosen over carrying the Wacom app's tonal five-lane
  mesh across because (a) an e-ink panel with a handful of grey levels dithers any
  continuous grey it is handed, inventing a texture on top of ours, while a mark already
  made of black flecks and white paper needs no dithering at all, and (b) the Wacom mesh was
  judged right *beside a surface model supplying the gaps*, and the host that asked for this
  draws on plain white paper with no surface behind it. One system has to carry the tooth,
  so the pencil carries it.
- **Pressure moves coverage and darkness; width never moves.** Requested that way by the
  host, and it keeps one variable in play on a panel that cannot show two.
- **Tilt stays unread**, as planned. Not because the pen is deaf — BOOX delivers `tiltX`/
  `tiltY` on every raw point, and the NA5C's spans are sane (`-43..55` / `-13..38`) — but
  because the fleet survey found one model reporting roughly a hundred times the others with
  no `getMaxTilt()` to normalize against. Reopening that is **a phase of its own**: per-model
  characterization by hand, unknown models staying at zero. It is what the side-of-lead
  regime (lighter, broader, streakier as the pen lays over) would need, and it is the single
  biggest thing this pencil is missing.
- **The determinism constraint became stronger than the plan asked for.** The plan wanted no
  reshuffle on reload; the grain also must not reshuffle at **pen-up**, because the live
  preview and the bake run through the same renderer and a stroke that re-textures the
  instant the pen lifts makes every mark end in a flinch. So `CanvasPaperView` now mints the
  stroke's id when the contact starts (`pendingStrokeId`) instead of at commit, and the live
  preview seeds off the id the stroke is *about to* be committed with. Stations are placed at
  fixed arc length from the first point, never at input-point indices, so the flecks already
  behind the pen do not move when the next sample lands — pinned by a test that renders a
  10-point prefix and a 40-point stroke and demands the prefix match exactly.
- **`StrokeRenderer.draw` gained a `seed: Int`** (defaulted, so nothing else changed).
  Callers holding a `Stroke` pass `id.hashCode()`; that is the whole public-surface delta.
- **Two flaws the offline preview caught that no test would have.** The lanes of tooth were
  spread endpoint-to-endpoint across the mark, so the two outermost lanes sat exactly on the
  lead's rim and took the full edge falloff — survivable on a broad lead with seven lanes
  between them, ruinous on a fine one where those two lanes *are* two thirds of the mark: a
  hard-pressed fine lead came out patchy grey instead of a firm dark line. Lanes are cell
  centres now. And the fleck diameter floors the apparent width: a mark measures about
  `width + 2 px` however fine the lead, so below ~2 px a lead stops getting finer. Both are
  written into `docs/api.md` because a host picking pencil sizes needs the second one.
- **The demo needed no change** — its Style button already cycles every `StrokeStyle`, so
  `PENCIL` was always reachable and now draws graphite.
- **The device pass found the live-vs-baked pop, and it was a width collapse, not a texture
  difference — 0.1.8 fixes it by remapping the live style.** `PENCIL` armed the firmware's textured
  charcoal (4), which is a *stamp* pen: BOOX multiplies its nominal width by
  `NoteConstant.CHARCOAL_STROKE_WIDTH_EXTRA_SCALE = 5.0` before rendering, because the grain bitmap
  is scaled to the stroke and below roughly 20 px no texture can exist at all. So a 6 px pencil
  previewed about 30 px wide and committed 6 — the mark shrank to a fifth of itself the instant the
  pen lifted, and it read as the *bake* being broken rather than the preview. It now arms the plain
  even line (`STROKE_STYLE_PENCIL`, 0): live and baked agree on the mark's size and differ only in
  grain, so a stroke **gains its tooth** at pen-up instead of shrinking.
  **The general lesson, worth more than the fix:** a preview that lies about width is far worse than
  one that lies about texture, because width is what the hand aims with — and the firmware's
  per-pen-kind width multipliers are not cosmetic. Any future style armed against a BOOX texture pen
  (`CHARCOAL`, `CHARCOAL_V2`, `NEO_BRUSH` ×2.0) has to account for its scale factor or accept the
  same collapse. `CROSS` still arms charcoal deliberately — it is approximating a texture there, not
  a width, and the bake corrects it to true x-marks.
- **Still owed, and the reason this is 🧪 not ✅:** the on-device pass **of the pencil itself**. The live-vs-baked pop
  (Onyx previews `PENCIL` as firmware `STROKE_STYLE_CHARCOAL`, Ratta as a plain `NEEDLE`
  line) has not been looked at, and neither has how the grain reads on a Kaleido panel. The
  constants in `GraphiteGrain` are all named and commented for exactly that pass; the light
  end in particular (a feather touch deposits well under one fleck per tooth, by design) is
  the most likely thing to want moving.

### Phase 11 — Tilt on Onyx: the pencil's other half (post-v0.1.0)
**Status:** 🧪 Awaiting device verification · **Publishes:** 0.1.9 → 0.1.12 (all published)

Opened by Phase 10's own device pass. The pencil's grain was right and its *width* was not: laid
over, the firmware's live ink drew several times wider than the bake, and the mark visibly collapsed
at pen-up. Two wrong turns on the way to the cause are worth keeping, because both are easy to
repeat.

**Wrong turn one — the wrong code path.** The collapse was first blamed on
`NoteConstant.CHARCOAL_STROKE_WIDTH_EXTRA_SCALE = 5.0`. That constant is one BOOX's *own Notes app*
applies before calling in; nothing multiplies on our behalf. And the "texture pens need width ≥ 20"
finding it was reasoned from came from the **NeoPen software renderers**, a different path from the
firmware overlay this engine uses. An inference from a neighbouring code path was stated as if it
had been measured. It had not.

**Wrong turn two — dismissing style 6 on a one-line description.** `CHARCOAL_V2` was written off as
"textured, much thicker than style 4" without a test. It turned out to be the one the artist wanted:
same tilt behaviour, better grain on a Kaleido panel.

**What the measurement actually found.** A hand drew at three deliberate angles with per-stroke tilt
logged, 1300–1600 samples each. `hypot(tiltX, tiltY)` **is degrees from vertical, directly**: a
deliberate upright read a mean of 9.2, a deliberate 45° read 44.3, flat read 75.2. No scale factor,
no fudge. The five-device survey's fear — that tilt is unusable because the fleet reports it on
incompatible scales — is true *across* models and false *within* one that has been measured.

**What landed:**

- `gpaper-onyx` supplies tilt in radians for models on a **measured** allowlist (`NoteAir5C` today),
  and zero for everyone else. Zero is not a degraded mode: it means a pencil held upright, so an
  unmeasured device still gets a pencil. A plausibility ceiling backstops a firmware change that
  moved the scale. **Adding a model is a measurement, never an inference** — the rule this phase
  exists to enforce.
- `GraphiteGrain` widens the mark with tilt, on a curve **fitted to what this firmware does**
  (≈1× at 9°, 2.5× at 44°, 5.5× at 75°) rather than imported from Paintsprout's Wacom pencil, whose
  profile stays thin until nearly flat and blooms far later. Matching the panel beat matching the
  sibling app. Tilt is read **per station**, not once per stroke, because a shading stroke is a hand
  rolling the pencil over as it travels.
- Onyx arms `STROKE_STYLE_CHARCOAL_V2` for `PENCIL`. `TouchHelper`'s entire pen surface is
  `setStrokeStyle` / `setStrokeColor` / `setStrokeWidth` — verified by `javap` on the AAR — so a
  textured live style cannot be had without its tilt response. The answer was to match it, not fight
  it.
- 7 new JVM tests pin the curve, its monotonicity, the clamp past flat, the per-station broadening
  and that tilt does not disturb determinism.

**The general lesson, worth more than the fix:** a live preview that lies about **width** is far
worse than one that lies about texture. Width is what the hand aims with, and the artist reads the
collapse as the *bake* being broken.

**0.1.10 — refitted against the artist's eye, and the flank made paler.** The first curve came from
*estimating* the firmware's live widths and landed at half of what was needed: upright was already
right, tilted needed about twice the girth. Refitting with the origin pinned moved the exponent as
well as the gain — **1× at 9°, ≈4.9× at 44°, ≈10.9× at 75°.** Pinning the origin is not negotiable:
upright is the width the artist chose from the tin, and a pencil that does not draw the width it was
set to is a broken tool rather than a differently-tuned one.

The same release added **tilt lightening**, which the artist asked for on the same look. A lead laid
over spreads the same graphite across a broader band and leaves less of itself on any one peak, which
is why shading with the side of a pencil comes out grey however hard you lean. It reduces *coverage*
rather than fleck darkness — tone in this renderer comes from how many specks of tooth catch, and
darkening the flecks instead would have quietly turned a spatial texture back into a tonal one.
Anchored at 0.45 at full lean, the figure Paintsprout's Wacom app judged against real pencils.

Cost check, since the curve got much steeper: a full-screen broad lead laid flat is ≈47k flecks —
three `drawPoints` calls, and well inside the 240k cap.

**0.1.11 — grain density, set by photograph rather than by eye.** The artist photographed the same
three strokes live on the panel and again after the bake. Measuring them settled two things that
looking could not.

**The width was already right, and the first reading said otherwise.** Thresholding the photos
high enough to segment cleanly made the baked bands look ~30% narrower — but that was the threshold
discarding the bake's pale outer flecks. At a threshold low enough to keep them the extents match
within 4% at all three angles. *A binary threshold cannot measure a texture whose whole nature is
partial coverage;* the honest statistics are threshold-free ones. Total ink mass per unit length
was the one that answered it.

**By that measure the bake laid down ~30% less graphite — and by the same amount at every angle.**
Flat across tilt is what identifies the cause: the upright stroke gets no tilt-lightening at all and
was still 0.68×, so this was baseline coverage, not the lightening being too strong. [EDGE_BARE]
(0.55 → 0.32), [SKATE_DEPTH] (0.28 → 0.16) and [FLECK_PX] (2.3 → 2.55) came up together, chosen so
the factor is **flat across the whole pressure range** — the light-to-hard response had already been
approved and must not move while fixing something else. Most of it was the rim: a mark giving up
half its coverage at the edge spends a lot of its width on almost nothing.

**0.1.12 — the firmware overdraws, and the correction goes in the engine, not the renderer.** A
second photo pair, after the density fix, showed the bake a uniform **0.76× the live width** — stable
at 0.72–0.80 across every sensible threshold and, again, *the same at all three angles*, which
exonerates the tilt curve and points at the base width. `CHARCOAL_V2`'s stamps overhang: its mark is
about 1.3× the width handed to `setStrokeWidth`.

Both paths run through one `penWidth`, so they can only be decoupled inside g-paper — and **which
side to correct is the whole decision.** Widening the bake to meet the firmware would have made
`Stroke.width` a per-device fiction, so a host compositing its own ink through `StrokeRasterizer`
would get a different answer from the one on screen. Dividing the width the *engine* asks for keeps
`Stroke.width` meaning the width of the mark everywhere. It also costs the live ink nothing: a host
that scales its pen widths up to suit hands the firmware exactly the number it got before, so the
EPD's appearance is untouched and only the bake moves.

**0.1.13 — the grain had a direction, and graphite has none.** Photographing the panel's ink beside
ours *under magnification* showed what neither the eye at arm's length nor any aggregate statistic
had: our flecks were combed into short dashes running **along** the stroke, while the panel's speckle
was fine and isotropic. The cause was structural — a fleck is wider than the lattice pitch that
spaces it, so the same lane recurring at the same offset station after station fused its flecks into
a line. Fixed by sliding the whole comb sideways by a random fraction of a lane at every station:
one hash, and the only thing that gave the texture a direction is gone.

The grain also came **finer** in the same release (pitch 2.55/1.7 → 1.65/1.1) because the panel's
speckle is visibly smaller-grained than ours was, and coarse grain reads as gravel. Scaling pitch
and fleck by the same factor leaves coverage — and therefore density — untouched, which is what
made it safe to change texture and density independently.

And [LEVELS] went 3 → 6. Three was argued for on the grounds that more levels turn a spatial texture
tonal; the artist reported the pressure ramp *stepping* where the panel graded smoothly, and the
reason is that **coverage saturates once the tooth is full**, so past that point the darkness ramp is
the only thing still carrying pressure and three steps cannot carry it.

**Two lessons:** an aggregate statistic cannot see structure — mass, extent and coverage were all
being matched while the texture was plainly wrong in a way a magnified crop showed instantly. And a
combed texture at a given coverage does not *look* like an even scatter at the same coverage, so a
density judgement made over a structurally wrong grain is not worth acting on.

**0.1.14 — the grain was made of chains, and chains look like hair.** 0.1.13 removed the texture's
*direction* but not its *connectedness*: the artist still saw it, and named it exactly —
"pipe cleaner". A pixel-level `screencap` of the re-baked strokes (the bake is screencap-visible, so
this needed no camera at all) showed why. **A fleck wider than the lattice that spaces it cannot
help but touch its neighbours**, and once flecks touch they stop being specks and become little
worms a fleck thick and several long — half a millimetre of connected bristle at 300 dpi. The
panel's own charcoal, magnified, is essentially a one-pixel dither: nothing in it is connected.

The same fault explains the report that Paintsprout's Wacom app has always looked like this too.
Its grain is drawn as continuous *lanes* running along the stroke. Different geometry, same
mistake — **graphite laid down as connected geometry looks like hair, whichever way the geometry
runs.**

The cure is that the fleck is now sized **against the pitch, and ramped by darkness**: about one
pitch at the pale end, where specks must stand alone, and over two at the dark end, where they
should flood together so a hard-pressed line is solid rather than a grey mesh. In between, chains
are ~1 px and under the eye's reach. It carries pressure as well, which is welcome — coverage
saturates once the tooth is full, so past that point a growing fleck is the only thing left to
darken with. The whole lattice also came finer (pitch 1.1 → 0.8). Ink mass per tone was held within
about 10% end to end while all of this moved, checked by simulation before shipping.

**0.1.15 — the pipe cleaner, actually found: noise that becomes shape.** 0.1.13 and 0.1.14 each
removed something real about the grain and neither touched what the artist was seeing, which was the
signal that the fault was not in the grain at all. The clue that cracked it was his own: *the Wacom
app has always looked like this too* — and those two renderers share almost nothing. What they share
is that **both drive pencil width from raw tilt.**

A digitizer's tilt jitters by several degrees sample to sample; a hand cannot roll a pencil that
fast. One measured stroke on a NoteAir5C swung 65.7°–85.6° along its length, which through the width
curve is a 9×–13× swing in how broad the mark should be. Fed in raw that becomes geometry: both
edges ripple at the sample rate and the stroke grows a fringe of hairs. Rendering the same stroke
with the reading smoothed put the two side by side and settled it in one image.

The lean is now averaged over ~40 px of arc — **causally**, over the path already covered and never
over samples that have not arrived, so a prefix still renders identically to the whole stroke.
Pressure stays raw on purpose. **The rule worth carrying: noise that becomes *shape* must be
smoothed; noise that becomes *tone* need not be, because there it is doing the same job the tooth
is.** Two tests pin it — a jittering reading may not wander an edge more than a steady one, and a
deliberate roll must still broaden the mark, because smoothing must not flatten the gesture it
exists to render.

**0.1.16 — the pipe cleaner, at last, and it was never the grain.** Three releases had each removed
something real about the texture and the artist reported no change at all from any of them. The
mistake in the search was one of *scale*: the fault was inspected at 10× pixel zoom, where a bristle
is one pixel wide and looks like ordinary speckle, and it is unmistakable **at life size**. Viewing
the same stroke at 1× and 2× showed it in a second — transverse striations combing the mark.

A cross-section of grain is laid perpendicular to the pen's direction, and that direction was taken
from **one adjacent pair of raw samples**. At 2 px sample spacing, 0.35 px of digitizer jitter swings
the computed angle with a standard deviation of ~14°, ranging past ±35°. Every comb of flecks is
rotated by that much, and the error is multiplied by the half-width of the mark — on a lead laid over
at 80-odd px, a 30° error throws its flecks tens of pixels out of line. Rendering one clean path and
the same path with 0.35 px of jitter, side by side, produced a textbook pipe cleaner and settled it.

Now smoothed over ~10 px of arc, causally, exactly as the lean is. **The general rule, stated twice
over in two releases and worth more than either fix: noise that becomes shape must be smoothed. And
when a rendering fault survives redesigning the renderer three times, stop working on the renderer —
the input, or the frame the output is placed in, is wrong.** Also worth keeping: **inspect a texture
at the size it will be looked at.** Every wrong diagnosis in this sequence came from magnifying past
the scale the defect lives at.

**0.1.17 — the ends were chisels.** With the texture finally right, the artist compared the ends
against BOOX's own Notes app: theirs finish in a rounded dome, ours in a straight cut clean across
the mark with corners on it. **A lead meets the paper as a disc**, so the ink ends in a half-round of
the mark's own half-width — walking out past the end and shrinking the half-width along a circle is
that disc, drawn the only way this renderer knows how.

Ordering matters: the touch-down cap is laid **before** the body, so everything already on the paper
keeps its index as the stroke grows, and only the lifting cap moves with the pen — which is what the
real tip does. The prefix-stability test was tightened rather than loosened to say exactly that: the
guarantee covers ink already laid down and stops at the pen.

**0.1.18 — the smoothing filter had a startup transient, and it began every broad stroke with a
hook.** Introduced by 0.1.16 and spotted by the artist on the panel: only the widest strokes did it,
which is the signature of an error multiplied by the half-width. The tangent filter was **seeded
from the first pair of samples** — the single noisiest direction measurement in a stroke — and two
things hang on that seed: the touch-down dome is thrown backwards along it (a half-disc of the
mark's half-width, aimed tens of degrees wrong) and the filter then swings for a window's worth of
travel as it converges, sweeping the first cross-sections through a curve. Seeded instead from a
**chord across the whole smoothing window**, there is no transient to converge from — the seed is
already the answer the filter would have settled on.

**Worth carrying: a filter added to remove noise brings a transient of its own, and at a stroke's
start the transient is on display.** Check the beginning of a mark whenever smoothing is introduced
anywhere in this renderer.

**0.1.19 — the dome was a blob, because a contact patch is an ellipse.** 0.1.17 capped a stroke with
a half-disc of *the mark's own half-width*, which on a lead laid over is a rounded end 7 mm across;
the artist called it glop, and a circle is blunt besides — halfway along one the width is still 87%
of full. The physics corrects it: a tilted lead smears its mark many times sideways but still leaves
the paper over the width of **the lead**, so the cap reaches the lead's radius *along* the stroke
while spanning the mark's half-width *across* it. An ellipse flattened along the direction of travel.
Held upright the two are equal and it is a circle again, which is right, because then the patch is
one. Measurement first ruled out density — the tip was *lighter* than the body, not heavier — which
is what pointed at shape.

**Left for the device:** the middle of the width curve, and grain density now that the texture is
even enough to judge it. Both fits were anchored on an artist's estimate of
*relative* widths at three angles, so 44° is the least constrained point on it.

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
