# CLAUDE.md — g-paper project intelligence

g-paper is an Android library that embeds a writable/drawable "paper" surface in any app.
`PLAN.md` is the cross-session memory: phase statuses, locked decisions, and the working protocol
(phase rituals, context resets) live there. This file holds standing rules and build facts that
apply regardless of phase.

## Layout

Gradle multi-module project at repo root:

- `gpaper-core` — public API + generic Canvas engine (`com.symmetricalpalmtree.gpaper.core`)
- `gpaper-onyx` — BOOX adapter (`…gpaper.onyx`), depends on core
- `gpaper-ratta` — Supernote adapter (`…gpaper.ratta`), depends on core
- `demo` — demo app (`com.symmetricalpalmtree.gpaper.demo`)

Docs (Phase 8, all under `docs/` + the root `README.md`): `api.md` (host-facing surface, guided
tour), `integration-guide.md` (per-device build setup), `architecture.md` (internals),
`host-responsibilities.md` (persistence/pages/undo/gesture patterns). **Any public-surface or
build-requirement change must update the matching doc in the same commit.**

Reference source: `~/git/Notesprout` (see its `docs/drawing-engine.md`) — the engines being
extracted/redesigned. Read it for device knowledge; never copy its sibling-view duplication.
Pen-type/native-style knowledge: Notesprout `docs/onyx-pen-tools.md` (Onyx five-device survey) and
the Ratta 0…31 pen-code sweep recorded in Notesprout's `app/src/debug/AndroidManifest.xml` comment.

## Public API (Phase 1 — standing rules)

- The whole host-facing surface is `gpaper-core`'s `com.symmetricalpalmtree.gpaper.core` package;
  `docs/api.md` is the guided tour and must be kept in step with the code.
- **`model/` and `geometry/` stay pure Kotlin — no Android imports.** That is what lets the unit
  tests run on the JVM without Robolectric. Android bridging goes in `model/AndroidInterop.kt`.
- Native device style/pen codes never surface in the public API. `StrokeStyle` is abstract; the
  committed (baked) appearance is core-rendered and portable across engines, live ink is a
  best-effort per-engine mapping (tables in `StrokeStyle` KDoc + `docs/api.md`).
- `core/canvas/` is the generic engine + shared canvas base (`CanvasPaperView`, `StrokeRenderer`).
  Public **only** so device modules can subclass — never present it as host API; hosts go through
  `GPaper.create`. `StrokeRenderer` is the single source of truth for committed stroke appearance.
- **A textured style's texture must never reshuffle** (Phase 10, `PENCIL`). Two failures, not one:
  a grain re-rolled on **reload** is a drawing that changes behind the artist's back, and a grain
  re-rolled at **pen-up** makes every mark end in a flinch, because the live preview and the bake
  run through the same renderer. So texture comes from a stateless integer hash seeded by
  `StrokeRenderer.draw`'s `seed` — never a running RNG — and callers holding a `Stroke` pass
  `id.hashCode()`. `CanvasPaperView` therefore mints the stroke id when the **contact starts**
  (`pendingStrokeId`), not at commit, so the preview seeds off the id the stroke is about to get.
  Anything that walks the path must index by **arc length from the first point**, never by input-
  point index: a stroke still being drawn must agree with the same stroke committed, and pen
  samples arrive at whatever rate the hand and the digitizer agree on.
- **A raster page is a layer over the paper, never the paper itself (Phase 13, `PageMode.RASTER`).**
  The page image is transparent where nothing was drawn; white and the template draw beneath it.
  That is what lets an eraser clear to transparent rather than paint white, and a textured sheet
  sit under a raster page later. **Dropped, not erased, at a content swap:** the committed display
  list keeps its own reference to the bitmap it was recorded with, so the old pixels hold until
  the next page lands; erasing in place blanks the panel a frame early. Both modes share
  everything up to pen-up — only what is *kept* differs, and a host that never sets `pageMode`
  gets the stroke engine unchanged. `getPageRaster` is a **copy**, always: the host encodes it
  off the main thread while the pen keeps going. The engine keeps no history in either mode.
  **A raster undo is a swap, not a load (Phase 17, 0.1.29):** `readPageRaster(rect)` is the
  before-image as a `RasterPatch`, and `swapPageRaster` puts it back and leaves the array holding
  what was there — one entry serves redo, no second copy of an 18 MB page mid-undo, and on Onyx
  only the patched region is refreshed. The swap runs row by row through one reused buffer.
- **A mark announces itself as RUNS, not as a box — and a change the host made itself is not
  announced at all (Phase 20, 0.1.33).** A raster host's before-image costs the *announced*
  area, never the ink's, so one rect per mark made a corner-to-corner hairline cost the whole
  page: ~9.5 MB of 64 px cells read on the main thread inside the pen-up callback on a 1404×1685
  Nomad page (~19.7 MB on a Manta), two such strokes filling a host's undo budget. Generosity
  *outward* is still right — a rect that misses one fleck leaves a fleck undo cannot lift — but
  generosity *across the diagonal* is only the bounding box being a bad model of a line.
  **Measured on the Nomad through SN's sketch face: a corner-to-corner hairline cost the host
  550 cells / 9.0 MB / 40 ms at pen-up on 0.1.32 and 134 cells / 2.2 MB / 18 ms on 0.1.33.**
  `RasterDirty.along` (pure, JVM-tested) cuts the polyline into runs of at most
  `RASTER_DIRTY_SPAN_PX` (256) of unpadded span, at most `RASTER_DIRTY_MAX_RECTS` (64) of them,
  and **the closing point of a run is the first point of the next** so the segment across the
  boundary lies wholly inside one rect: coverage is the invariant and every other property is a
  saving taken where coverage is not at stake. Past the cap the last run absorbs the tail — a
  bigger rect, never a dropped one. Every `onRasterWillChange` of a mark fires before any of its
  pixels move and every `onRasterChanged` after, in the same order; the eraser has reported per
  batch since 0.1.26, so a host that accumulates one entry per contact already handles it.
  **The ENGINE seam pays for the announced area too (2026-09-19).** `onRasterPixelsChanged`
  announced one union rect per composite batch, on the reasoning that a second image of the
  page costs only a repaint of empty pixels — and Ratta's dither rebuilds every pixel inside
  it, so the pen-up of one long pencil stroke held a Nomad's main thread for **848 ms**
  (`slow touch ACTION_UP: … base 848 ms`, measured through NSE · Sketch). It takes the same
  runs now, the caller's own list rather than a second computation of it. **A bounding box
  is a bad model of a line wherever somebody pays per pixel for it** — and the second place
  that was true went unnoticed for six releases because it was reasoned about as free.
  **And a mark lands ONCE**: the runs go in one batched call,
  `onRasterPixelsChanged(rects: List<Rect>)` (default: forwards each to the single-rect
  form, so nothing else changed), because the per-run fix cured the *line* — 848 ms → 138–198
  — and left the *scribble* at **651 ms**, up to sixty-four small rebuilds each too cheap to
  log. An engine keeping a second image of the page must override the list form: flatten per
  run, land once. **A fixed per-call cost paid sixty-four times hides from every per-call
  threshold there is.**
  **And `loadPageRaster` is now silent, as `swapPageRaster` always was**: a page the host
  replaced is the host's own news, and the flag every host carried to swallow the load's
  callbacks (`loadingRaster` in Paintsprout's `SketchbookActivity` and SN's `SketchActivity`)
  only worked because these calls happen to be synchronous — a correctness argument resting on
  an implementation detail nobody promised is a bug waiting for the day the detail changes.
- **Two rasters, one picture — because a pixel does not know which tool laid it (Phase 26,
  0.1.39).** The page was one ARGB bitmap and the rubber lifts alpha wherever it sweeps, so
  a gel pen came up under it exactly as graphite did. No colour key could have fixed that
  honestly: a black pen and a black pencil are the same pixel, and a rule read off the
  pixels would have been a guess about history dressed as a fact. The artist's rule is the
  physical one — *"in the real world, ink is more permanent than pencil"* — so the fix is
  the page's **data model**: `graphiteRaster` and `inkRaster`, routed once in
  `RasterLayer.of(style)` (`PENCIL` → graphite, everything else → ink). **The rubber rubs
  graphite and only graphite** — `eraseRasterAlong` names that bitmap, so the rule holds by
  construction rather than by a test that could be got wrong; the ink image is never read,
  never allocated and never announced by an erase. **The flatten is `DARKEN`**, not an
  over-draw, because these are not user-facing layers: `min` per channel is commutative, so
  there is no top and no bottom to get wrong, it is the right answer for a coloured ink
  later, and on white paper it is pixel-identical to `SRC_OVER` — so the pencil page the
  artist already approved does not move. **One contact announces exactly one layer** (a
  mark's runs are all its style's; a sweep is all graphite); a load or a clear announces
  both, graphite first, even when one is empty, because a host undoing a load needs the
  before-image of both. And **the un-layered calls mean graphite** — every raster call and
  both callbacks have a layered form the engine uses and an un-layered default that routes
  to `GRAPHITE`, so a 0.1.38 host compiles and behaves unchanged. A listener that overrides
  only the un-layered half hears **nothing** of ink on purpose: its `readPageRaster(rect)`
  reads graphite, so forwarding an ink change would hand it the wrong before-image and its
  undo would paint graphite where ink was. Silence beats a corrupted history.
- **On Supernote the pencil's live ink is ours, not the daemon's — and three rules keep it
  honest (Phase 28, 0.1.41).** `/dev/ebc` is openable by any app (the vendor policy line
  `allow appdomain rga_device chr_file { open ioctl map … }`, proved from an `untrusted_app`
  on a Nomad and a Manta), one byte per pixel of 4-bit grey in frame 0, shown by one
  `HTEINK_IOC_DISPAREA` (`0x48545701`, mode 7, flag 1 — Atelier's own call). So the raster
  pencil paints `GraphiteGrain`'s flecks into the panel itself: the lead's own shade,
  pressure back, and **no change at pen-up**, because what the bake lays is what is already
  there. **(1) The window is never invalidated mid-stroke on that path.** The compositor
  rewrites frame 0 from the window every ~0.2–1.4 s regardless, and a frame presented
  mid-stroke is that copy — *older than the dabs just painted*, which it overwrites; the
  panel only refreshes when someone asks, so with no frames the preview simply stands.
  **(2) The live pixel and the displayed pixel are decided by ONE pure function at the same
  page coordinates** (`DitherFlatten` — see (4)) — that is what makes the pen-up recompose a
  no-op instead of every mark settling a shade a beat after it is drawn. It was
  `RattaPanelTone`, the measured compositor grey→level table (irregular bands, level 9 never
  produced, identical on both devices), while the panel was still being sent greys; that
  table stays as the record of a measurement nothing else holds. **(3) The daemon is
  full-screen-disabled while that pencil is armed**, from the same tool push that would have
  armed the needle, or it paints its one flat grey over the grain. The physics behind the
  whole thing: **a solid grey dab lands black and lightens toward its target** (the 16-grey
  waveform passes through black) while **black flecks land at once** — so a scatter of black
  is the one thing this panel can preview truthfully, a pencil is a scatter, and a dither is
  how a *grey* one becomes black. `probe-ebc/README.md` holds every number; the
  fallback when the driver is unavailable is 0.1.40's needle, one log line, no host change.
  **(4) The pencil stays GREY in the page image; on that path the window and the panel show a
  blue-noise DITHER of the flatten, live and at pen-up — so no grey pixel ever reaches the
  panel from the pencil, and covers and exports stay true grey.** The read-back was right
  (Atelier sends the panel level 0 and nothing else) and the conclusion drawn from it was
  wrong: Atelier **dithers** a grey stroke into an even pattern of black dots, so a light
  stroke keeps its whole shape and reads as a flat light grey. The second walk instead thinned
  the grain — shade as fleck *density* — which lands cleanly under the nib and is still wrong,
  because **a density changes the MARK and the fault is in how the panel SHOWS one**
  (*"Atelier uses greyscale colours; this just leaves less graphite down, so shade 13 looks
  like a bug"*). The fix belongs to the display and stays there: `Dither` over `BlueNoise64`
  (pure, position-keyed, stateless — so two renderers looking at the same grey at the same page
  pixel always agree), `DitherFlatten` as the **one** per-pixel flatten+dither both halves call,
  and a core seam that keeps it off the data — `drawRasterLayers(canvas, forDisplay)`, false
  from `renderToBitmap`, plus `onRasterPixelsChanged(rect)` firing after every raster mutation
  and **before** the redraw that presents it. Its ends are exact by construction (`grey/255 <
  (threshold+0.5)/256`, not `grey < threshold`, or pure black would speckle). `DITHER_GAMMA` is
  the one knob and is 1 until a hand says otherwise. `PencilInk` and `GraphiteGrain`'s density
  dial stay as an unused seam; the stroke's stored colour never moved through any of this, and
  BOOX, the generic engine and Paintsprout were never touched.
  **(5) A whole-page rebuild is DEFERRED and the present waits for it (0.1.42).** A
  two-raster page is two `loadPageRaster` calls, each announcing the whole page and each
  presenting after — two ~500 ms rebuilds on a Nomad with a frame between them showing
  graphite and no ink (*the pencil first and the ink a moment later*). So `rect == null`
  posts a runnable, the second call folds into the first, and `redrawCommitted` is held back
  while one is pending (`DitherCoalescer`, pure, JVM-tested) — one rebuild, one present, of
  one correct picture. **Per-rect rebuilds stay synchronous**: each precedes a present that
  is already on its way, and a rect deferred is a mark that appears a frame late; a rect
  arriving while a whole page is pending is subsumed by it. Anything that cancels the posted
  runnable — the panel closing, a `pageMode` flip — must `reset()` the coalescer too, or
  every frame the view ever presents again is swallowed by `deferRedraw`. And the flatten
  itself is bulk work now (`DitherFlatten.band`, pinned against the per-pixel `black` pixel
  for pixel): the blue-noise row fetched once per row, the gamma a table, an absent layer a
  flag rather than a page-sized zero-fill, and the page's own rows landed with one
  `copyPixelsFromBuffer` instead of two and a half million `Int`s through `setPixels`.
  **And how the bytes reach the bitmap is a SIZE decision, not a whole-or-rect one
  (2026-09-19).** The byte array is the page's rows permanently and every rebuild fills it
  before anything reaches the bitmap, so a rect from an eighth of the page upward takes the
  same one-memcpy copy the page does — past that share the fixed copy beats the per-pixel
  expansion `setPixels` wants (`DitherCost`, pure). The invariant that makes it legal is
  that the array is never behind the bitmap; it is allocated and dropped *with* the image,
  because one left over from the previous page would be landed whole onto a fresh one.
  **A size decision AND a count one**: a mark's runs arrive as one batch, flatten one at a
  time — the ink's area, never the union's — and land together, by the page copy when the
  union is large **or** when there are more than `DITHER_MAX_SETPIXELS_RECTS` (8) of them,
  because a scribble's sixty-four small `setPixels` calls were 651 ms of pen-up with not
  one of them slow enough to log.
- **A live preview may lay a fleck only where the bake will put one — which makes
  `GraphiteGrain`'s PREFIX the contract, not the whole (Phase 28).** `of(points, …,
  prefix = true)` returns an exact ordered prefix of the finished stroke's grain, so a
  direct-panel preview draws each fleck once and never moves it. Getting there needed the
  file to be honest about lookahead: the running filters were always causal, but the two
  filter **seeds** were means over the first 40 and 150 px of travel, and a seed read from
  150 px ahead makes the opening of a mark depend on path the pen has not travelled yet.
  Both are now capped at `SEED_WINDOW_PX` (50 px, the reach the arrival trim already
  needed), in **both** modes — a prefix and a whole stroke must not take different paths
  through the file, or the invariant belongs to the caller rather than to the grain. Below
  that much settled travel prefix mode lays **nothing**: not yet decidable beats laid in the
  wrong place when the paper cannot be repainted. **Anything that looks ahead is a lookahead
  even when it is called a seed** — and the cost of this one was invisible for fifteen
  releases because every renderer drew whole strokes.
- **A preview that re-derives the whole stroke each event is quadratic in the stroke, and the
  hand feels it (Phase 28).** Asking `GraphiteGrain.of(…, prefix = true)` per MotionEvent
  re-decides every station already on the panel to find the one or two that are new: 4352 ms
  of UI thread over a 1252-event slow stroke on a Nomad, 3.5 ms an event and rising with the
  length. `GraphiteGrain.Sweep` resumes the station loop instead and returns only the new
  flecks (3402 ms → 6 ms for the same stroke on a JVM). **The loop stayed one loop** — `of`
  and `Sweep.extend` both run it over a `SweepState`, because two copies of a loop that lays
  every pencil mark on every engine is a thing that drifts, and the day one of them misses a
  later constant the preview and the bake stop being the same mark. What *permits* the resume
  is that nothing in the file looks ahead: a station decided now is the station the finished
  stroke will have. And before touching it, nine strokes' committed grain was pinned as a
  checksum over every coordinate's raw bits (`GraphiteGrainPinTest`) — every other test there
  states a *property*, and a property can go on holding while the mark it describes quietly
  moves.
- **The raster eraser's mid-sweep cadence is PER ENGINE, because a redraw does not cost the
  same thing on two panels (Phase 19, 0.1.32).** `rasterEraseRedrawIntervalMs` is a
  `protected open val` the base reads in `throttledEraseRedraw`; `RASTER_ERASE_REDRAW_END_ONLY`
  (`Long.MAX_VALUE`) means no mid-sweep redraw at all, and `finalizeEraseRedraw` presents the
  final state once whether or not one ever ran. Onyx keeps 16 ms — one frame — because its
  engine answers `presentRasterEraseProgress` with a regional `handwritingRepaint` of exactly
  the rubbed corridor. **Supernote has no regional-refresh transaction to ask for**, so the
  phase opened at 100 ms expecting the frame-silence rule to bite. **It does not bite on an
  erase sweep** (Nomad, 2026-09-15): that rule's cost is the *masking* an overlay imposes on
  frames presented under it, and an erase contact releases the overlay at `ACTION_DOWN`, so
  nothing accumulates — what is left is the panel's own update, which the Nomad keeps up
  with. Measured: 100 ms good (113 frames / 20 % janky per minute), 60 ms better (162 /
  22 %), **16 ms the artist's clear choice at 756 / 82 %** — *"this eraser works better on
  Ratta hardware than it does on Onyx."* **The frame count is far worse and the hand is
  right**, which is only a contradiction if you were judging the frames. So both engines sit
  at 16 ms for entirely different reasons, and **agreeing on a number is not sharing a
  reason** — the seam stays rather than collapsing back into a constant, because the next
  panel will have its own answer. Ratta's 16 is the private `RASTER_ERASE_REDRAW_MS` in
  `RattaPaperView` since Phase 21 (0.1.34); the `setprop` door it was walked behind is gone.
- **A firmware preview cannot lean, so on Ratta the `PENCIL` bakes upright (Phase 22, 0.1.35).**
  `GraphiteGrain` widens a leaned lead up to ~11×; the Supernote live line is one width whatever
  the tilt, so a hairline drawn at a writing angle baked 10–15× wider than it previewed (found on
  the Manta; the Nomad walk had been at an upright grip — both deliver `AXIS_TILT`). The `bakeTilt`
  seam sits beside `bakePressure` for the same reason: **the preview and the bake must agree, and
  where the hardware cannot vary something the bake gives it up.** Before tuning an EMR size
  against a width mismatch, ask how *large* the mismatch is — an EMR error is tens of percent,
  a lean error is an order of magnitude.
- **A measurement door is temporary by construction — it closes when the measuring stops
  (Phase 21, 0.1.34).** `RattaTuning` existed so a walk could switch candidates with `setprop`
  and a restart rather than a rebuild each, because a judgement of *feel* made against a stale
  memory of the previous candidate is no judgement. That is a reason to open a door, not a
  reason to keep one: a mutable global holding a number the hand has already settled is a
  behaviour nothing in the tree can be reasoned about from, and its very existence invites a
  host to "configure" what was measured. So each of the four values froze into a private
  constant **where it is read** — `RASTER_ERASE_REDRAW_MS`, `PENCIL_PREVIEW_GREY` and
  `PENCIL_BAKE_PRESSURE` in `RattaPaperView`, `RattaEmr.EMR_MIN_HAIRLINE` used directly — each
  carrying its measurement in its KDoc, because **the number is worth nothing without the walk
  that produced it.** Re-opening one of these questions means another walk and another door,
  not a knob left standing for a walk nobody has scheduled. (Phase 23, 0.1.36: one of
  those, `PENCIL_PREVIEW_GREY`, is gone — not re-opened but *outgrown*, when the pencil went
  from one lead to fifteen and one constant could no longer answer. Its measurement lives on as
  a rung of `RattaInkMap.pencilPreviewFor`, which still answers DARK_GRAY for the lead the walk
  was run on.)
- **A preview can only be honest about what the firmware can vary; where it cannot vary tone,
  the BAKE gives up tone rather than the preview lying (Phase 19, 0.1.32).** The Supernote
  firmware paints one tone per armed pen. Three rounds on the Nomad tried to make a
  pressure-toned pencil agree with its preview from the preview's side — a darker grey, then
  a lighter one, then the pressure-sensitive pen codes — and none could, because the codes vary
  *width* and the greys are fixed per arming: a lightly drawn line previewed dark and baked
  pale every time. So `bakePressure(style, pressure)` (a `protected open fun`, identity by
  default, applied in `compositeIntoRaster` — the one place a raster page is written) lets
  Ratta bake `PENCIL` at a constant **0.5 against a DARK_GRAY preview** — the artist's *"spot
  on"*, on the panel and at 3× in a screencap — and the mark on the panel is the mark drawn.
  **The bake was never wrong** — it was right about a tone the panel could not show while the
  pen was down, which is a different thing and takes a different fix. Onyx and Paintsprout keep
  the pressure pencil, because their preview can carry tone; and **stroke mode is untouched on
  every engine** — the pressures in a `Stroke` are the host's data and must be the measured
  ones.
- **One tone per arming is a limit on a single mark, not on the set of them (Phase 23, 0.1.36).**
  Phase 19's answer — a constant preview grey for `PENCIL` — was right for a pencil with one
  lead, and stopped being right the moment NSE · Sketch grew fifteen shades: a black lead and a
  pale one previewed identically, so the preview lied about the choice the artist had just made.
  A firmware that cannot vary tone *within* a stroke can still vary it *between* strokes, and
  what the hand needs to see is that the pick took. `RattaInkMap.pencilPreviewFor` is therefore a
  **second ladder beside `firmwareColorFor`, with its own thresholds** — because a scatter of
  flecks baked at constant pressure reads lighter than a solid line of the same colour, so the
  nearest-grey question has a different answer for a pencil, and bending the shared thresholds to
  fit would have broken every other style's pen-lift handoff to fix one. For every grey it tops
  out at GRAY and never answers LIGHT_GRAY: a mark that is invisible *while it is being drawn*
  is worse than one that previews a shade off, because the hand aims with it. **Its rungs are
  the artist's hand on the Nomad (2026-09-17): 0–2 BLACK, 3–6 DARK_GRAY, 7–14 GRAY** — the
  first guess put 7–9 on DARK_GRAY and they previewed darker than they baked; LIGHT_GRAY was
  trialled for 12–14 and rejected by the same hand, so the pale end previews a shade dark and
  stays visible. DARK_GRAY still carries `#505050`/`#555555`, Phase 19's pairing. **A white
  lead is the one LIGHT_GRAY (Phase 27, 0.1.40)**: it lays nothing on bare paper and pales
  graphite under it (flecks go down `SRC_OVER` on the raster), so the faintest panel tone is the
  truthful preview and a GRAY trail vanishing at pen-lift would have said the opposite.
- **A hairline needs a lower firmware floor than a pen does (Phase 19, 0.1.32).** `RattaEmr`
  (pure, JVM-tested) clamps `px * 100` to 200…9600 for every style but `PENCIL`, whose floor is
  `EMR_MIN_HAIRLINE` (120, the Nomad's answer — see Phase 24 for the ceiling). The general floor exists
  because an EMR near zero paints a sub-pixel line that reads exactly like a dead firmware
  path — but the sketching pencil is a 1.2 px lead, and at floor 200 the firmware previews it
  as a 2 px needle and the mark visibly narrows at pen-up. **A preview that lies about width is
  the serious failure** (the BOOX `CHARCOAL` lesson, from the other direction): width is what
  the hand aims with, and the collapse is read as the *bake* being broken.
- **A limit nothing ever reached is not a measurement, and it will be believed anyway
  (Phase 24, 0.1.37).** `RattaEmr.EMR_MAX` carried 1200 from the PoC through Phase 19 with the
  reason *"the panel gains nothing above it and the daemon lags"* — a sentence stating two
  findings, **neither of which had been made**: nothing had ever armed an EMR above 1200,
  because in a world whose widest lead was 12 px nothing ever asked to. It survived Phase 19's
  rewrite, a floors-and-ceiling JVM test, and the Phase 21 freeze that went through these very
  constants pairing each with its walk, because a clamp nobody hits is invisible from every side
  — the only thing that can expose it is a host finally asking for more. Arc 44 did, and **all
  seven wide leads (16 / 20 / 24 / 32 / 48 / 64 / 96 px) preview at the width they bake with no
  lag at any of them** (the artist's hand, Nomad, 2026-09-17); Supernote's own notes app sits
  around 24. The ceiling is now 9600 and its KDoc says the thing the old one did not: **96 px is
  the widest lead a hand has walked, not a width the panel refused.** Two standing rules fall out
  of it. A bound stated as a device finding must name the walk that found it or say plainly that
  it is a guess — `EMR_MIN_HAIRLINE`'s KDoc does this and is why nobody has had to re-derive 120.
  And when a clamp is the suspect, ask first whether anything has ever *touched* it: a wrong
  ceiling and a right one are indistinguishable until the day something reaches them.
- **`loadPageRaster` and `swapPageRaster` need no Ratta override, for two different reasons
  (verified Phase 19 — not a gap).** `loadPageRaster` is a content swap and a page turn calls
  `clearForContentSwap` first, which bakes and releases the overlay under the swap law before
  any pixel moves. `swapPageRaster` is an **undo**, which arrives with no swap in front of it —
  the mark being taken back is very likely still live firmware ink — and what covers it is
  `redrawCommitted`'s own `pendingBake` guard, in the order the law requires: re-record the
  page image (already swapped), *then* `clearAll`, *then* present, then arm the ladder. The
  undone mark's overlay ink goes with the clear.
- **The same renderer does not make the same pixels on a different rasteriser (Phase 13).**
  `StrokeRenderer` into a hardware `RenderNode` (the committed layer) and into a software
  `Canvas(bitmap)` (`StrokeRasterizer`, covers, the raster page) lay the hairline pencil with the
  same flecks in the same places and about 40 % apart in tone — a round dot under 1.2 px is where
  GPU and CPU coverage part company. Measured on the NoteAir5C by diffing the same rows both ways.
  The artist chose the software tone for raster pages, so this is a fact to carry, not a bug to
  fix: never claim a software bake is pixel-identical to the panel, and diff it before saying so.
- **Transform mode rides the lasso entries (Phase 15, 0.1.27).** `beginTransform` puts one host
  object under handles + a rotate knob, and the mode is carried entirely by the shared
  selection entries device engines already call — `lassoTryBeginDrag` / `lassoDragMove` /
  `lassoDragFinish` / `lassoDragCancel` / `lassoOutlineStart` / `selectionBoxContains` /
  `isSelectionDragActive` / `hasActiveSelection`. That is deliberate: it is what gives the Onyx
  raw path and the Ratta firmware suppress the mode with **no device-module change**, so a fix
  to transform input goes in those entries, never in a device override. Every gesture sample is
  computed from the box the contact began on (`TransformGeometry`, pure, JVM-tested) — nothing
  accumulates. The host persists on `onTransformEnded` only; `onTransformChanged` is a live
  working-copy update the engine repaints through `drawObject`.
- **The pure half of a texture belongs in `geometry/`.** `GraphiteGrain` decides *where the
  graphite lands*; `StrokeRenderer` only puts ink there. That split is what lets determinism be
  proved by a JVM test instead of asserted, and it is the pattern any later textured style follows.
- **Draw a texture as one call per darkness, never per speck.** `PENCIL` issues
  `GraphiteGrain.LEVELS` (3) `drawPoints` calls however many thousand flecks a stroke holds —
  a page of pencil re-records in a frame because of this. Few darknesses is also the *right*
  answer on EPD: tone is meant to come from how many flecks land, and more levels quietly turns a
  spatial texture back into a tonal one and hands the panel greys to dither. Paintsprout's Wacom
  app reached the same rule from the other side (`BlurMaskFilter` on a software canvas measured
  twice its single largest per-frame cost; its grain is meshes with per-vertex colour).
- **BOOX's per-pen-kind width multipliers are not cosmetic, and a live style must be chosen for
  width before texture.** The firmware's texture pens scale the width they are given before rendering
  — `CHARCOAL_STROKE_WIDTH_EXTRA_SCALE = 5.0`, `BRUSH_STROKE_WIDTH_EXTRA_SCALE = 2.0` — because their
  grain bitmaps are scaled to the stroke and below roughly 20 px no texture exists at all. `PENCIL`
  armed against `STROKE_STYLE_CHARCOAL` therefore previewed a 6 px lead at about 30 px and committed
  6, and the mark collapsed to a fifth of itself at pen-up (measured NA5C, fixed in 0.1.8 by arming
  the plain even line, style 0). **A preview that lies about width is far worse than one that lies
  about texture: width is what the hand aims with, and the artist reads the collapse as the *bake*
  being broken.** Any style armed against a texture pen must account for its scale factor or accept
  the same collapse — `CROSS` still arms charcoal on purpose, because there it is approximating a
  texture rather than a width.
- **Tilt is supplied per measured model and zero everywhere else (Phase 11).** There is no
  `getMaxTilt()` in the SDK and the fleet reports the raw numbers on incompatible scales (one model
  in the thousands), so `gpaper-onyx` carries an allowlist of models whose tilt has actually been
  *measured* and reports `0` for the rest. **Measured on the NoteAir5C: `hypot(tiltX, tiltY)` is
  degrees from vertical, directly** — a hand at a deliberate 45° read 44.3 over 1400 samples.
  **Adding a model to that list is a measurement, never an inference from a similar-looking one.**
  `tilt = 0` is not a degraded mode — it means a pencil held upright — so a renderer must still look
  right there, and an unmeasured device gets a fixed-width pencil rather than a broken one.
  **Since 0.1.24 the Onyx engine reports zero on the measured model too** (`REPORT_TILT`): the
  artist sketched with the tilt-driven pencil and rejected it. The measurement stays in the source
  because it is a measurement; the policy of driving width from it is what was withdrawn.
- **A live preview that lies about WIDTH is far worse than one that lies about texture**, because
  width is what the hand aims with — and the artist reads the collapse at pen-up as the *bake* being
  broken, which sends the search to the wrong half of the system. `TouchHelper` exposes only
  style/colour/width (verified by `javap`), so a textured live style cannot be had without whatever
  tilt response it comes with — which cuts both ways: to take the tilt out of a mark you must give
  up the texture in the live ink, and 0.1.24 did exactly that (`PENCIL` arms style 0 again).
- **A firmware style is a target only if the artist has approved the firmware style.** Phase 11
  fitted fourteen releases to `CHARCOAL_V2` — width curve, overdraw, density, all measured — and the
  artist rejected the result whole, because the charcoal stamp itself never looked like a pencil and
  nobody had asked. Each round was measurably right and the whole was wrong. Fit to a reference the
  artist has said yes to, never to a style because it happens to be textured. Phase 12 has the record.
- **A fleck is never wider than the lead that lays it (0.1.24).** Rendered offline, a 1.2 px lead
  under 1.6 px flecks baked at more than twice its live width. `fleckPx(level, width)` caps at the
  lead; renderers use that form. Render a new lead size to a PNG before it reaches a panel — this is
  the second flaw that habit has caught that no unit test would have.
- **A cross-section must be laid across the SMOOTHED direction of travel.** Taking the pen's
  direction from one adjacent pair of raw samples measures jitter, not travel: at 2 px spacing,
  0.35 px of digitizer noise swings it ~14° sd, past ±35°. Every cross-section of grain is rotated by
  that much and **the error is multiplied by the half-width of the mark** — on a wide stroke it throws
  grain tens of pixels out of line and the mark grows bristles ("pipe cleaner", 0.1.16). Smoothed over
  ~10 px of arc, causally. Paintsprout's Wacom app builds its mesh normals the same way and has the
  same fault.
- **A rounding that is harmless in bulk can dominate at a boundary.** `laneCount` rounds up so a
  hairline still gets grain — invisible in a stroke's body, where lanes number in the dozens, and
  badly wrong in its cap, where strips narrow to a tooth or two and that one extra lane doubles their
  density. The excess landed on the cap's outline (its outermost lanes sit there by construction) and
  drew a dark bead round the end of every stroke. Ask for coverage per unit of **area**, not per lane
  (0.1.19), and stop a cap while its strips are still a tooth wide.
- **A pen's arrival is not a mark — drop it, do not filter it.** Touch-down leaves a small path
  excursion; on a laid-over lead the mark folds across itself there and composites to solid black
  (0.1.22). The renderer skips to the last sample within 25 px travelling more than 60° off the
  stroke's eventual direction. **Trimming only the backward steps is not enough** — the kink where
  the path rejoins the line folds the mark just as badly. Seed every other filter from the *trimmed*
  start, or they are measured across the wobble they exist to be immune to.
- **A filter can lag or it can damp a sustained excursion — not both.** Damping the *path* to remove
  a pen-landing wobble was tried and reverted: a plain average lags, which shortens every stroke and
  pulls its end cap inside the mark, and a trend term that cancels the lag makes the filter track the
  excursion faithfully instead of absorbing it (measured: pile-up unchanged at every strength, clean
  baseline worse). Position noise is not tilt noise; the same tool does not work on it.
- **Overlap within ONE stroke composites to solid black.** A pen-landing wobble folds a laid-over
  mark across itself and leaves a knot. Curing it properly means a stroke depositing **once** on any
  paper — one alpha mask per stroke rather than fleck-by-fleck — since a lead drags rather than
  stamping twice; crossings *between* strokes should still darken. Not done.
- **When one input drives two outputs in opposite directions, they must not both be instantaneous.**
  Tilt makes a mark broader *and* paler, so read from the same instant a brief near-upright moment is
  ten times narrower and twice as dark at once — a black nub, and touch-down is where a pen is most
  often caught upright. Width follows the lean closely (40 px); darkness follows the lean the hand has
  *settled into* (150 px). Shape belongs to the instant, tone belongs to the grip.
- **Fix a class of bug everywhere it lives, not where it was found.** The tangent and lean filters
  were introduced together; the tangent's seed was corrected in 0.1.18 and the lean's was not, so it
  went on producing a differently-shaped version of the same artifact (a wedge instead of a hook) for
  two more releases. **A digitizer's readings at touch-down are its least reliable** — the pen is
  barely on the glass — so no filter may be seeded from the first sample.
- **A filter added to remove noise brings a transient of its own, and a stroke's start puts it on
  display.** The tangent smoother (0.1.16) was seeded from the first pair of samples and began every
  broad mark with a hook — the touch-down cap thrown along a wrong heading, plus the filter swinging
  as it converged, both scaled by the half-width. Seed from a **chord across the whole smoothing
  window** instead: no transient, because the seed is already where the filter would settle. Check
  the beginning of a mark whenever smoothing is introduced.
- **When a rendering fault survives redesigning the renderer, the renderer is not the problem** — the
  input, or the frame the output is placed in, is. Three releases of grain work changed nothing the
  artist could see, because the grain was never wrong.
- **An angular error is multiplied by the lever arm, so a texture tuned on a narrow lead is untested
  on a wide one (Phase 25, 0.1.38).** Two degrees of direction wobble is nothing at a mark's
  centre line and two station pitches at the rim of a 96 px lead — the combs piled up and the mark
  became "a series of tiny lines". Nothing in the grain had changed since it was approved at 12 px;
  the lever arm had. Any constant that scales with half-width (`LEVER_JITTER`) or with an angle must
  be looked at again at the widest lead a host offers. The way to look: dump `GraphiteGrain.of` from a
  JVM test and render it offline — the pure-Kotlin promise exists so a texture fault can be
  reproduced and *measured* (a histogram along the tangent found this one) without a panel.
- **Independent coin tosses are white noise, and white noise is not paper (Phase 25).** Once the
  comb was gone the mark was an even spray. A sheet's tooth comes in patches, and the same patches
  under every stroke — so the field is in **page** coordinates and seeded by a constant, never by
  the stroke. Anything that modulates coverage must leave total ink where the hand approved it;
  measure before and after.
- **Inspect a texture at the size it will be looked at.** This defect is invisible at 10× pixel zoom,
  where one bristle reads as ordinary speckle, and obvious at 1×. Every wrong diagnosis in that
  sequence came from magnifying past the scale the defect lives at.
- **Noise that becomes SHAPE must be smoothed; noise that becomes TONE need not be.** A digitizer's
  tilt jitters several degrees sample to sample and a hand cannot roll a pen that fast — fed raw into
  a width it fringes both edges of the mark with fine hairs ("pipe cleaner"). `GraphiteGrain` averages
  the lean over ~40 px of arc, **causally**, so a prefix still renders like the whole stroke; pressure
  stays raw because it sets darkness and darkness noise reads as grain. Paintsprout's Wacom app has
  the identical fault (`tiltGain` off raw tilt) — **the shared symptom across two unrelated renderers
  is what identified the shared input.** When a rendering fault survives redesigning the renderer,
  suspect the input.
- **Graphite laid down as CONNECTED geometry looks like hair.** A grain fleck wider than the lattice
  that spaces it must touch its neighbours, and touching flecks become little worms a fleck thick and
  several long — half a millimetre of bristle at 300 dpi, which an artist called a "pipe cleaner".
  Paintsprout's Wacom app has the same fault from the other direction (its grain is continuous
  *lanes* along the stroke). The cure (0.1.14): size the fleck **against the pitch and ramp it by
  darkness** — about one pitch at the pale end so specks stand alone, over two at the dark end so
  they flood into solid ink. The panel's own charcoal, magnified, is essentially a one-pixel dither;
  nothing in it is connected.
- **The bake is `screencap`-visible even when live EPD ink is not**, and marks re-render from stored
  data on open — so a pixel-exact image of what the *current* renderer makes of an old drawing is one
  `adb exec-out screencap`, no camera needed. Reach for that before photographing anything.
- **An aggregate statistic cannot see structure — magnify the texture and compare it.** Mass, extent
  and coverage were all matching the panel's ink while our grain was plainly wrong: the flecks were
  combed into short dashes running *along* the stroke where the panel's speckle is isotropic. A
  fleck wider than the lattice pitch that spaces it fuses with its neighbour in the same lane, station
  after station, and the mark grows a direction graphite does not have. Fixed in 0.1.13 by sliding
  the comb sideways a random fraction of a lane at every station. **Any lattice-placed texture needs
  something that decorrelates it along the path**, or it will comb.
- **A combed texture does not look like an even scatter at the same coverage**, so a density
  judgement made over a structurally wrong grain is not worth acting on — fix the structure first,
  then re-judge density.
- **Scale a texture's pitch and its grain size by the same factor** and coverage, and therefore
  density, is untouched — which is what lets grain fineness be tuned independently of how dark a
  mark is.
- **A binary threshold cannot measure a texture whose nature is partial coverage.** Comparing a
  photographed live stroke against its bake, thresholding high enough to segment cleanly made the
  baked bands read ~30% narrower — the threshold was discarding pale outer flecks, and it inflated
  the coverage measured inside the band at the same time, so width and density were entangled and
  both wrong. Threshold-free statistics settle it: **total ink mass per unit length**, and the
  profile's second moment. At a threshold low enough to keep the pale flecks the extents matched
  within 4%.
- **`Stroke.width` means the width of the mark — protect that when a firmware disagrees.** BOOX's
  `CHARCOAL_V2` overdraws ~1.3× (measured NA5C, constant across pen angle; corrected in 0.1.12 by
  dividing the width `gpaper-onyx` hands `setStrokeWidth`; the divide left with the style in 0.1.24,
  the rule did not). The live style and the bake are coupled
  through one `penWidth`, so only one of them can be corrected — **correct the engine, never widen
  the renderer.** Widening the bake to meet a firmware would make `Stroke.width` a per-device
  fiction, and a host compositing its own ink through `StrokeRasterizer` would get a different
  answer from the one on screen. It also costs the live ink nothing: a host that scales its pen
  widths to suit hands the firmware the same number as before.
- **When a correction is aimed at one axis, keep it flat across the others.** 0.1.11 raised grain
  density by a factor deliberately constant over the whole pressure range, because the light-to-hard
  response had already been approved — a density fix that also moved pressure would have undone a
  settled decision while appearing to fix something else. The same logic pins the upright width
  anchor when the tilt curve is retuned.
- **Measure the device before explaining it.** Phase 11 lost a round trip to an inference from
  `NoteConstant.CHARCOAL_STROKE_WIDTH_EXTRA_SCALE` — a constant BOOX's *own app* applies, reasoned
  from a finding about the NeoPen *software* renderers, a different code path from the firmware
  overlay — stated as if it had been measured. It lost another to writing off `CHARCOAL_V2` on a
  one-line survey description; that style turned out to be the one the artist wanted.
- **Pen-activity gate includes hover.** `isPenActive` = writing ∨ hovering + 350 ms tail — the palm
  lands before the pen tip, so proximity must close the gate. Traps: stylus hover is delivered
  to `onHoverEvent` first (pointer-source) — and since the paper view is not hoverable it returns
  false, so the platform then delivers the SAME MotionEvent to `onGenericMotionEvent` too. Handle
  both entries, but process pointer-source hover only on the hover leg (core's
  `isPointerSourceHover` predicate), or every sample is handled twice — and any per-event mutation
  (Ratta's `offsetLocation` registration shift) is applied twice; tap-like host
  gestures must re-check the gate at finger-**up**; and tap-*actions* must commit after a
  `PEN_ACTIVE_TAIL_MS` **escrow** (drop if the gate closes meanwhile) — a palm micro-tap can
  complete ~190 ms before the pen enters hover range (measured NA5C), invisible to any proximity
  signal at up-time. Contact size is no substitute: EPD touch panels may report zero
  size/touchMajor with palms classified as plain finger (NA5C does).
- **Onyx proximity is off by default.** With the raw pipeline open, BOOX delivers NO pen-approach
  signal at all — no hover MotionEvents, no `onPenActive` — until
  `TouchHelper.setPostInputEvent(true)` (bytecode-verified master switch, default off; set in
  `openRawDrawing`). With it on, subscribe `TouchHelper.register(...)` to the SDK event bus for
  `PenActiveEvent`/`PenDeactivateEvent` (greenrobot; enter/leave EMR range, 100 ms-timeout exit) —
  these feed `markPenInRange`/`markPenOutOfRange` on the shared gate. Events arrive on the raw
  input thread; the gate fields are volatile. **The bus is the ONLY safe level feed:** the SDK
  marshals the host-facing `onPenActive` callback through the view's Handler, so a backlog of
  posted reports can flush AFTER the raw-thread `PenDeactivateEvent` and re-latch the gate closed
  forever (measured NA5C — ~50 stale reports after the deactivate while main was busy completing a
  lasso; finger selection drag/dismiss stayed refused). While the bus is subscribed the callback
  must contribute nothing; it degrades to pulse-tail semantics only when the subscribe failed.
  `gpaper-onyx` ships `consumer-rules.pro` (EventBus `@Subscribe` keep rules) so minifying
  consumers can't strip the subscriber and lose the exit signal.
- **Ratta bake-handoff ordering (fourth overlay law, found here — not in the reference).** The
  deferred bake must issue `clearAll` **between** the node record and the `invalidate`
  (`RattaPaperView.redrawCommitted`): the daemon pairs a clear with the next app frame it sees, and
  a clear issued after the invalidate can pair with a stale in-flight frame recorded *before* the
  bake — the overlay drop then reconciles against stroke-less pixels and the just-written ink
  visibly vanishes until a later repaint damages the region. The reference never hit this because
  its hosts present no frames mid-writing; g-paper hosts MAY present frames at any moment (the
  demo's raw counter did at input rate until the Phase-9 frame-silence rule below deferred it),
  so the clear ladder also arms after **every** bake handoff — post-bake, every
  possible frame contains the strokes, making retry pairs harmless when the handoff landed and a
  ≤450 ms self-heal when it didn't.
- **Ownership guards are process-local; a cross-process handoff must DROP the token (0.1.2).**
  `penOwner` (Onyx) / `inkOwner` (Ratta) are statics — a successor paper screen in another
  process (Notesprout Paper's scratch-pad extension) can never overwrite them, so every
  later teardown of the departing view (focus loss, detach, `release()`) would still fire
  against the device-global pipeline, *after* the successor's claim. Onyx's
  `closeRawDrawingIfOwner` already nulls the owner + `isSetup` on `releaseForHandoff()`;
  Ratta's `releaseForHandoff()` now does the full teardown (overlay release, full-screen
  disable, `enableFullUiAuto(false)`) and nulls `inkOwner`. Symptom before the fix: the
  caller's session stayed live but the panel left full-UI-auto ≈ 200 ms after the reclaim
  → every drag frame on the slow waveform until a later re-arm ("sluggish drag", Nomad).
- **The Onyx fast-mode pin is per-session, not per-PEN (0.1.3).** `applyToolState` pins the
  app-scope handwriting waveform for every drawing tool, not only when PEN arms: a pipeline
  (re)opened on the LASSO (a host resuming after a cross-process handoff while a selection flow
  left it on the lasso) otherwise ran every drag frame unpinned — the "sluggish drag after a
  transfer" on a NoteAir5C, cured by any later PEN arming (dismiss → pen → reselect).
- **Pen-gesture recognizers are shared-base machinery too** (Phase 9): the geometry gates live
  once in pure-JVM `geometry/GestureRecognizer`, detection sits at the single commit point in
  `CanvasPaperView.commitCapturedStroke` (mid-contact exclusion fragments pass
  `allowGestures = false`), and engines contribute ONLY ink retraction via the
  `onGestureStrokeConsumed` seam (Onyx: render-off + dismissal repaint + retry at contact end —
  withheld-frame rules; Ratta: the gesture-trace clear ladder). Two hard-won classification facts:
  **scribble-shape screens the smart lasso and is exclusive** — real zigzag scribbles routinely
  satisfy the loop gates too (closure + winding; a spiky coil passing BOTH gate sets is pinned in
  a JVM test), so a dense oscillating stroke is never a lasso and an empty-hit scribble falls to
  ink, never to lasso; and **the winding gate cannot reject closed retraces** — nearly any closed
  path winds ≥360° around its own centroid (a flat hairpin is topologically a thin loop), so the
  empty-hit-test fallthrough is the real guard against loop-shaped false positives. Component-
  initiated tool changes (smart-lasso LASSO switch, PEN restore at session end) fire
  `PaperListener.onToolChanged`; the restore can land AFTER `onSelectionDismissed` (tap-away
  dismisses at pen-down, restores at pen-up) — never advise hosts to read `tool` in selection
  callbacks.
- **EPD frame-silence rule (Ratta-critical):** pixels under Supernote overlay ink are frozen
  against app updates, so every app frame presented mid-writing pays a masking cost that GROWS
  with the accumulated unbaked overlay ink — felt as progressively lagging ink (measured Nomad;
  Manta merely later). The reference never hit it (its hosts present no frames mid-writing).
  Hosts must present NO frames while `isPenActive` — and note the raw `HOVER` stream arrives at
  input rate on Ratta/generic (EMR pens hover between every stroke; on Onyx hover never reaches
  the view as MotionEvents), so chrome must never repaint from `MOVE` OR `HOVER` raw events. The
  demo's gate-deferred status line is the reference implementation; `dumpsys gfxinfo` is the
  measuring stick (1182 frames/65% janky in one short writing session before the fix, ~30–50
  per session after).
- **Selection/lasso is shared-base machinery** (`CanvasPaperView`): device engines add only trail
  chrome + EPD drag handling, driving the base's protected seams from their pipelines. Trails are
  engine chrome, never model data. The stylus-only contract has ONE exception: while a selection is
  active, a single finger drags it / a finger tap dismisses it (palm-gated: `isPenActive` refusal,
  mid-drag pen cancel, multi-touch kill, `PEN_ACTIVE_TAIL_MS` escrowed dismissal) — hosts with touch
  listeners on the paper view must yield finger events while a selection is active. Host content
  joins drags via the optional `ContentRenderer` pair (exclusion-aware `draw` + `drawObject`);
  implement both or neither.
- **Firmware geometry is captured, not tracked** — a resize (rotation, insets change) must
  re-push it: Onyx `setLimitRect` re-applies in `OnyxPaperView.onSizeChanged` (posted — mid-layout
  `getLocationOnScreen` lies), Ratta re-runs `setupFirmwareInk` for its screen-space disable areas.
- **Onyx: frames presented during a live raw contact are withheld from the panel**, and a pen-up
  `invalidate()` of identical content is damage-free — the panel never repaints. Any overlay-chrome
  change made at pen-down (e.g. tap-away selection dismissal) needs an explicit
  `handwritingRepaint` at contact end **plus a ~250 ms retry** (the immediate one races the SDK's
  end-of-contact processing and can be eaten). Related: the lasso trail render must arm at the
  first *move* sample, never at pen-down — a tap that arms the overlay freezes its own dismissal
  frame and its wipe loses that race.
- **Ratta: never issue an armed gesture-trace clear at ACTION_DOWN of an inking contact** — the
  daemon pairs the clearAll with a frame presented a beat *into* the new contact and eats its first
  ink (lasso-trail starts visibly wiped on the Nomad; the Manta's faster daemon hides it; latent in
  the reference, which flushed at down in all modes). Flush from the **hover stream** instead (once
  per ladder arming, `flushArmedOverlayClearOnApproach`) — law 3's pre-contact channel; the
  down-time flush is kept only for erase contacts, whose overlay ink is unwanted anyway.
- The Ratta engine is selected only when Supernote hardware **and** the firmware ink binder are
  both present (`isRattaDevice() && SupernoteInk.isAvailable()`); absent either, selection falls
  through (that probe fall-through is engine *selection*, not a runtime fallback). With the binder
  absent, `RattaPaperView` itself degrades to generic-style behavior (`rendersLiveStrokes = !firmware`).
- Engine selection: explicit registration via `GPaper` (no ServiceLoader, no reflection). Engine
  choice happens once at creation, logged at `Log.i`; **never add a silent runtime fallback** —
  post-construction engine failures must be loud.
- Hosts own all data; ids are the join key. `clear()` fires no erase callbacks; page turns are
  `clearForContentSwap()` + `loadStrokes()`; `onPenLifted` is a save trigger only.
- **Host content and the eraser (0.1.4):** the eraser tool *reports* swept content whole
  (`onContentErased`, per-gesture dedup in `CanvasPaperView.reportedContentErases`) — the component
  never removes host content itself; the host deletes + `notifyContentChanged()`. Scribble erase
  stays stroke-only by design. `StrokeRasterizer` (public, `core/render/`) is the offline door to
  the internal `StrokeRenderer` — hosts compositing their own content (Notesprout Paper link
  composites) draw through it so baked appearance stays pixel-identical; never suggest a host
  reimplement stroke drawing.
- **The two tap signals are complements (0.1.5).** `onSelectionTapped` owns every sub-threshold tap
  *inside* an active selection box; `onPaperTapped` owns the stylus tap in `Tool.LASSO` with
  *nothing* selected (the host's paste-here hook). Neither ever fires for the other's case, and the
  tap that **dismissed** a selection fires neither — `lassoOutlineStart` latches
  `outlineDismissedSelection`, because a contact spent on a dismissal must not also be read as an
  empty-handed tap. `onPaperTapped` needs no escrow: the finger path (`handleFingerSelection`) only
  ever drags or dismisses an *active* selection, so a contact reaching `completeLassoOutline` is a
  stylus by construction.

## Toolchain (mirrors Notesprout)

- Gradle 8.14 (wrapper), AGP 8.11.1, Kotlin 2.2.20
- JDK 17 Temurin, pinned via `org.gradle.java.home` in `gradle.properties`
- compileSdk 35, minSdk 29, targetSdk 35 (demo)
- Jetifier + the insecure BOOX maven repo (`http://repo.boox.com/...`) are enabled since Phase 3
  for `gpaper-onyx` (onyxsdk-device 1.3.3, onyxsdk-pen 1.5.4, hiddenapibypass 4.3 — all
  `implementation`-scope). **Consumers that skip gpaper-onyx need neither**; consumers using it
  add both to their own build. The Onyx AAR manifests carry an application label — apps need
  `tools:replace="android:label"`. The demo ships arm64-v8a only + `libc++_shared.so` pickFirsts.
- **`gpaper-ratta` adds zero dependencies** — it drives the Supernote firmware's ink daemon
  directly over Binder (raw `Parcel` transactions, reflection on `ServiceManager`/the `eink`
  service). Consumers need no extra repo, no jetifier, nothing.

## Build & device testing

- `./gradlew build` — full build. `./gradlew :demo:assembleDebug` → `demo/build/outputs/apk/debug/demo-debug.apk`.
- **Publishing is mavenLocal-only** (Phase 6 decision): `./gradlew publishToMavenLocal` publishes
  `com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.5` + sources (coordinates in
  `gradle.properties`). `consumer-smoke/` is a standalone consumer project (NOT in the root build)
  proving a host app builds against the published artifacts:
  `./gradlew -p consumer-smoke assembleDebug` after copying `local.properties` in (see its README).
- Install to devices with the `device-build-install` skill (`.claude/skills/device-build-install/`),
  which holds the ADB serial + tier table. Users refer to devices by nickname (G10, MAX, SNN…).
- EPD pen overlays are **invisible to screencap** — ink behavior is verified by the user's eyes on
  real BOOX/Supernote hardware. App UI (non-ink) does show up in screencap. The **generic engine's
  ink is ordinary View rendering and IS visible to screencap** (useful even on EPD devices while no
  device adapter is registered).
- adb `input` injection cannot exercise stylus paths: injected events carry toolType UNKNOWN on
  Supernote (both `input tap` and `input stylus swipe`), so the engine ignores them. Injected taps
  do drive click listeners — fine for toolbar/UI checks; pen behavior needs real hands.
- The Supernote Manta reports itself as a Nomad in every `ro.product.*` property; the ADB serial is
  the only reliable way to tell them apart. `Build.MANUFACTURER` is `"Supernote"`, not `"ratta"`.
- BOOX devices spam logcat (`test_keymap` etc.) hard enough to wrap the buffer in seconds — debug
  with `adb logcat -G 16M` plus a **streaming** filtered capture (`logcat -s TAG`), never `-d` after
  the fact. Also a general BOOX trap (reproduced on NA5C and G102): `install -r` + immediate
  `am start` can race package finalization, leaving the package installed but **disabled**
  (`enabled=3`, "Activity class does not exist") — heal with `pm enable <pkg>`.
- BOOX has a real status bar overlaying the window top (Supernote has none) — host layouts must
  apply system-bar insets; the demo pads its root via `setOnApplyWindowInsetsListener`.

## Working style

- Deliberately slow and careful; frugal with background agents/tokens — work mostly inline.
- Each phase: plan → build → test → commit & push when green. Update `PLAN.md` statuses the moment
  they change; fold new durable knowledge into this file at phase close-out.
