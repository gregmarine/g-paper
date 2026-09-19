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
**Status:** ✅ Complete (renderer approved on the panel 2026-09-03, as the upright hairline of Phase 12; the tilt half was withdrawn there)

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
**Status:** ✅ Withdrawn in Phase 12 (0.1.24) · **Publishes:** 0.1.9 → 0.1.23 (all published) · The measurement stands; the policy of driving width from the lean was drawn with and rejected by the artist.

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

**0.1.19 — the "glop" was a bead of ink drawn round the cap's outline.** The dome's shape was right;
its *density* was not. [laneCount] rounds a lane count up so a mark thinner than one tooth still gets
grain — harmless in the body where lanes number in the dozens, and badly wrong in a cap, where the
strips narrow to a tooth or two and that one extra lane doubles or triples their candidates. Every
station over-deposits, and because their outermost lanes sit on the cap's edge by construction, the
excess accumulates along the outline as a dark arc. Corrected by asking for coverage per unit of
*area* rather than per lane, and by stopping the cap while its strips are still a tooth wide instead
of chasing them to nothing.

**0.1.20 — and the "glop" was the same transient again, in the filter I had not gone back to fix.**
0.1.18 seeded the *travelled direction* from a chord because seeding from the first sample gave every
broad stroke a hook. The **lean** filter was left seeded from `points[0].tilt` — and a digitizer's
tilt at the instant of touch-down is the least trustworthy reading it produces, the pen being barely
on the glass. Read low, a stroke the artist began with the lead already laid over starts narrow and
dark and flares open across the next few millimetres: an arrowhead with a dense nub on the point.
Reproducing a roll-in synthetically drew that shape exactly and settled it. Seeded now from the
arc-length-weighted mean over the smoothing window, like the tangent.

**The lesson, having now paid for it twice: fix a class of bug everywhere it lives, not where it was
found.** Two filters were introduced together, one seed was corrected, and the other went on
producing a differently-shaped version of the same artifact for two more releases.

**0.1.21 — two tilt effects were compounding, and one of them should not be instantaneous.** The
dark nub at the start of a laid-over stroke turned out not to be a defect in the caps at all: it
predates them (verified by thresholding a 0.1.16 build) and is the roll-in rendering exactly as the
model said it should. Tilt drives width and darkness in *opposite* directions, so a moment of
near-upright is ten times narrower and nearly twice as dark at once — and touch-down is precisely
where a pen is most likely to be caught upright.

The shape of a mark belongs to the instant; the paleness of side-of-lead shading belongs to how the
lead is being *held*. So darkness now follows a much slower lean (150 px) than width does (40 px). A
stroke held flat stays paler than one held upright — the artist's earlier request is intact — but a
stroke no longer flashes dark where the pen passes through vertical. Pinned by a pair of tests that
state both halves.

**The knot at the start of a broad stroke is understood, and not yet cured.** It is not the caps
(it predates them, verified by thresholding a 0.1.16 build) and not the roll-in (softening the
darkness coupling in 0.1.21 changed the artist's stroke by two pixels out of a thousand). It is the
**path**: a pen touches down and the hand settles, a few pixels' excursion before the stroke sets
off. On a fine lead that is invisible; on a lead laid over — ten times broader — the mark folds
across itself there, and graphite laid twice on the same paper composites to solid black. Reproduced
synthetically, it draws the same Y-shaped knot the panel shows.

**0.1.22 fixes it by dropping the arrival instead of smoothing it.** Whatever the hand did while
landing is not a mark, so the renderer finds the last sample within 25 px of arc at which the pen was
travelling more than 60° off the direction the stroke turned out to go, and starts there. A clean
touch-down never travels off-course, so it is trimmed by nothing — pinned by a test.

**Two attempts were needed and the first one is instructive.** Trimming only *backward* steps left
the path rejoining the stroke's line at a **kink** sharp enough to fold the mark over itself all over
again: measured pile-up 10 flecks per pixel against 4 for a clean start, barely better than the 13 it
began at. The rule has to catch the corner as well as the reversal. Also: the trim is found first,
with a chord long enough to see past the arrival, and every filter is then seeded from the *trimmed*
start — otherwise the seeds are measured across the very wobble they exist to be immune to.

**Damping the path does not fix it, and the attempt is worth recording so nobody repeats it.** A
plain running average lags, which shortens every stroke and pulls its end cap inside the mark — a
test for the shape of an end caught that immediately. Adding a trend term to cancel the lag makes the
filter *track* the path faithfully, excursion included: measured peak pile-up stayed at 13–16 flecks
per pixel at every strength from 6 px to 30 px, while the clean baseline got *worse* (4 → 10). **A
filter can lag or it can damp a sustained excursion; it cannot do both.** Reverted rather than
shipped.

What would actually cure it is architectural: a single continuous stroke should deposit **once** on
any given paper — the lead drags, it does not stamp twice — which means compositing a stroke's grain
through one alpha mask rather than fleck by fleck. Crossings *between* strokes would still darken,
which is right. Not attempted; it is a real change to how every style renders.

**A wrong turn on the way, worth recording.** The first attempt reshaped the cap into an ellipse
reaching only the lead's radius — on the theory that a tilted lead leaves the paper over its own
width rather than the smear's. That is sound physics and it looked far worse: on an 85 px half-width
it collapses the cap to a near-straight cut with a spike, and it did nothing about the dark arc,
which was the actual complaint. **A plausible physical story is not evidence; the artifact was
visible in both versions and should have been isolated before anything was reshaped.**

**Left for the device:** the middle of the width curve, and grain density now that the texture is
even enough to judge it. Both fits were anchored on an artist's estimate of
*relative* widths at three angles, so 44° is the least constrained point on it.

### Phase 12 — Hairline: the pencil goes back to an upright, even line (post-v0.1.0)
**Status:** ✅ Complete (commit 58e882f) · **Publishes:** 0.1.24 · Approved on the NoteAir5C 2026-09-03: the artist drew with the hairline in graphite grey (`#505050`, a host colour) and said "I like this" — the first pencil on that panel approved. All three device questions below answered yes by eye.

Opened by the artist sitting down to sketch with the Phase 10/11 pencil for an evening and
rejecting it whole. Neither the live `CHARCOAL_V2` ink nor the bake read as pencil, the marks were
far too broad in an ordinary sketching grip, and the tilt response was named as part of the cause.
The request: take tilt out, make the stroke as thin as it will go, keep pressure.

**Why fourteen measured releases converged on the wrong pencil.** Every one of them measured the
bake against the firmware's charcoal stamp — width curve fitted to it, overdraw measured on it,
density photographed against it — and each round came out measurably right while the whole came
out wrong. Nobody had asked whether the charcoal stamp itself looked like a pencil. **A firmware
style is a target only if the artist has approved the firmware style.** Fit to a reference the
artist has said yes to, never to a style because it happens to be textured. The same lesson Phase
11 already recorded about aggregate statistics, one level up.

**Why the texture went with the tilt.** `TouchHelper`'s whole pen surface is style / colour /
width, and both charcoal styles broaden with the lean inside the firmware. So the only live style
with no tilt response is one with no texture, and `PENCIL` arms the plain even line (style 0) again
— the same style 0.1.8 armed, and that 0.1.9 left for the wrong reason. The live line is exactly the
width the host asked for; the bake adds grain and pressure → darkness at pen-up. The pen-up change
is tone and texture, never size.

**What landed:**
- `gpaper-onyx`: `PENCIL` → `STROKE_STYLE_PENCIL` (0). `REPORT_TILT = false` gates `tiltRadians`
  ahead of the measured-model list, so every model reports zero; the NoteAir5C measurement and the
  allowlist stay in the source as a measurement, not a policy. The `CHARCOAL_V2_OVERDRAW` divide is
  gone with the style it corrected for. Nothing in core's tilt path was removed — `widthFactor`,
  `coverageFactor`, both lean filters — it simply sees zero.
- `gpaper-core`: **a fleck is never wider than the lead that lays it.** Rendering a 1.2 px lead
  offline before it reached a panel (the Phase 10 discipline) showed the darkest 1.6 px flecks
  baking it at more than twice the live line's width — a width lie at exactly the scale the host
  was about to draw at. `fleckPx(level, width)` caps at the lead's width, floored at
  `FLECK_MIN_PX`; `StrokeRenderer.drawPencil` uses it. Above 1.6 px nothing changes. Pinned by a
  test.
- `StrokeStyle` KDoc, `docs/api.md`, `CLAUDE.md` updated; the tilt-fit numbers stay in the docs as
  what was measured.

**Kept, deliberately:** the tangent smoothing, the landing trim, the dome caps, the fleck sizing
against the pitch and the lane phase — all of Phase 11's grain work that had nothing to do with the
lean. And the measurement itself: `hypot(tiltX, tiltY)` is degrees from vertical on the NoteAir5C,
and measuring it again would only find that again. Whoever brings the lean back gets to decide what
it should drive; it will not be the width of the mark on a curve borrowed from a charcoal stamp.

**Left for the device:** whether the firmware's plain line will draw as thin as 1.2 px or has a
floor of its own; whether a pale, pressure-carried hairline reads as pencil on a Kaleido panel; and
whether the pen-up change from a uniform black line to a grained, pressure-toned one is acceptable
now that it never changes size. All three are the artist's eye.

---

### Phase 13 — Raster pages: the page as an image (post-v0.1.0)
**Status:** ✅ Complete (commit 5eb3731) · **Publishes:** 0.1.25 · Opened 2026-09-06 for
Paintsprout Onyx's raster experiment (`apps/paintsprout_onyx/RASTER_PLAN.md`, phase R0), which
owns the walk.

Arc 1 of the Onyx app closed with the artist's verdict that graphite through g-paper feels like
pencil — and two things that do not feel like paper, both because the page is a list of strokes:
the eraser takes marks whole, and a mark can be given back whole. Paintsprout's rule is that the
artist has what paper gives and nothing more. For a sketching pencil a page that *is* pixels is
truer, and this phase gives g-paper such a page, beside the stroke page, chosen per view.

**What landed:**
- `PageMode { STROKE, RASTER }` and `PaperView.pageMode`, default `STROKE`. Setting it drops
  content the way `clearForContentSwap` does (the call is open, so the Onyx overlay release comes
  with it) — a mode belongs to an empty page and is never flipped under ink. Nothing in stroke
  mode moves: every existing test is untouched and green.
- `CanvasPaperView.pageRaster`: one page-sized `ARGB_8888` bitmap, transparent where nothing was
  drawn, allocated on first need (page rect, else the laid-out view) and **dropped, not erased, at
  a swap** — the committed display list holds its own reference to the bitmap it was recorded
  with, so releasing ours keeps the old pixels on the panel until the next page lands, exactly the
  contract strokes have. Erasing in place would blank the panel a frame early. It is a layer over
  the paper, never the paper: white and the template still draw beneath it, so an eraser can
  clear to transparent (Phase 14) and a textured sheet can one day sit under it.
- `drawCommittedContent` blits the image where the stroke loop runs. `commitCapturedStroke` in
  `RASTER` builds the same `Stroke` with the same id (the grain is seeded from it, as the preview
  was), draws it once through `StrokeRenderer` into the image, calls `bakeAfterCommit()` as before
  (one `drawBitmap` re-record now), fires `onStrokeCommitted` as before, and lets the object go.
  `loadStrokes`/`addStrokes` composite instead of keeping — the one-way bake of a stroke page;
  `removeStrokes` is a no-op; `getStrokes()` stays empty.
- `PaperListener.onRasterWillChange(rect)` / `onRasterChanged(rect)`, default bodies, page space,
  around every change. The rect comes from `geometry/RasterDirty` — bounds pushed out by the
  *full* width plus 2 px, snapped outward, clipped to the page, null when off it. Generous on
  purpose: the fleck is jittered off its lattice and drawn as a dot up to the lead's width and
  the fountain nib swells, and a before-image that misses one fleck leaves a mark undo cannot
  take back. Pure Kotlin, proved by `RasterDirtyTest` (9 tests).
- `loadPageRaster(bitmap?)` (copied in at 1:1 from the origin, never stretched — a wrong-sized
  image is a host bug worth seeing), `getPageRaster()` (a **copy**, never the live bitmap: the
  host's save encodes it while the pen keeps going), `copyPageRaster(rect)` (a fresh bitmap even
  for the whole page — `Bitmap.createBitmap(src, …)` hands back the *source* for a full subset).
- `OnyxPaperView.loadPageRaster` goes through `epdRepaintHandoff` like every other content swap.
  The pen path is untouched: the pen-up composite rides `commitCapturedStroke`. Ratta needs no
  override — its deferred bake and `redrawCommitted` guard already cover a raster load.
- `docs/api.md` (Raster pages), `docs/host-responsibilities.md` (persistence + undo patterns).

**Decided at phase start (Paintsprout, 2026-09-06):** `ARGB_8888` over `ALPHA_8` (18 MB at the
NA5C page; colour kept rather than tinted back, so a colour panel is not locked out by storage);
`onStrokeCommitted` keeps firing in raster mode (the host's timestamps and counts come from one
place in both modes).

**What a JVM test cannot reach here, and the device walk must:** the composite landing the same
pixels `StrokeRasterizer` lays, `getPageRaster` being a copy, the mode set clearing content. All
three need a `Bitmap`, which the no-Robolectric rule keeps out of the JVM suite; they are
`screencap`-verifiable on the panel because raster content is ordinary committed content.

**Outcome (NoteAir5C walk, 2026-09-06):** ink accumulated across a minute of sketching, every
committed mark `screencap`-visible, pen-up no slower in the hand, no engine log lines. The eraser
did nothing on the page, as expected before Phase 14.

**Finding — the software and hardware rasterisers do not lay the hairline pencil the same.** The
same rows were `screencap`ed as a raster page and again reopened as a stroke page, and diffed.
The grain is identical, fleck for fleck in the same places; the *tone* is not. The stroke page
(the committed `RenderNode`, GPU) carried about 40 % more ink mass and twice the pixels at
half-dark; the raster page (`Canvas(bitmap)`, CPU) is paler. Same renderer, same seed, same
`Paint` — only the rasteriser differs, and a round dot under 1.2 px is exactly where GPU and CPU
coverage part company. Not proven past the diff, but nothing else in the two paths differs and
premultiplied rounding cannot reach that magnitude. So "the same renderer makes the same pixels"
is true only on the same rasteriser: `StrokeRasterizer` (covers, thumbnails, the raster page)
has always baked paler than the panel, unnoticed under the covers' ×3 shrink.

**Decided (the artist, 2026-09-06): keep the software bake.** Side by side at 1× he preferred
the raster page's tone to the stroke page's. Nothing tuned; the raster composite is what the
CPU rasteriser makes of the approved grain, and a raster book's cover now matches its pages
exactly. Recorded for whoever compares the two modes: they differ in tone as well as in what is
kept, and the clean way to remove that confound — if it ever matters — is to bake the stroke
page's committed layer through a software bitmap too, which is a separate decision.

**Watch:** the bitmap is mutated per pen-up, so its generation changes and the hardware canvas
re-uploads the whole 18 MB texture at each re-record. Not felt on the NA5C in this walk; if
pen-up ever feels slower than a stroke page, that upload is the first suspect. The
`gfxinfo` frame count was not taken cleanly (no reset before the sketching minute) and is owed
by the next walk.

---

### Phase 14 — The pixel eraser: rubbing graphite off a raster page (post-v0.1.0)
**Status:** ✅ Complete (closed 2026-09-06 on the NoteAir5C) · **Publishes:** 0.1.26 · Opened
2026-09-06 for Paintsprout Onyx's raster experiment (`RASTER_PLAN.md`, phase R1), which owns the walk.

Phase 13 gave g-paper a page that is pixels; the eraser still hit-tested a stroke list that, on
such a page, is empty. This phase is the reason the raster page exists: a rubber that takes
graphite off the tooth along the sweep, rather than an object out of a list.

**What landed:**
- `eraseAlong` in `RASTER` strokes the same chained sweep polyline onto the page image with a
  round-capped, round-joined `Paint` at `2 · eraserRadius` in `PorterDuff.Mode.CLEAR`,
  antialiased. Clear, not white: the image is a layer over the paper, and a rubber that painted
  white would leave opaque holes in any sheet that one day sits under it. A one-sample batch
  gets a zero-length line so its round caps leave a disc where the rubber touched. Per batch:
  `onRasterWillChange(rect)` → clear → `onRasterChanged(rect)`; `onStrokesErased` never fires
  (no ids), and host content renderers are not consulted (a rubber, not an object remover).
  Unread page (no bitmap yet) — nothing to rub, nothing announced.
- `geometry/RasterErase`, pure: `batchRect` (the sweep's bounds pushed out by radius + 2 px,
  snapped, clipped — the same generosity as a mark's dirty rect, because the host's undo tile is
  cut from it) and `covers` (distance-to-polyline ≤ radius: the disc swept along the path, which
  is exactly the round-cap/round-join stroke). `RasterEraseTest` (7 tests): the disc, the round
  ends, the un-filled elbow, every covered pixel inside the batch rect, chained batches gapless
  on a flick and gapped without the chain, clipping, off-page null.
- **Live rubbing.** Stroke mode already redraws every 60 ms mid-sweep, but with the raw pipeline
  armed the Onyx panel withholds ordinary frames until pen-up, so the corridor vanished all at
  once. New open hook `presentRasterEraseProgress(rect)` fires after each throttled redraw with
  the union of batch rects since the panel last saw one; the base does nothing (an ordinary
  display just presents the frame), `OnyxPaperView` posts one coalesced
  `EpdController.handwritingRepaint(view, rect)` for the region. Regional rather than full-view
  because the full-view repaint is the per-move flash the class doc forbids. Dropped at sweep
  end; the existing full repaint on `onEndRawDrawing` / `onEndRawErasing` closes the sweep. The
  class-doc line that said erase repaints only at gesture end now records the exception.
- The hardware eraser end needs nothing: `onBeginRawErasing` already runs `beginEraseSweep` →
  `eraseAlong` → `finalizeEraseRedraw`, and the raster branch sits inside `eraseAlong`.
- Stroke mode untouched (348 tests green, the 341 prior ones unchanged). Ratta: the raster
  branch never reaches its hardware; its deferred bake is unaffected.
- `docs/api.md`, `docs/host-responsibilities.md` (accumulate the per-batch tiles into one entry).

**Decided at phase start (the artist, 2026-09-06):** rubbing is shown *live*, as far as the
panel allows — not once at pen-up as the plan's default had it. The eraser radius is stroke
mode's (`DEFAULT_ERASER_RADIUS_PX`, the firmware cursor already at `2 · radius`).

**Outcome (NoteAir5C walk, 2026-09-06):** the rubber lifts graphite along the sweep and leaves
the rest; the pen's eraser end does the same through the existing `onBeginRawErasing` route;
the regional mid-contact `handwritingRepaint` lands with **no full-panel flash**. Two things the
walk found and this phase fixed before closing:

- **A second corridor beside a fast curved sweep.** The SDK reports an erase contact twice —
  every sample streamed, then the whole contact again as one list at pen-up — and the list
  arrived chained to the last streamed sample, so the replay opened with a chord from the lift
  point back to the start. A probe logged both callbacks: identical coordinates, the list one
  sample longer (1256 against 1255; 298 against 297). The Onyx engine now drops the list when
  the contact streamed (`eraseSweepStreamed`) and keeps it only for a contact that did not. This
  is the one change that reaches stroke mode, where the same chord silently took any mark it
  crossed; tests unchanged and green.
- **The artist felt the rubbing as slightly delayed.** Measured before touching anything:
  re-record < 1 ms; frame 14 ms, of which the 18 MB bitmap upload is 4 ms; the panel call
  returns in 2 ms; input arrives 23 ms (median, p90 31) after the SDK's own sample time. The
  60 ms stroke-mode throttle was the only engine-side lever, so the raster eraser got its own
  one-frame cadence (`RASTER_ERASE_REDRAW_INTERVAL_MS = 16`). The hand called the result "a
  little better… I think it might be acceptable"; what remains is the panel's own update, which
  the engine cannot shorten. Stroke mode keeps 60 ms.

`gfxinfo` for the walk's sketching-and-erasing minute: 169 frames, 6 janky — against G6's 26
for a stroke page, the difference being the per-batch erase frames, by design.

---

### Phase 15 — Transform mode: handles and a rotate knob on one host object (post-v0.1.0)
**Status:** ✅ Complete (walked on the Supernote Nomad 2026-09-06, every item passed) · **Publishes:** 0.1.27 · Opened
2026-09-06 for Notesprout SN's shape objects (`apps/notesprout_ratta/OBJECTS_PLAN.md`, decision 8 /
D9 / phase H3), which owns the walk.

The lasso can move a host object and nothing more. SN's six hand-placed shapes need resize and
rotate, and both belong in the engine: the live handles must be drawn where the EPD sees them,
under the same pen-gate and firmware suppression as the selection drag, and a host that draws
its own handles over the paper would fight the pen for every contact. So the engine gains a
second selection-like mode that edits geometry it does not understand — an `OrientedBox` — and
hands it back.

**What landed:**
- `model/OrientedBox(cx, cy, w, h, rotationDeg)` — pure: local/page frames, corners, AABB,
  contains, angle normalisation. Rotation clockwise on screen in `[0, 360)`, `Canvas.rotate`'s
  sense.
- `geometry/TransformGeometry` + `TransformGrab` — pure: `classify` (nearest handle within
  radius, else the knob, else the body), `resize` (anchor the opposite handle, clamp at the
  minimum, aspect lock by the dominant axis on a corner / derived side about the centre on an
  edge), `rotate` (bearing + 90°, snap within 5° of the cardinals), `move`. Every result is a
  function of the box the contact **began** on and the current point — nothing accumulates.
  `TransformGeometryTest` (19 tests): every handle found at 0° and 37°, the knob on the box's
  own up axis, anchors held through a rotated resize, lock + minimum, snap edges.
- `PaperView.beginTransform / endTransform / setTransformAspectLocked / transformingContentId /
  transformBox`; `PaperListener.onTransformChanged` (live, throttled to the lasso cadence, once
  more at the lift, never the same box twice) and `onTransformEnded(before, after)` (exactly once
  per mode, on every exit including the host's own).
- `CanvasPaperView`: one `TransformState`; the committed record excludes the object (the drag's
  exclusion set, widened) and the transform layer draws it live through `drawObject`, then the
  overlay (`canvas/TransformOverlay`: oriented dashed box in the lasso paint, eight axis-aligned
  10 dp handles, a 14 dp knob on a stem 36 dp above the top edge; `round(density)` px outlines
  on integer edges). **The shared lasso entries carry the mode**: `lassoTryBeginDrag` starts a
  transform gesture when the grab region is hit, `lassoDragMove` / `lassoDragFinish` /
  `lassoDragCancel` route to it, `lassoOutlineStart` ends the mode (and marks the contact spent,
  so a tap-sized one never reports `onPaperTapped`), `selectionBoxContains` answers for the grab
  region, `isSelectionDragActive` / `hasActiveSelection` include it — so **the Onyx raw path and
  the Ratta firmware suppress get the mode for free**, neither device module changed. Finger
  contacts go through the same palm-gated entries (a finger tap outside ends the mode after the
  escrow). Exits: `endTransform`, outside contact, tool change, every data-in call
  (`loadPageRaster` included), `setSelection`, an erase contact (`eraseAlong`), `release`
  (silent). `onSelectionDragVisual` brackets a transform gesture like a drag.
- Demo: **Xform** (arms LASSO, begins on the sample object; reads **Done** while active) and
  **Lock**; the sample object is now an `OrientedBox` drawn rotated.
- `docs/api.md` (Transform mode), `docs/host-responsibilities.md` (the persist-on-end pattern).

**Decided at phase start (Notesprout, 2026-09-06):** 0.1.27 (0.1.25/26 went to Paintsprout's
raster pages the same day); the knob is offered for every object — the engine has no notion of a
type to gate it on.

**Outcome (Nomad walk, 2026-09-06, by hand):** handles and knob legible on the panel; a pen
handle-drag with no firmware trail; corner drag free and locked; the knob's snap felt at the
cardinals; body move; pen tap-outside exit; finger handle-drag and finger tap-outside exit; a
tool change exits. One finding, host-side: the demo's own finger handler kept consuming finger
events in transform mode (it yielded only while a *selection* was active), so a finger tap
outside moved the sample object instead of ending the mode. Fixed in the demo and written into
`docs/host-responsibilities.md` as the rule — **yield finger input while
`transformingContentId != null`, exactly as while a selection is active.**

---

### Phase 16 — Lasso eraser: the lasso pointed at the eraser (post-v0.1.0)
**Status:** ✅ Complete (walked and frozen 2026-09-07) · **Publishes:** 0.1.28 · Opened
2026-09-06 for Notesprout SN's lasso eraser (`apps/notesprout_ratta/LOOP_PLAN.md`, decision 1 /
D1–D2 / phase LE1), which owns the walk.

SN wanted og Notesprout's third erase path — a drawn loop that deletes what it takes — and the
host cannot build it: the outline never leaves the engine, and a host that armed `LASSO` and
deleted in `onSelectionCreated` would show the selection box for a frame, paint the wrong trail,
and have to gate the paste-here tap. So the engine gains `Tool.LASSO_ERASER`: the lasso's capture
(`GestureMode.LASSO`, never the drag branch — there is no box) and the lasso's hit rule
(`outlineHits`, now shared with the selection builder so the two can never disagree), completed
as an erase on the scribble-consume recipe (`completeLassoErase`: model removal → one
`onLassoErased(strokeIds, contentIds)` with the `onScribbleErased` forwarding default →
`finalizeEraseRedraw` → `onGestureStrokeConsumed`). Nothing selects, nothing reports
`onPaperTapped`, a loop that takes nothing reports nothing; arming the tool drops any standing
selection. Ratta arms the firmware's `CROSS` pen (the native lasso-eraser x-trail, already
catalogued in `SupernoteInk.Pen`) at `LASSO_TRAIL_EMR` and marks the contact as an outline so the
lift runs the proven `releaseGestureTrace` ladder. Onyx's raw lasso path is widened to both
capturing tools (`capturesOutline`) with the lasso's trail style — mechanical, compiles,
**not hardware-tested at 0.1.28** (no BOOX on the SN arc). No new geometry, no new test surface:
`LassoHitTest` is already covered; the consume path is walked.

**Outcome:** walked and frozen by Notesprout SN's arc 29 "Loop" (LE1–LE4, 2026-09-06/07, on the
Nomad — `apps/notesprout_sn/LOOP_PLAN.md`), armed from a second tap on the armed eraser via a
Point · Lasso sub-bar on all four paper surfaces; the host never repaints from `onLassoErased`.
The Onyx half of this phase remains hardware-untested — no BOOX device carried it through a walk.

---

### Phase 17 — The raster undo: a patch swapped in, not a page loaded (post-v0.1.0)
**Status:** ✅ Complete (closed 2026-09-14 on the NoteAir5C) · **Publishes:** 0.1.29 · Opened
2026-09-14 for Paintsprout Onyx's raster experiment (`RASTER_PLAN.md`, phase R3), which owns the
walk.

Phases 13 and 14 left a raster host one route to an undo: `copyPageRaster` for the before-image
and `loadPageRaster` of a patched whole-page copy to put it back — an 18 MB copy, a whole-page
re-record and a full-panel refresh to take one hairline back. The host chose the engine route
the plan had offered instead.

**What landed:**
- `RasterPatch(rect, pixels)` in the core package: a page-space rect and row-major ARGB pixels,
  exactly `getPixels`' shape, with `bytes` for the host's budget. An `IntArray` rather than a
  bitmap because a host keeps hundreds of these and bounds them by bytes, and an array is an
  honest number with no native allocation beside it.
- `readPageRaster(rect)`: the before-image in that shape, clipped to the page; a page with no
  image yet reads as transparent — the first mark's before-image is nothing, read for free.
- `swapPageRaster(patches)`: each patch's pixels go onto the page and **the array is left holding
  what was there**, so one entry is both the undo and the redo. Row by row through one reused row
  buffer — never a second copy of the page in the middle of undoing a page-wide erase. A rect not
  wholly on the page is skipped with a log line, never clipped (a patch that no longer registers
  with its page is a host bug worth seeing). Allocates the page if it is gone (the redo of a first
  mark undone to nothing). Fires nothing on the listener; one `redrawCommitted`.
- `OnyxPaperView.epdRepaintHandoff` takes a region: the pending full-view flag became a pending
  union rect, so a swap refreshes only the patches it touched and a swap landing in the same turn
  as a content swap unions to the full view. Every existing caller passes nothing and gets the
  full view as before.
- `docs/api.md`, `docs/host-responsibilities.md` (the swap route, and the cell-grid advice for
  sweeps whose batch rects overlap), `CLAUDE.md`, `PaperListener.onRasterWillChange`'s KDoc.
- No new pure geometry, so no new JVM test: the swap is `getPixels`/`setPixels` over a `Bitmap`.
  Its involution is proved on the device by `screencap` — a raster page's committed content is
  capturable, so undo-then-redo diffing pixel-exact against the page before either is the test.
  Stroke mode untouched; the Onyx change is a parameter with a default.

**Outcome (NoteAir5C walk, 2026-09-14):** the regional `handwritingRepaint` at pen-idle takes —
a mark undone and redone, a sweep undone as one step, an undo across a page turn, a page-wide
stroke undone, by arrow and by finger gesture; the artist's verdict on the whole list: *"All
pass."* An adb walk diffed a leaf add/undo/redo pixel-exact beforehand.

---

### Phase 18 — The rubbing eraser: graphite lifted a little at a time (post-v0.1.0)
**Status:** ✅ Complete (closed 2026-09-14 on the NoteAir5C, commit `ba8ee8b`) · **Publishes:** 0.1.30 · Opened
2026-09-14 for Paintsprout Onyx's arc 2 (`ONYX_PLAN.md`, phase E1), which owns the walk.

Phase 14's rubber cut a hole: the sweep stroked onto the page in `CLEAR`, everything within the
radius gone in one pass, the corridor's antialiased edge plainly a side. The raster experiment
closed yes on that eraser, and the artist's notes on it were exact — *it cut holes rather than
lightened*, *the corridor's edge showed*, *the rubber is too large*. This phase is the first two;
the third is the host's radius.

**What landed:**
- `RasterRubbing(liftLight, liftFirm, feather)` in the core package, and `PaperView.rasterRubbing`
  with the artist's defaults: one pass lifts a quarter of what is there at a light touch and three
  fifths firm, interpolated by pressure (an unreported pressure — NaN, negative, over 1 — counts as
  the middle); half the radius, measured in from the edge, is feathered.
- `geometry/RasterRub`, pure: `coverage` (1 in the core, linear to 0 at the radius), `lift`,
  `direction` / `isReversal` (a batch travelling more than 120° against the last one), and
  `rubBatch`, which reads a batch rect of straight-alpha pixels, raises a page-sized byte
  **pass mask** to `max(old, lift × coverage)` per pixel and scales alpha by
  `(1 − new) / (1 − old)`. Colour is untouched: lifted graphite is paler, not a different grey.
- **A pass lifts each pixel once; a reversal starts a new pass.** Batches arrive every frame and
  overlap at their seams — a rubber that lifted twice wherever two batches met would bead every
  fast sweep — so within a pass the mask only ever rises. When the arm comes back the pass is
  dropped and the next lift lands on what the last one left, so dwell counts, as the artist
  asked. The page's alpha already carries every settled pass, so the ratio needs no memory of
  them: one byte mask, cleared only over the rect the pass touched, is the whole state.
- `CanvasPaperView.eraseRasterAlong` is now `getPixels` → `rubBatch` → `setPixels` on the batch
  rect, mean pressure of the batch as the lift's input; the `CLEAR` paint and path are gone.
  Everything announced is unchanged — `onRasterWillChange(rect)` before, `onRasterChanged(rect)`
  after, the one-frame throttled regional repaint between — so the host's grid undo and the Onyx
  live rubbing ride as before. `beginEraseSweep` drops the pass and the direction.
- `RasterRubTest` (13): the lift curve and the unreported-pressure guard; the coverage profile
  with and without feather; one pass lifting the fraction under the core and the edge less than
  the middle; two chained batches not lifting their seam twice; a second pass compounding
  (0.6 × 0.6); a firmer batch raising to its own level, a lighter one changing nothing; a fully
  lifted pixel staying at nothing; reversal versus a corner; the pass cleared over a rect only.
  206 core tests green; the Onyx module compiles unchanged.
- `docs/api.md` (the raster eraser row and the tool line), `docs/host-responsibilities.md`.

**Decided at phase start (the artist, 2026-09-14):** pressure-weighted, a quarter to about 60 %,
dwell counts; feathered edge; the rubber **replaces** the hard eraser — there is no `CLEAR`
mode left to arm.

**Outcome (NoteAir5C, the artist's hand, 2026-09-14):** *"I'm impressed. This eraser feels
fantastic!"* Nothing tuned — the phase-start numbers stand. The host's smaller radius (18 → 12 px)
was judged at the same time: *"The stroke eraser feels better now as well. I think making it
smaller was the right call."* Whether the eraser end reports real pressure was not separated out
by the hand and remains unmeasured; the unreported-pressure guard makes it safe either way.

**0.1.31 (the arc-2 code review, 2026-09-14):** a lift is a ratio and never reaches zero, so a
line rubbed out by eye kept an alpha of one or two for twenty light passes and the host's blank
test called the leaf drawn on. `rubBatch` now lets a pixel go entirely below `GONE_BELOW_ALPHA`
(3); one test added (14).

---

### Phase 19 — Ratta and the raster page (post-v0.1.0)
**Status:** ✅ Complete (closed 2026-09-15 on the Supernote Nomad, the artist's hand) ·
**Publishes:** 0.1.32 · Opened
2026-09-15 for Notesprout SN's arc 43 "Sketch"
(`~/git/Notesprout/extensions/sketch/SKETCH_PLAN.md`, phase K1), which owns the walk.
Note: Paintsprout's `ONYX_PLAN.md` had reserved 0.1.32–0.1.34 for its own abandoned arcs 3/4,
on paper only (0.1.34 was built and then dropped); 0.1.33 went to Phase 20 below and 0.1.34
remains free.

Phases 13–18 built the raster page, the pixel eraser, the patch undo and the rubbing eraser,
and every one of them was walked on a NoteAir5C. **Nothing on Supernote has ever run raster
mode.** The two engines are not the same shape at the point where it matters: Onyx can ask its
panel to refresh a region, and Ratta cannot ask its firmware for anything of the sort — the
daemon owns the panel and an app only presents frames, which is why the frame-silence rule
exists. So this phase is not a port; it is the two places where a number that was right on
BOOX is a *measurement* on Supernote, plus the vehicle to take those measurements with.

**What landed:**
- **The cadence seam.** `CanvasPaperView.rasterEraseRedrawIntervalMs`, a `protected open val`
  read by `throttledEraseRedraw` in place of the constant, which stays 16 — Onyx and generic
  behaviour is byte-for-byte what it was. `RASTER_ERASE_REDRAW_END_ONLY` (`Long.MAX_VALUE`)
  means no mid-sweep redraw at all; the throttle returns early on it and `finalizeEraseRedraw`
  presents the final state exactly once, which it already did for a sweep whose last batches
  fell inside the throttle window (it re-records unconditionally and drops the pending union —
  verified, nothing to fix).
- **`RattaPaperView.rasterEraseRedrawIntervalMs` = 16 ms**, the walk's answer — the same
  number Onyx uses, reached for a different reason (see the Outcome). It opened at 100 ms on
  the reasoning that every mid-sweep redraw here is a whole app frame presented while the pen
  is down; the hand chose one frame anyway, and was right.
- **`RattaEmr.penSize(style, widthPx, floor)`** — pure Kotlin, replacing the private `emrSize`:
  `px * 100` clamped to `[floor, 1200]`, the floor being `EMR_MIN_HAIRLINE` (120) for `PENCIL`
  and `EMR_MIN` (200) for everything else. The sketching pencil is a 1.2 px lead; at 200 the
  firmware previews it as a 2 px needle and the mark narrows at pen-up, and a preview that lies
  about width is the failure the artist reads as the bake being broken. **120 reads right on the
  Nomad**: the preview is the width the bake turns out to be.
- **A tone ladder for `PENCIL`'s live preview** — `RattaTuning.pencilPreviewGrey`, one of the
  four firmware greys (`RattaTuning.Grey`), default `DARK` — taken straight by
  `firmwarePenColor()` instead of the grey `RattaInkMap` picks for the ink's true colour
  (`#505050` → BLACK, which is what it exists to escape). Graphite bakes as flecks with bare
  paper between them and reads far paler than any solid line, and pressure widens that gap: a
  lightly drawn mark bakes to almost nothing while the firmware previews it at full strength.
  So the preview tone is not a boolean but a ladder. `RattaInkMap` is untouched — a
  `PENCIL`-only exception to the mapping, not a shift in it. **Measured: `DARK`**, paired with
  the constant bake below; no rung could carry it alone.
- **`CanvasPaperView.bakePressure(style, pressure)`**, a `protected open fun` returning the
  pressure unchanged, applied in `compositeIntoRaster` — the one place a raster page is
  written, so it covers a fresh mark, a `loadStrokes` bake and an `addStrokes` bake alike.
  `bakePoints` probes before it copies, so an engine that doesn't override the seam allocates
  nothing (raw-bit comparison, so an unreported NaN pressure doesn't fake a change). **Stroke
  mode is untouched**: the `Stroke` handed to `onStrokeCommitted` keeps the measured pressures,
  because that object is the host's data.
- **`RattaPaperView.bakePressure` bakes `PENCIL` at `RattaTuning.pencilBakePressure`**
  (**measured 0.5**; `null` = the real pressure), gated on `firmware` — a
  binder-less Ratta renders its own live ink through the bake's own renderer, which *can* carry
  tone, so there the real pressure is what keeps the two identical. This is the user's decision
  of 2026-09-15 revising Notesprout's pencil decision **for Ratta only**; BOOX and Paintsprout
  keep the pressure pencil.
- **`RattaTuning`** — the measurement door: `rasterEraseRedrawIntervalMs`, `pencilEmrMin`,
  `pencilPreviewGrey` (+ the `Grey` levels and `greyName`) and `pencilBakePressure`, each
  default now being the Nomad's measured answer. Documented in `docs/api.md` as **not host
  API**: hosts leave it alone, it stays only for arc 43's later walks on a real page, and it is
  removed at the arc's close (K8) with each value freezing into a constant. A rebuild per
  candidate is how a judgement of feel gets made against a stale memory of the previous one.
  Two doors were opened during the walk and **removed once answered** —
  `pencilPreviewPressure` (arming the firmware's pressure pen code for `PENCIL`: no visible
  difference, and those codes vary width, the one thing a preview must never lie about) and
  `logEraserPressure` with the `CanvasPaperView.onRasterEraseBatch` seam it was the only user
  of (the eraser end reports real pressure; there is nothing left to watch).
- **A note at Ratta's "needs no override" list** naming `loadPageRaster` and `swapPageRaster`,
  and why each is covered — `clearForContentSwap`'s release under the swap law for the load,
  `redrawCommitted`'s own `pendingBake` guard (record → `clearAll` → invalidate → ladder) for
  the undo swap, which arrives with no swap in front of it and with the undone mark very likely
  still live on the overlay. Read both paths to confirm it rather than inheriting the claim.
- **The demo gains a Raster toggle** and becomes the measurement vehicle: `PageMode.RASTER` on
  a cleared page, `PENCIL` 1.2 px `#505050`, the rubbing eraser at 12 px on the 0.1.30 defaults,
  Pen and Eraser only, no gestures (a hatch is not a scribble), a host-owned undo built the way
  the API document says — before-images on a 64 px grid read once per cell per contact, one
  entry per contact, `swapPageRaster` in both directions — the wall time of every swap at
  `Log.i`, a "Swap pg" door that reads and swaps the whole page, and the four tuning properties
  applied at startup and shown in the (gate-deferred) status line.
- `RattaEmrTest` (5). **207 core tests green, unchanged**; `gpaper-ratta` 12 (7 + 5).
- `docs/api.md` (the per-engine cadence, the `PENCIL` EMR floor, the constant pencil bake, the
  `RattaTuning` paragraph), `docs/architecture.md` (a seam may be a number), `CLAUDE.md`.

**Outcome (Supernote Nomad, the artist's hand, 2026-09-15): every question answered, and one
of them not the way the plan expected.** The walk ran as four rounds on the demo, each
candidate set with `adb shell setprop <name> <value>`, the demo force-stopped and relaunched
(the properties are read in `onCreate`), `dumpsys gfxinfo <pkg> reset`, then a minute of
sketching and rubbing with the hand's verdict taken beside the counter — the verdict being the
one that decides.

| Round | Question | Answer |
|---|---|---|
| 1 | `raster_erase_ms` — the rubbing cadence | 100 ms good · 60 ms better · **16 ms best** |
| 1 | `pencil_emr_min` — the hairline's firmware floor | **120**: the preview is the width the bake turns out to be |
| 1 | Eraser-end pressure — real, or a constant? | **Real**: 2852 batches, 0.06–0.49, median 0.24 |
| 2 | `pencil_grey` — which rung of the tone ladder | BLACK too dark, DARK_GRAY an improvement, GRAY tried — **none of them can work alone** |
| 3 | The pressure pen code (`INK`) for the preview | **No visible difference** — the codes vary width, not tone |
| 4 | `pencil_bake_pressure` — the constant the bake gives up to | **0.5**, with the DARK_GRAY preview: *"spot on"* / *"really good"* |

**The cadence result is the one worth keeping.** The phase opened at 100 ms on the reasoning
that Supernote has no regional-refresh transaction, so every mid-sweep redraw is a whole app
frame presented while the pen is down — the frame-silence rule's forbidden case. The hand went
the other way: *"I really like the 16 ms… this eraser works better on Ratta hardware than it
does on Onyx."* And the counters agree that it *should* be worse — 100 ms: 113 frames, 20 %
janky, p50 13 ms / p99 30; 60 ms: 162 frames, 22 %, the same percentiles; 16 ms: **756 frames,
82 % janky**, p50 20 / p90 28 / p99 40, over ~2800 erase batches in the minute. The frame count
is six times worse and the hand is right, because **the frame-silence rule's cost is the
masking an overlay imposes on frames presented under it — and an erase contact releases the
overlay at `ACTION_DOWN`.** Nothing accumulates during a sweep, so what is left is the panel's
own update, which the Nomad keeps up with. 250 ms and end-only were never walked: there was no
reason to go slower once 16 had won. So both engines sit at one frame, for entirely different
reasons — which is exactly why the seam stays rather than folding back into a constant.

**The pencil took three rounds to ask the right question.** Rounds 2 and 3 were spent trying to
make a pressure-toned bake agree with its preview *from the preview's side*, and could not: the
firmware paints one tone per armed pen, so a soft touch cannot preview softer at any rung of
the grey ladder, and the pressure-sensitive codes vary width rather than tone. **The user's
decision, revising Notesprout's pencil decision 3 for Ratta only:** on Supernote the `PENCIL`
bake ignores pressure, so live and baked agree. The bake was never wrong — it was right about a
tone the panel cannot show while the pen is down, which is a different fault and takes a
different fix. BOOX and Paintsprout keep the pressure pencil; their preview can carry tone, so
they have nothing to give up. At 0.5 against DARK_GRAY the artist judged the pair *"spot on"*
on the panel and again in a Mac screencap at 3×.

**The rest of the numbers.** Undo swaps **14–20 ms** per entry; whole-page swap **79–126 ms**
(read 1–53 ms) on the 1404×1685 page — 9.46 MB; demo PSS **78 MB**. The bake-handoff flash on
an undo was noticed and **accepted** (M4). `RasterRub`'s pressure-weighted lift is doing real
work on this hardware, and the unreported-pressure guard is not carrying it.

**Frozen, and the doors that closed.** The four measured values are now `RattaTuning`'s
defaults — cadence 16 ms, EMR floor 120, preview `Grey.DARK`, bake pressure 0.5 — and the two
doors answered with a "no" are **gone**: `pencilPreviewPressure` (with its `livePenCode`
branch; `PENCIL` arms `NEEDLE` unconditionally again) and `logEraserPressure`, along with the
`CanvasPaperView.onRasterEraseBatch` seam it was the only user of, and both demo properties.
`RattaTuning` itself stays, holding the four measured numbers as a door for arc 43's later
walks on a real page (K5/K6), and **is removed at the arc's close (K8)**, each value freezing
into a constant. *(That close is Phase 21 below, 0.1.34: no question was re-opened on K5 or K6,
and the four values are constants where they are read.)*

**No JVM test for the end-only sentinel or the bake seam.** `throttledEraseRedraw` and
`bakePoints` are private and live inside an Android `View`; extracting a one-line comparison
and a two-branch expression into pure modules to test them would be inventing the modules to
justify the tests. Both are covered by the walk. `RattaEmrTest` (5) pins the part that *is*
pure.

**Held for the publish step.** Paintsprout Onyx consumes the **published** 0.1.31 artifacts, so
its 203 JVM tests are not a gate here — the core change is additive (two `protected open`
members and one private constant) and the compile check belongs to the publish step, with
`publishToMavenLocal` and the SN re-pin held until Fable has read this diff and these numbers.
*Still held at the time of writing:* Phase 20 landed on top of this one before either was
published, so the two publish together at 0.1.33.

---

### Phase 20 — A mark says where it landed (post-v0.1.0)
**Status:** ✅ Complete (closed 2026-09-15 on the Supernote Nomad, the artist's hand — Notesprout SN arc 43 K6) ·
**Publishes:** 0.1.33 · Opened 2026-09-15 for Notesprout SN's arc 43 "Sketch"
(`~/git/Notesprout/extensions/sketch/SKETCH_PLAN.md`, phase K6), which owns the walk and the
numbers.

Two things Paintsprout wrote down while building on the raster page (`ONYX_PLAN.md`, items 1
and 2), and both of them are the same mistake seen from two sides: **the engine was telling the
host about a change in a shape that suited the engine rather than the host.**

The first is the pen-up composite. A mark announced one rect — its bounding box — so a
corner-to-corner hairline announced the whole page, because a bounding box is a bad model of a
line. What a host pays is the *announced* area, not the ink's: on a 1404×1685 Nomad page that is
every 64 px cell of the page read on the main thread inside the pen-up callback, about 9.5 MB
(≈19.7 MB on a Manta), and two such strokes fill an undo budget bounded by bytes, as the API
document tells hosts to bound it. The eraser had already been announcing per batch since 0.1.26;
the composite was the one place still speaking in boxes.

The second is `loadPageRaster`. It fired a whole-page will-change/changed pair, so every host
carried a flag to ignore its own load — `loadingRaster` in Paintsprout's `SketchbookActivity`,
the same in SN's `SketchActivity` — and that flag only works because these callbacks happen to
be synchronous. A correctness argument resting on an implementation detail nobody promised is a
bug with a date on it. `swapPageRaster` has been silent since 0.1.29 for the right reason, and
the reason covers the load too.

**What landed:**
- **`RasterDirty.along(points, width, pageWidth, pageHeight, maxSpanPx, maxRects)`** — pure
  Kotlin beside `of`, which it calls once per run, so the pad (`width` + `MARGIN_PX`), the
  outward snap and the page clip are one rule with one test. The polyline is walked in order and
  cut where taking the next point in would push the run's **unpadded** span — the larger of its
  width and height — past `maxSpanPx`. **The closing point belongs to both runs**: it is the last
  point of one and the first of the next, so the segment across the boundary lies wholly inside
  the next rect and consecutive rects overlap by at least the pad around it. Cutting *between*
  two points would leave that segment announced by neither, which is the one failure the object
  exists to prevent. **Coverage is the invariant** — every pixel a mark can touch lies in at
  least one returned rect — and the span, the cap and the dropping are savings taken only where
  coverage is not at stake. A single segment longer than the span is still one run (two points
  cannot be cut); at `maxRects` the last run absorbs the whole tail, so a pathological polyline
  degrades to the old behaviour rather than to a missing piece of a mark; a run that clips off
  the page is dropped, and a mark wholly off it announces nothing; no points, no rects; and a
  mark that never reaches the span comes back as exactly the one rect `of` always gave it — an
  equivalence pinned by a test, because a word must not start costing more than it did.
- **`CanvasPaperView.rasterDirtyAlong(stroke)`** replaces `rasterDirtyOf` (the single-rect form
  had no other caller), reading the same `pageWidth`/`width` fallback and the two new private
  constants `RASTER_DIRTY_SPAN_PX` (256 — a few of the 64 px before-image cells the API document
  recommends) and `RASTER_DIRTY_MAX_RECTS` (64). Both are **candidates the K6 walk measures.**
  `commitCapturedStroke` and `addStrokes` fire one `onRasterWillChange` per run, **all of them
  before any pixel moves**, then the composite, then `bakeAfterCommit` / `redrawCommitted`, then
  `onStrokeCommitted` (commit only), then one `onRasterChanged` per run in the same order.
  `addStrokes` concatenates the runs of every stroke around its one composite.
- **`loadPageRaster` is silent** — the `endActiveTransform` / `clearSelection` / copy-in /
  `redrawCommitted` sequence is untouched, only the announcement is gone. `loadStrokes` (the
  RASTER bake), `addStrokes` and `clear()` stay reported: SN's "Bring in ink" door fills its undo
  entry through `addStrokes`' will-change, and a bake or a clear changes pixels a host may well
  want a before-image of. The rule, stated in the `PaperListener` KDoc, in `PaperView`'s and in
  both documents: **a change the host made itself (`loadPageRaster`, `swapPageRaster`) is not
  announced; a change the pen or a bake made is.**
- `RasterDirtyTest` +8 (215 core, from 207): empty in, empty out; one point equals `of`; a short
  mark equals `of` exactly; a long line cut into runs each inside `span + 2 × pad`, consecutive
  rects overlapping by at least the pad; a corner-to-corner hairline on a 1404×1685 page covered
  segment by segment at quarter-pixel steps for **under a quarter of the page**; the cap reached,
  the tail absorbed and coverage still holding; off-page runs dropped and a wholly off-page mark
  silent; a stroke doubling back staying inside the span budget. Coverage is checked the honest
  way — every sample's padded box, clipped to the page, must lie inside **one** rect.
- `docs/api.md` (the raster rows and two new paragraphs), `docs/host-responsibilities.md` (the
  undo paragraph: several will-change calls per contact is now the rule for marks as well as
  sweeps, and the `loadingRaster`-style flag is gone), `docs/architecture.md` (the purity split
  — the run rule is pure, so coverage is proved rather than eyeballed), `CLAUDE.md`, and the
  0.1.33 version pin in `README.md` / `docs/integration-guide.md` / `gradle.properties`.
- No engine-module change: `gpaper-onyx` and `gpaper-ratta` compile untouched, and the demo's
  host-side undo (which reads its before-images from `onRasterWillChange` onto a 64 px grid,
  once per cell per contact) needed nothing — it already accumulates per contact, and it never
  called `loadPageRaster`. **215 core / 12 ratta / 0 onyx, all green; `:demo:assembleDebug`
  builds.**

**The numbers this phase does not have.** Everything above is reasoning and JVM tests. What it
is worth on a panel — cells read and entry bytes for a corner-to-corner hairline before and
after, the pen-up main-thread milliseconds, and that undo is still an involution across several
runs of one mark — belongs to the arc 43 K6 walk on the Nomad, along with whether 256/64 are the
right pair.

**Outcome (Supernote Nomad, the artist's hand, 2026-09-15, through Notesprout SN's sketch
face — one corner-to-corner pencil hairline on a fresh 1404×1685 page, undo, redo):** with the
host reading before-images on its 64 px grid, the mark cost **550 cells / 8 985 600 B / 40 ms on
the main thread at pen-up on 0.1.32, and 134 cells / 2 190 336 B / 18 ms on 0.1.33** — 4.1× fewer
cells and bytes, the undo and the redo swapping 134 tiles both ways and the line coming back whole.
Two page turns afterwards recorded no undo entry and dirtied nothing, so the host dropped its
`loadingRaster` flag the same day. **The 256 px span and 64-rect cap stand as measured** — the
diagonal's runs deduplicate on the host's grid to about a quarter of the box, and nothing in the
walk argued for a finer cut. Review before publish (Fable) changed one thing: the two constants
are `internal`, not public API. Paintsprout Onyx's 203 stayed green on its 0.1.31 pin; its
`ONYX_PLAN.md` watch-list items 1 and 2 are struck through, and its own `loadingRaster` goes with
its pin jump, when it chooses to make one.

---

### Phase 21 — The door closes (post-v0.1.0)
**Status:** ✅ Complete (2026-09-16) · **Publishes:** 0.1.34 · Opened 2026-09-16 for Notesprout
SN's arc 43 "Sketch" (`~/git/Notesprout/extensions/sketch/SKETCH_PLAN.md`, phase K8 — docs and
freeze), which is the close Phase 19 promised this to.
Note: Paintsprout's `ONYX_PLAN.md` had reserved 0.1.32–0.1.34 for its own abandoned arcs 3/4 and
had built-and-dropped a "Phase 21 / 0.1.34" on paper only; the number is this phase's.

Phase 19 measured four Supernote raster-page numbers on the Nomad and then left them behind
`RattaTuning`, a `setprop` door, on the reasoning that arc 43's later walks would be on a real
page rather than the demo's and might want to re-open one. **K5 and K6 walked the real page and
re-opened none of them.** So the door closes on the terms it was opened on.

The rule it leaves behind is the one worth keeping: **a measurement door is temporary by
construction.** Opening one is right — a judgement of *feel* made against a stale memory of the
previous candidate is no judgement, and a rebuild per candidate is exactly how that happens.
Keeping one after the measuring stops is not: a mutable global holding a number the hand has
already settled is a behaviour nothing in the tree can be reasoned about from, it survives into a
release as a public object on a module that promises nothing about it, and its existence invites
a host to "configure" what was measured. **The number is worth nothing without the walk that
produced it** — so each value freezes into a constant *where it is read*, carrying its
measurement in its KDoc, and re-opening a question means another walk and another door.

**What landed** — **no behaviour change anywhere**: the same four numbers, the same arming, the
same bake.
- **`RattaTuning` is gone** (the whole file), along with its `Grey` levels, `greyName`, and its
  duplicate of core's `RASTER_ERASE_REDRAW_END_ONLY` sentinel (core keeps its own, private, which
  is why the copy existed at all).
- **`RattaPaperView` gains three private constants**, each with the walk's story condensed into
  its KDoc: `RASTER_ERASE_REDRAW_MS` (16 — 100 ms good / 60 better / 16 the hand's clear choice
  at six times the janky frames, because an erase contact has already released the overlay),
  `PENCIL_PREVIEW_GREY` (`SupernoteInk.Color.DARK_GRAY` — the `PENCIL`-only exception to
  `RattaInkMap`, kept because the firmware paints one tone per armed pen so no rung of the ladder
  could carry it alone), and `PENCIL_BAKE_PRESSURE` (0.5 — the bake gives up its tonal range
  because nothing on the preview's side can be made to vary; Ratta only, stroke mode untouched).
  `rasterEraseRedrawIntervalMs` and `bakePressure` are one line each now; the long-form reasoning
  moved to the constants so the number and its measurement sit together.
- **`RattaEmr.penSize(style, widthPx)` loses its `floor` parameter** and reads
  `EMR_MIN_HAIRLINE` (120) directly. The parameter existed only so the door could move the floor
  on a running device, and with the door gone nothing varies it — a parameter kept for its tests
  is the module inventing work to justify them. The `coerceIn(0, EMR_MAX)` that absorbed a
  nonsense `setprop` goes with it: nothing can set a nonsense floor any more.
- **The demo drops the four `debug.gpaper.*` reads**, `getprop`, `tuningLine` and the tuning text
  on the raster status line. Everything else about the raster vehicle — the toggle, the host-owned
  undo, the swap timer — is untouched.
- `docs/api.md` (the `RattaTuning` section becomes a short note that the door closed and there is
  nothing to migrate, since it was never host API), `CLAUDE.md` (a standing rule for the door's
  life cycle; the Phase 19 cadence bullet now names the constant), the Phase 19 entry above (one
  line pointing here), and the 0.1.34 version pin in `gradle.properties` / `README.md` /
  `docs/integration-guide.md`.
- `RattaEmrTest` keeps its five tests: the floor-parameter test, which tested the door, is
  replaced by one pinning the two floors and the ceiling as the measured numbers they now are.
  **215 core / 5 ratta-emr within 12 ratta, all green; `:demo:assembleDebug` builds.**
  `gpaper-onyx` is untouched and `gpaper-core` unchanged.

**Nothing to verify on a panel.** Every value is the one that was already running, so the walk
that validates this phase is the one that produced the numbers. The host pin jump to 0.1.34 is
SN's arc 43 K8, after `publishToMavenLocal`.

### Phase 22 — The pencil bakes upright on Ratta (post-v0.1.0)
**Status:** ✅ Complete (2026-09-17) · **Publishes:** 0.1.35 · Opened 2026-09-17 for Notesprout
SN's Manta walk of NSE · Sketch (branch `sketch-manta` there).

On the Manta the baked `PENCIL` hairline landed **10–15× wider** than the firmware's live line.
The first reading was wrong: the bake is the same pixels on both panels, so the EMR size was
suspected, and a measurement door (`RattaTuning`, pencil EMR + bake pressure) was re-opened for
the walk. The artist's "10–15×" is what corrected it — no EMR mismatch is that large, and
`GraphiteGrain`'s lean profile is exactly that large (1× at 9°, ≈4.9× at 44°, ≈10.9× at 75°).
**The firmware's live line cannot widen with lean**, so a hairline drawn at an ordinary writing
angle previewed as a hairline and baked with the flank of the lead. Both Supernotes deliver
`AXIS_TILT`; the Nomad's K1 walk simply happened at an upright grip.

**What landed**
- **Core: `bakeTilt(style, tilt)`**, a `protected open` seam beside `bakePressure` and applied at
  the same one place (`bakePoints`, the raster composite). Identity by default — Onyx and the
  generic engine keep the leaning pencil, because their preview can show it. Stroke mode is
  untouched: the kept `Stroke` still carries the tilt the digitizer reported.
- **Ratta overrides it to 0 for `PENCIL`**, gated on `firmware` exactly as `bakePressure` is.
- **The door opened and closed inside the phase** — `RattaTuning` was never published in a
  release a host pinned; the EMR floor (120) and bake pressure (0.5) were not moved.

**Verified by hand on both panels (2026-09-17):** Manta and Nomad, upright and leaned, the bake
is the line that was previewed. 215 core / 12 ratta green.

### Phase 23 — The pencil previews its tone on Ratta (post-v0.1.0)
**Status:** ✅ Complete (2026-09-17) · **Publishes:** 0.1.36 · branch `pencil-tones` · Opened 2026-09-17 for
Notesprout SN's arc 44 "Pencils" (branch `pencils` there; plan + ledger in
`extensions/sketch/PENCILS_PLAN.md`, phase T1).

NSE · Sketch is growing fifteen greyscale pencil shades (`#000000 … #EEEEEE` in `0x11` steps) and
five lead sizes (1.2 / 2 / 4 / 7 / 12 px). The bake already takes both from `penColor` /
`penWidth`. The preview does not: `RattaPaperView.firmwarePenColor()` answers
`PENCIL_PREVIEW_GREY` (DARK_GRAY) for every pencil, so a black lead and a pale one would preview
alike. **The artist's decision (2026-09-17): the pencil preview maps to the nearest usable firmware
tone.**

**Planned**
- Pure `RattaInkMap.pencilPreviewFor(argb)` with **its own** thresholds — `firmwareColorFor`'s are
  "do not revisit" and stay untouched; a pencil bakes at pressure 0.5 through grain and reads
  lighter than its nominal colour, so it needs its own ladder. Must answer DARK_GRAY for `#555555`
  (K1's "spot on") and never LIGHT_GRAY (near-invisible). Starting ladder: levels 0–2 → BLACK,
  3–9 → DARK_GRAY, 10–14 → GRAY; the hand on the Nomad settles it.
- `firmwarePenColor()` routes `PENCIL` through it; the `PENCIL_PREVIEW_GREY` constant goes.
  `bakePressure` 0.5, `bakeTilt` 0, both EMR floors and the erase cadence **do not move**.
- `RattaInkMapTest` pins the ladder.
- Demo: the raster toggle gains shade + size cyclers (the walk surface), and each of the five lead
  sizes is rendered to a PNG before it reaches a panel (`CLAUDE.md`'s rule).
- A measurement door only if the first ladder misses, opened and closed inside the phase.
- `docs/api.md` + `CLAUDE.md` in the same commit as the code. Paintsprout Onyx green on its pin
  before publish.

Opus writes on Fable's brief; Fable reviews the diff before publish.

**Landed (2026-09-17 — as first written; the walk below moved one number)**
- **`RattaInkMap.pencilPreviewFor(argb)`** — pure, beside `firmwareColorFor`, with its own two
  private thresholds: `PENCIL_BLACK_MAX_LUMA` 42.5 (the midpoint of shade levels 2 and 3) and
  `PENCIL_DARK_GRAY_MAX_LUMA` 161.5 (the midpoint of 9 and 10), so levels 0–2 → BLACK, 3–9 →
  DARK_GRAY, 10–14 and anything lighter → GRAY. A boundary sits between two shades rather than on
  one, so no level is a rounding away from the other side. `firmwareColorFor`, its 85/187/222 and
  their "do not revisit" KDoc are **byte-for-byte untouched** — this is a second ladder, not a
  shift in the first. It never answers LIGHT_GRAY: that code renders near-invisibly, which is
  consistent for a solid line of near-white ink and wrong for a pencil, because the palest lead
  must still be *watchable while it is drawn*. `#555555` (the new default) and `#505050` (arc 43's
  lead, the one the hand actually paired with the 0.5 bake) both still answer DARK_GRAY.
  **Both numbers are starting values with a reason, not measurements**, and the KDoc says so.
- **`firmwarePenColor()` routes `PENCIL` through it** and **`PENCIL_PREVIEW_GREY` is gone.** Its
  KDoc's still-true half — the Phase 19 history, why BLACK was too dark, one tone per arming — is
  now in `pencilPreviewFor`'s KDoc and in `firmwarePenColor`'s; `PENCIL_BAKE_PRESSURE`'s KDoc, the
  companion's door-closing note and `CLAUDE.md`'s Phase 21 bullet all re-point at the ladder rather
  than at a constant that no longer exists. Nothing else moved: `bakePressure` 0.5, `bakeTilt` 0,
  `RattaEmr`'s two floors and `RASTER_ERASE_REDRAW_MS` are as they were. **No door**: the ladder is
  two constants in a pure object, and if the walk moves them it moves them in source.
- **Checked rather than assumed:** `penColor`'s setter already calls `rearmPenIfLive()` (as
  `penWidth`'s and `penStyle`'s do), so a shade picked mid-page re-arms the firmware pen on the
  next mark with no tool boundary and nothing had to be added.
- **`RattaInkMapTest` +5 (17 ratta, from 12):** all fifteen shade levels pinned one by one to the
  code they arm; both boundaries pinned from both sides, at the shade *and* at the luma; the
  `#555555`/`#505050` → DARK_GRAY pairing pinned **together with** `firmwareColorFor("#505050")` →
  BLACK, so the test states what the two ladders disagree about; LIGHT_GRAY refused across **all
  256 greys**, not just white; alpha ignored. The existing seven are untouched.
- **The demo's raster page gains a shade cycler and a lead cycler** (raster-only chrome, built and
  styled exactly like the stroke page's style/width/colour cyclers): shade steps the fifteen levels
  from 5 (`#555555`), lead steps 1.2 / 2 / 4 / 7 / 12 from 1.2, each tap wraps, each face carries
  its current value, each pushes straight into `penColor`/`penWidth` and reports the armed pencil
  on the gate-deferred status line. They replace the fixed `#505050` at 1.2 px. **The demo is still
  one tool** — no style choice on a raster page. The armed *firmware tone* is deliberately **not**
  reported: `RattaInkMap` is `internal` to `gpaper-ratta` and nothing public exposes it, and
  widening the public API to light up a status line is not a trade worth making.
- **`PencilRenderHarness` (`gpaper-core/src/test`, +1 core → 216)** — `CLAUDE.md`'s "render a new
  lead size to a PNG before it reaches a panel", which arc 44 triggers five times over. It drives
  the real `GraphiteGrain` and writes a contact sheet of the five leads × three shades × straight
  and curved marks, at 1× and at a nearest-neighbour 3×, plus a per-lead 3× crop, into
  `gpaper-core/build/pencil-renders/` (never committed). **It cannot drive `StrokeRenderer`** —
  that is `android.graphics` and an Android module's unit tests compile against `android.jar`,
  which has neither Skia nor AWT nor ImageIO — so the fleck loop is a hand-mirror of
  `drawPencil` (same levels, same `fleckPx(level, width)`, same `levelAlpha`, discs for round
  caps) onto a small self-contained rasteriser and PNG writer built on `java.util.zip` alone:
  **no new dependency**, in a repo whose whole test stack is JUnit 4. That makes it a *third* rasteriser, so
  its images are evidence about **geometry** and never about tone — 0.1.13 already measured Skia's
  own two paths ~40 % apart in tone on identical flecks. It also pins the 0.1.24 rule that produced
  the habit: a fleck is never wider than the lead that lays it.
- **What the renders show (read by eye at 1× and 3×, and measured threshold-free):** all five leads
  lay isotropic graphite with no combing, no connected bristle, no bead at a cap and no banding;
  the inked extent is the nominal lead plus one to three pixels at every size (1.2 → 4.0, 2 → 4.0,
  4 → 6.0, 7 → 8.0, 12 → 14.0, measured as the rows holding the middle 98 % of the mark's mass),
  i.e. **every lead reads at its nominal width plus about one fleck of bleed** — which is what the
  document promises, and it holds at the hairline as well as at 12 px, so nothing in the new range
  is quietly fatter or thinner than it says. The widest fleck on the 1.2 px lead is 1.2 px, the
  0.1.24 cap doing its job. One thing worth
  a second look on the panel rather than here: **an exactly axis-aligned hairline reads gappier
  than the same lead on a curve**, because a horizontal mark lines its lanes up with the pixel
  rows. It is a rasteriser artifact of straight-and-level marks, not a lead problem, and no hand
  draws that line — but it is the sort of thing that gets reported as "the thin pencil is broken".
- `docs/api.md` (a paragraph under the constant-bake one: the preview follows the lead's colour,
  why the ladder is its own, why it stops short of the lightest code, and that the boundaries await
  the walk), `CLAUDE.md` (the Phase 21 bullet's constant list corrected — `PENCIL_PREVIEW_GREY` was
  not re-opened but **outgrown** — plus one new standing bullet: one tone per arming limits a
  single mark, not the set of them).
- **215 → 216 core, 12 → 17 ratta, all green; `:demo:assembleDebug` builds.** `gpaper-onyx` and
  `gpaper-core`'s main source set are untouched, so the **public API surface is unchanged** and
  Paintsprout's pin is unaffected.

**The walk (the artist's hand, Nomad, 2026-09-17) — what turned the guess into a measurement**
- **Levels 7–9 previewed darker than they baked** on the first ladder (3–9 → DARK_GRAY). The
  DARK_GRAY ceiling moved 161.5 → **110.5** (the midpoint of levels 6 and 7): **0–2 BLACK, 3–6
  DARK_GRAY, 7–14 GRAY**, and the hand passed it. The BLACK ceiling (42.5) stood.
- **Levels 12–14 also preview darker as GRAY than they bake.** LIGHT_GRAY was trialled for them in
  a throwaway build and **rejected by the same hand** — it "doesn't work": the line cannot be
  followed while it is drawn. GRAY stays the palest rung; "never LIGHT_GRAY" is now measured, not
  only argued. Whether the host offers levels 13–14 at all is the host's question (arc 44 T3) —
  the ladder covers every grey either way.
- **All five lead sizes pass** — preview and bake agree in width at 1.2 / 2 / 4 / 7 / 12 px. No
  EMR change, no door opened.
- Fable reviewed the diff line by line (three wording corrections: the Phase 19 record says GRAY
  "was tried", not "too pale"; two "the third" miscounts). KDoc, `RattaInkMapTest`, `docs/api.md`
  and `CLAUDE.md` re-stated from "starting values" to the walked ones.

**Gate:** 216 core / 17 ratta green; `:demo:assembleDebug` builds; Paintsprout Onyx 203 green on
its pin; published to mavenLocal as **0.1.36**. SN re-pins at arc 44 T3.

### Phase 24 — The lead goes wide on Ratta (post-v0.1.0)
**Status:** ✅ Complete (2026-09-17) · **Publishes:** 0.1.37 · branch `pencil-tones` ·
Opened 2026-09-17 by Notesprout SN's arc 44 "Pencils" phase T3 (branch `pencils` there; plan +
ledger in `extensions/sketch/PENCILS_PLAN.md`).

Phase 23 gave the pencil fifteen shades and walked its five leads (1.2 / 2 / 4 / 7 / 12 px). T3 is
where the host picks which sizes it actually offers, and the artist went looking above 12 — where
`RattaEmr.EMR_MAX` had been clamping the live preview to 12 px since the PoC, so a 24 px lead
previewed as a 12 px one and baked at 24. **That is the width lie this project treats as the
serious one**, and it had been sitting behind a ceiling whose KDoc read *"the panel gains nothing
above it and the daemon lags"* — a sentence asserting two device findings, neither of which had
ever been made. Nothing had ever armed an EMR above 1200, because until arc 44 nothing asked to.

**Planned**
- Lift `EMR_MAX` to the widest size the hand walks, and rewrite its KDoc to say what is measured
  and what is merely untried. `EMR_MIN` (200) and `EMR_MIN_HAIRLINE` (120) **do not move** — the
  floors are measurements and this phase is only about the other end.
- `RattaEmrTest`: the ceiling pins move, and each walked width gets its own pin, so a lead the
  walk approved can never be silently clamped back.
- The demo keeps its widened lead cycler as the walk surface.
- `docs/api.md`, `CLAUDE.md` and this entry in the same commit as the code.
- Audit `gpaper-ratta` / `gpaper-core` for anything else assuming a 12 px maximum.

**Landed (2026-09-17)**
- **`RattaEmr.EMR_MAX` = 9600 (96 px)**, and its KDoc now separates the measurement from the
  assumption in as many words: 96 px is the widest lead a hand has walked, and it is the ceiling
  **because nothing wider has been tried, not because anything refused**. A host wanting more
  should expect a walk to grant it — the opposite of the old ceiling's story. `penSize` itself is
  unchanged: one `coerceIn` between a style-dependent floor and this ceiling.
- **`EMR_MIN` and `EMR_MIN_HAIRLINE` are byte-for-byte untouched**, as is every other Ratta
  constant (`PENCIL_BAKE_PRESSURE` 0.5, `bakeTilt` 0, `RASTER_ERASE_REDRAW_MS` 16,
  `RattaInkMap`'s two ladders). `gpaper-core`'s main source set and `gpaper-onyx` are untouched,
  so the **public API surface is unchanged** and Paintsprout's pin is unaffected.
- **The 12 px-maximum audit found nothing else to fix.** `RattaEmr.EMR_MAX` was the only place in
  either module where a maximum width was written down. The firmware **eraser** has a floor and no
  ceiling at all (`eraserEmr`, `radius * 50` coerced up to `ERASER_EMR_MIN` 400), so a wide rubber
  was never clamped. `RasterDirty`'s pad is `width + MARGIN_PX` — driven by the mark's own width,
  so it already grows with the lead and needed no change; the one consequence worth stating is
  that at a 96 px lead a run's announced rect is ~452 px a side rather than the ~284 a 12 px lead
  makes of the same 256 px span (`MARGIN_PX` is 2), which costs a
  raster host a larger before-image per run exactly as it should. The remaining "12 px" in the
  tree are unrelated: `PencilRenderHarness`'s cell geometry is arc 44's five lead sizes, and
  `docs/api.md`'s "12 px-inflated box" is the selection overlay's inflation. One thing the audit
  did turn up and the KDoc now says: `RattaEmr`'s own opening paragraph notes the Needle
  penSizeArray running "~200…2400", and the walk arms sizes well past that and the firmware
  renders them — **that array is the range Ratta's own app picks from, not a bound the daemon
  enforces.** Same mistake in miniature, and the reason the floor argument rests on what a thin
  line *looks* like rather than on where the array starts.
- **`RattaEmrTest` +1 test (17 → 18 ratta):** `a wide pen stops at the ceiling` now pins 12 px at
  1200 (nowhere near the ceiling now), 40 px passing through at 4000, and 200 px clamping at 9600;
  the new `every lead the hand walked arms the width it asked for` pins all seven walked sizes
  (16 / 20 / 24 / 32 / 48 / 64 / 96 → 1600 / 2000 / 2400 / 3200 / 4800 / 6400 / 9600) one by one;
  the floors-and-ceiling test keeps its two floors and takes the new ceiling. The class KDoc says
  why the ceiling is pinned for the opposite reason to the floors.
- **The demo's lead cycler keeps 1.2 … 96** (twelve leads), with its KDoc rewritten to record that
  16 … 96 are the walk surface this phase was opened by and why they stay: 96 is what makes the
  ceiling a measurement rather than a number in a KDoc.
- `docs/api.md` (the "200…1200" sentence becomes 200…9600 plus a short paragraph on the
  measurement and on what the number is *not*), `CLAUDE.md` (the Phase 19 hairline bullet's clamp
  text corrected, plus one new standing bullet: **a limit nothing ever reached is not a
  measurement, and it will be believed anyway** — a bound stated as a device finding must name its
  walk or admit it is a guess, and when a clamp is the suspect, ask first whether anything has
  ever touched it).
- **216 core / 18 ratta green; `:demo:assembleDebug` builds.**

**The walk (the artist's hand, Nomad, 2026-09-17)** — with the ceiling lifted, PENCIL leads of
**16 / 20 / 24 / 32 / 48 / 64 / 96 px all preview at the width they bake**, and no lag was reported
at any of them: *"all of those wide-lead sizes work."* Supernote's own notes app offers widths
around 24 px, well inside the walked range. The old 1200 was never contradicted by a measurement,
because it was never met by one.

**Gate:** 216 core / 18 ratta green; `:demo:assembleDebug` builds; Fable reviewed the diff and published
**0.1.37** to mavenLocal (`GPAPER_VERSION`, `README.md`, `docs/integration-guide.md` bumped). SN
re-pinned at arc 44 T3 (`:sn-screen` 0.1.35 → 0.1.37) and walked the twelve leads on the Nomad;
**arc 44 froze at T5 (2026-09-17)** on the user's hand walks — Phases 23 and 24 are closed with
it. `pencil-tones` merged to `main` (`--no-ff`) on the artist's word, 2026-09-17, and deleted.

---

### Phase 25 — Graphite on paper: the wide lead stops being a comb (post-v0.1.0)
**Status:** ✅ Complete (2026-09-17) · **Publishes:** 0.1.38 · branch `graphite-tooth` ·
Opened 2026-09-17 by the artist's Manta screencap of NSE · Sketch's 96 px lead: *"it doesn't
look like a natural graphite grain. It looks like a series of lines more than anything."*

It was. Reproduced offline (a JVM dump of `GraphiteGrain.of` rendered in Python — the pure-Kotlin
promise paying off), and the mechanism was **not** the fleck jitter, which was the first guess and
changed nothing at 1.0: the centre of the mark was laid evenly along its length and the **rim came
in bunches with a ~4 px period**, each bunch a short bar across the mark. A cross-section is a
rigid comb turned to the smoothed travel direction, and that direction wobbles by a couple of
degrees after `TANGENT_SMOOTH_PX` — nothing at the centre line, **1.7 px along the stroke on a
fleck 48 px out**, twice the station pitch. Combs pile up and part at the rim. Invisible on the
12 px lead every earlier phase looked at; the whole look at 96. Smoothing harder would need a
~1000 px window to hold that lever arm still, which would drag every curve.

**Landed (all in `GraphiteGrain`, `gpaper-core` only — public API unchanged)**
- **`LEVER_JITTER` (0.08 px along per px out):** a fleck's along-the-stroke freedom grows with its
  distance from the centre line, so the rim flecks land a few px ahead of or behind their station
  — more than the wobble can move them — and the bunching averages out. Centre flecks keep their
  cell. `JITTER` raised 0.62 → 1.0 in both axes while there (a full cell; the comb was the real
  fault but a lattice that shows through at 0.62 is a second one).
- **The skate is two-dimensional** (`SKATE_WIDTH_PX` 5): it was a function of arc alone, so it
  lifted a whole cross-section at once — on a fine lead a streak, on a 96 px lead a **bar across
  the mark every 26 px**. Now long along and short across: streaks in the direction of travel.
- **The sheet's tooth (`tooth`, `catches`, `TOOTH_CELL_PX` 3.5 / `TOOTH_WEIGHT` 0.55 /
  `TOOTH_SEED`):** with the comb gone the mark was white noise — every site an independent coin
  toss, an even spray that reads as static. Real tooth comes in patches; so a two-octave value-noise
  field in **page** coordinates, seeded by a constant (the sheet, not the stroke), and a site's catch
  is a blend of its own toss and the tooth height under it. Mottled like paper; two strokes over the
  same spot share the same hollows; at full coverage the field is overruled and a hard press goes
  solid. Applied to the tap as well as the sweep.
- **Total ink is unchanged within 1 %** (measured on the offline render), so the fifteen-shade
  ladder the hand approved in Phase 23 does not move; the texture is redistributed, not darkened.
  Checked at 96 px / 0.5, 96 px / 1.0 (solid), 24 px, 5 px, the 1.2 px hairline, and a 12 px lead
  at 60° (pale, broad, mottled like shading): nothing regressed on the narrow leads BOOX draws.
- **217 core green**; the determinism pins in `GraphiteGrainTest` pass untouched — the field is a
  stateless hash like everything else here.

**Gate — passed (the artist's hand, Manta, 2026-09-17):** SN re-pinned `:sn-screen` at 0.1.38, the
release host + `NSE · Sketch` installed, the 96 px lead drawn by hand: *"Sooooo much better :)"*
`graphite-tooth` merged to `main` (`--no-ff`) the same day and deleted.

### Phase 26 — Two rasters: graphite and ink (post-v0.1.0)
**Status:** ✅ Complete (2026-09-17, the artist's hand on the Nomad) · **Publishes:** 0.1.39 ·
branch `two-rasters` ·
Opened 2026-09-17 by the artist's decision for NSE · Sketch's arc 45 "Ink" (Notesprout
`extensions/sketch/INK_PLAN.md`): *"in the real world, ink is more permanent than pencil"* — the
gel pen must not lift under the rubber. The page held one ARGB bitmap and a pixel does not know
which tool laid it, so the fix is the page's data model, not a colour key: **two rasters, one
picture.**

**Scope (Fable's brief to Opus; Fable reviews the diff before publish)**
- `RasterLayer { GRAPHITE, INK }` in `gpaper-core`. `CanvasPaperView` holds `graphiteRaster` and
  `inkRaster`, both allocated lazily as `pageRaster` is today.
- **Routing by style**: `compositeIntoRaster` bakes `PENCIL` into graphite and every other style
  into ink — a fresh mark, a `loadStrokes` bake and an `addStrokes` bake alike.
- **Flatten = `DARKEN`**: the committed-layer draw paints graphite then ink with
  `PorterDuff.Mode.DARKEN`, so each pixel is the darker of the two — order-independent, and right
  for a coloured ink later. The Ratta live preview is untouched.
- **The rubber rubs graphite only** (`eraseRasterAlong`); the ink raster is never read by an
  erase. `RasterRubbing` and `RasterRub` are unchanged — an `inkLift` fraction is a future
  decision, not this phase.
- **Layer-qualified API**: `getPageRaster(layer)` / `loadPageRaster(layer, bitmap)` /
  `copyPageRaster(layer, rect)` / `swapPageRaster(layer, patches)`, and a layer on
  `PaperListener.onRasterWillChange` / `onRasterChanged`; **the un-layered forms keep meaning
  graphite**, so Paintsprout compiles unchanged against a re-pin. One contact announces exactly one
  layer. `clear()` drops both. `RasterDirty` unchanged.
- Demo: the raster page draws pencil + pen + rubs, and renders the flatten to a PNG before a panel
  sees it (this file's standing rule). `docs/api.md` + `CLAUDE.md` in the same commit.

**Landed (built 2026-09-17, awaiting the gate)**
- **`RasterLayer { GRAPHITE, INK }`** in `gpaper-core`, with `RasterLayer.of(style)` the one
  place the routing is decided (`PENCIL` → graphite, every other style → ink). Pure Kotlin.
- **`CanvasPaperView`** holds `graphiteRaster` + `inkRaster`, each allocated lazily on its
  own first mark — so a pencil-only page costs what it always did. `compositeIntoRaster`
  makes one `Canvas` per layer anything actually lands in; every drop site
  (`loadStrokes`, `clear`, `clearForContentSwap`, `release`) drops both.
- **The flatten is one added blit**: `drawCommittedContent` paints graphite as before and
  ink over it through a single reused `Paint` carrying `PorterDuff.Mode.DARKEN`.
  `renderToBitmap` goes through the same method, so the picture and the panel agree.
- **`eraseRasterAlong` names `graphiteRaster`** — the artist's rule held by construction,
  not by a test on pixels. `RasterRub` / `RasterRubbing` / the pass mask / the cadence seam
  are untouched, and an erase never reads, allocates or announces the ink image.
- **Layered API + un-layered defaults**: `loadPageRaster` / `getPageRaster` /
  `copyPageRaster` / `readPageRaster` / `swapPageRaster` and both
  `PaperListener` raster callbacks take a `RasterLayer`; the 0.1.38 forms are interface
  defaults meaning `GRAPHITE`. The engine calls **only** the layered forms, and the
  un-layered listener default is deliberately **silent for ink** — a legacy listener's
  `readPageRaster(rect)` reads graphite, so forwarding an ink change would hand it the
  wrong before-image.
- **`OnyxPaperView`**'s two overrides moved to the layered forms; nothing else on Onyx
  changed. **`RattaPaperView`** still needs no override, and its comment now says why two
  rasters do not disturb either reason.
- **Demo**: a `Pen (ink)` toggle beside Shade/Lead (the SN gel pen — `PEN`, 5 px, black;
  the cyclers keep stepping while it is armed and take effect when the pencil returns), an
  undo history keyed by `(layer, tile)` that swaps per layer, `Swap pg` reading and swapping
  **both** rasters, a **`Dump`** button that wall-clocks `renderToBitmap()` and writes the
  flatten to `getExternalFilesDir(null)/flatten-<epoch>.png` (`flatten render: WxH in N ms`)
  — the "render it to a PNG before a panel sees it" door — and the armed raster tool in the
  status head.
- **220 core / 18 ratta green** (+3 core: `RasterLayerTest` walks `StrokeStyle.entries` so a
  new style cannot fall through the routing, and `LegacyRasterHostTest` is a 0.1.38 host
  whose *compiling* is the source-compatibility guard); `:demo:assembleDebug` builds.
- `docs/api.md` (raster section rewritten: the two rasters, the `DARKEN` flatten, the
  layered calls, the graphite-only rubber, the silent-ink rule for legacy listeners) and
  `CLAUDE.md` (one new standing bullet in the raster block) in the same commit.

**Gate:** `./gradlew test` green (core + ratta + onyx); Paintsprout Onyx green on its pin; the
Nomad demo by the artist's hand — pen over pencil, pencil over pen, rub each way: graphite lifts,
ink stays; flatten redraw ms vs. 0.1.38 and demo PSS recorded here; 0.1.39 to mavenLocal.

**Gate — passed (the artist's hand, Nomad, 2026-09-17):** 220 core / 18 ratta green; Paintsprout
Onyx compiled against a throwaway 0.1.39 re-pin with no source change (`:app:compileDebugKotlin`
green, pin reverted); the demo walk — pencil shading, gel pen across it, rubbed both ways, pencil
over ink and rubbed again, undo/redo of a mark, a pen line and a rub — *"Nothing feels off."*
The flatten dumped from the demo and inspected at 1× and 3×: ink solid through the rubbed
graphite, the rub corridors only in the graphite, no fringe at a crossing. **Measured:**
`renderToBitmap()` of the 1404×1711 flatten **37 ms** (a new instrument — there is no 0.1.38
`Dump` to set beside it; a pencil-only page skips the second blit entirely); demo PSS **48.8 MB
at launch → 70.9 MB** after the walk with both rasters live (each ~9.6 MB on the Nomad); undo
swaps 7–20 ms for 6–9 graphite tiles; a pen line's entry 33 tiles on ink alone, every rub entry
on graphite alone. 0.1.39 published to mavenLocal.

**Consumer closed (2026-09-18):** Notesprout SN re-pinned 0.1.39 at arc 45 / G3 (`sn-screen`), and
the sketch face now carries both rasters — per-layer saves as lossless WebP, per-layer undo
entries, the layered listener overrides only. The user's Nomad hand walk was clean: pencil over
pen and rubbed, pen over pencil and rubbed, a rub over ink alone, undo/redo across both rasters,
Bring in ink then rub then undo — *"Clean!"* Face PSS 67.7 MB with both rasters live vs. 64.2 MB
at one; an ink lattice's undo entry costs exactly what the same graphite lattice's does (368 tiles
/ 6 029 312 B each). Merge of `two-rasters` to `main` pending the user's word.

### Phase 27 — A white lead previews LIGHT_GRAY (post-v0.1.0)
**Status:** ✅ Complete (2026-09-18) · **Publishes:** 0.1.40 · branch `two-rasters` ·
Opened 2026-09-18 by the artist's decision for NSE · Sketch, before arc 45 merges: the pencil
palette narrows to **the four tones the firmware has** — black, grey, light grey (ladder levels
0 / 5 / 9, the three the preview hits exactly) and **white** (level 15), the white lead being a
*lightener*: flecks go down `SRC_OVER` on the graphite raster, so a white one pales the graphite
under it — the precision the rubber does not give — and lays nothing on bare paper under the
`DARKEN` flatten.

**Scope.** One rung on `RattaInkMap.pencilPreviewFor`: `PENCIL_GRAY_MAX_LUMA` = 246.5 (the midpoint
of level 14 and white), above which the preview arms LIGHT_GRAY. Every grey the hand rejected
LIGHT_GRAY for (12–14) still previews GRAY — a grey lead is watched while it is drawn; a white
lead's honest preview is the faintest tone the panel has, since a GRAY trail that vanished at
pen-lift would say the opposite of what the lead does. `firmwareColorFor` untouched. The ratta test
"never answers LIGHT_GRAY" becomes "for white and for nothing greyer". No demo change, no API
change; `penColor` = `0xFFFFFFFF` is the whole host-side ask.

**Gate:** `./gradlew test` green; the white lead on the artist's Nomad through NSE · Sketch (the
consumer walk stands in for a demo walk — the demo's Shade cycler never reaches white).

### Phase 28 — Graphite on the panel: the pencil's live preview goes direct on Ratta (post-v0.1.0)
**Status:** 🔄 In progress (opened 2026-09-18) · **Publishes:** 0.1.41 · branch `ebc-live` (from
`ebc-probe`, which carries the `probe-ebc` app — the measurement door this phase is built on; its
README is the reference for every number below).

**Why.** On Supernote the pencil's live preview is the firmware daemon's needle in one grey, and
the mark it lays lands at pen-up through a bake + overlay clear — the flash. Atelier has no
flash and sixteen greys because it never uses the daemon for a stroke: it paints straight into
the panel driver (`/dev/ebc`), which the vendor SELinux policy lets any app open (`allow appdomain
rga_device`). `probe-ebc` proved it from an `untrusted_app` on the Nomad and the Manta: 16-grey
pixels in frame 0, one `HTEINK_IOC_DISPAREA` (mode 7, flag 1 — Atelier's own call) per event, no
flash. And it found the physics: **a solid grey lands black and lightens** (the 16-grey waveform
passes through black), while **black flecks land at once** — which is exactly what `GraphiteGrain`
lays. The user's hand on both devices: *"works perfectly, very much like Atelier."*

**The user's decisions (2026-09-18):** native code lives **inside `gpaper-ratta`** (a small C
file; building g-paper needs the NDK, consumers get the `.so` in the AAR); **pressure returns to
the Ratta pencil** (the constant 0.5 bake existed only because the needle could not show tone);
**pencil only** — pen, rubber and stroke-mode pages keep the firmware path; Opus codes on Fable's
brief, Fable reviews, the user walks both devices.

**Design**
- `gpaper-ratta` gains `EbcPanel` (internal): opens `/dev/ebc` `O_RDWR|O_CLOEXEC`, `HTEINK_IOC_GETINFO`
  (`0x48545201`, 24 B: `[1] = h<<16 | w`, `[3] = frameBytes`), `mmap(frameBytes)` of frame 0,
  and `display(rect, mode 7, flag 1)` through `HTEINK_IOC_DISPAREA` (`0x48545701`, the 24-byte
  `{l,t,r,b; bufOffset=0; u8 mode; u8 flag}`), via a JNI pass-through `ebc_jni.c`
  (open/close/ioctl/mmap/munmap). Frame pixels are one byte each, 4-bit grey `0x00`…`0x0f`.
  Absent or refused → the pencil keeps today's needle preview, `Log.w` once.
- **Rotation by geometry** (measured): panel ≠ screen size → Nomad's `panelX = screenY`,
  `panelY = panelH−1 − screenX`; equal → Manta's identity. Screen coordinates, so the view's
  `getLocationOnScreen` offset applies first.
- **`RattaPanelTone`** (pure Kotlin, tested): the compositor's Android-grey → 4-bit table, identical
  on both devices: 0–75→0 · 76–87→1 · 88–99→2 · 100–107→3 · 108–119→4 · 120–131→5 · 132–139→6 ·
  140–151→7 · 152–167→8 · 168–187→10 · 188–195→11 · 196–203→12 · 204–215→13 · 216–223→14 ·
  224–255→15 (level 9 is never produced). Live pixels go through it so the pen-up recompose writes
  the same levels back — the mirror is invisible.
- **The live layer.** One page-sized `ALPHA_8` scratch (`liveGraphite`) in `CanvasPaperView`'s
  Ratta subclass. Per event the stroke so far runs through `GraphiteGrain.of(points, width, seed,
  prefix = true)` with the stroke's pending id and the engine's bake parameters, and only the
  flecks past the count already laid are drawn by `StrokeRenderer` into the scratch — same flecks,
  same rasteriser as the bake. The dirty rect is flattened as `drawCommittedContent` flattens
  (graphite ⊕ scratch-as-shade, `DARKEN` ink, over white), toned, rotated, written, displayed.
- **`GraphiteGrain` prefix mode** (pure, tested): the filters are causal by design; prefix mode
  omits the **end cap** and lays nothing until the arc passes the landing window (`2 × LANDING_TRIM_PX`),
  so a prefix's flecks are exactly the first N of the whole stroke's. Test: for every prefix of a
  recorded stroke, `of(prefix, prefix=true)` ⊂ `of(whole)` as an ordered prefix.
- **A paint thread** owns the ioctl (it blocks up to ~65 ms while an update is in flight):
  the UI thread posts fleck batches; the thread unions whatever accumulated and sends one DISPAREA.
- **The firmware is kept off the pencil**: with the pencil armed on a raster page and the panel
  open, the tool push issues a full-screen disable instead of arming the needle (Atelier's
  `sendFullScreenDisableArea`). Pen-up: the stroke bakes as today (`compositeIntoRaster`), the
  scratch is cleared in the mark's rect, and `bakeAfterCommit` records + presents at once — no
  `pendingBake`, no ladder, there is no overlay ink to drop. The window is **never invalidated
  mid-stroke** (the compositor would overwrite fresh dabs with older pixels — measured).
- `bakePressure` / `bakeTilt` return identity for the direct path (pressure is back; tilt too —
  the preview can now show a leaned lead), and stay 0.5 / 0 when the panel is unavailable.
- Demo: the raster page shows `panel: direct` / `panel: needle` in the status head. `docs/api.md`
  (Ratta section: what the pencil does now), `CLAUDE.md` (the standing rules: never invalidate
  mid-stroke, tone through the table, the daemon disabled for the pencil) in the same commit.

**Known limits (this phase):** a fleck over a template line previews over white and darkens a
little at pen-up; live rubbing through the panel and the ink pen are later phases.

**Gate:** `./gradlew test` green (core + ratta); `:demo:assembleDebug`; the user's hand on the
Nomad and the Manta through the demo's raster page — light and hard, fast and slow, over ink, undo;
no flash, no change at pen-up; 0.1.41 to mavenLocal by the user.

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
