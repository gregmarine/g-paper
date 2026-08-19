# g-paper Public API

> The guided tour of the host-facing surface, as of **v0.1.1**. The authoritative surface
> is the code in `gpaper-core/src/main/java/com/symmetricalpalmtree/gpaper/core/` (KDoc
> included); this document must be kept in step with it. All three engines are live and
> device-verified: generic Canvas, BOOX (`gpaper-onyx`), Supernote (`gpaper-ratta`) —
> both device views subclass the shared `CanvasPaperView` base, so committed rendering,
> erase and lasso hit-testing, and the stroke model are literally the same code
> everywhere. Hosts never touch `canvas/` directly: `GPaper.create(context)` works with
> zero registration calls on generic devices; BOOX apps add one
> `OnyxEngine.register(application)` call, Supernote apps one `RattaEngine.register()`.
> Build setup per device family: [integration-guide.md](integration-guide.md). What the
> host owns: [host-responsibilities.md](host-responsibilities.md). Internals:
> [architecture.md](architecture.md).

## Philosophy

g-paper is **paper**: it captures stylus input and renders what it is told to render.
The host app owns all data, persistence, pages, undo/redo, gestures, and app logic.
The component holds an in-memory working copy of loaded strokes (for rendering and
hit-testing) and reports every change out through callbacks; the host mirrors those
changes into its own storage, keyed by stroke id.

## The surface at a glance

| Area | Types |
|---|---|
| Contract | `PaperView` (interface every engine implements) |
| Data model | `Stroke`, `StrokePoint`, `StrokeStyle`, `Bounds`, `Selection`, `SelectionMove` — pure Kotlin, zero Android deps |
| Tools | `Tool` — `NONE` / `PEN` / `ERASER` / `LASSO` |
| Events | `PaperListener` (all default no-op), `RawInputListener` + `RawInputEvent` |
| Host content | `ContentRenderer`, `ContentLayer`, `HitTarget` |
| Engine selection | `GPaper` (registry + factory), `PaperEngineProvider` |
| Geometry utils | `Geometry` — segment distance, point-in-polygon, polyline-vs-circle |
| Interop | `Bounds ↔ RectF/Rect` extensions |

## Quickstart shape

```kotlin
// Application.onCreate — register only the device modules you ship (generic is built in)
OnyxEngine.register(this)  // gpaper-onyx — also installs the BOOX SDK's hidden-API
                           // bypass and heals EPD state leaked by a killed pen session,
                           // which is why it takes the Application and must run here
RattaEngine.register()     // gpaper-ratta — zero dependencies (direct firmware binder)

// In the hosting screen
val paper: PaperView = GPaper.create(context)          // best available engine
frame.addView(paper.asView())                          // chrome must overlay it in a FrameLayout

paper.setPaperListener(object : PaperListener {
    override fun onStrokeCommitted(stroke: Stroke) { db.save(stroke) }
    override fun onStrokesErased(ids: List<String>) { db.softDelete(ids) }
})

paper.setTemplate(templateBitmap)
paper.setPageSize(pageW, pageH)      // the rect the data was authored in
paper.loadStrokes(db.loadStrokes())  // host data in

// Host Activity lifecycle
override fun onResume() { super.onResume(); paper.resumeDrawing() }
override fun onDestroy() { paper.release(); super.onDestroy() }
```

## The data model

`StrokePoint(x, y, pressure = 1f, tilt = 0f, timeMillis = 0L)`
`Stroke(id, points, color = BLACK, width = 3f, style = PEN)` with an eagerly computed `bounds`.

- **Pressure and tilt are captured** on hardware that reports them;
  rendering may ignore them initially. Conventions follow `MotionEvent`: pressure `0..1`,
  tilt radians from vertical, `timeMillis` monotonic event time (not wall-clock).
- **Color is an ARGB Int**, width is px. No serialization opinions anywhere — the host
  persists strokes however it likes.
- `Stroke.translated(dx, dy)` is the sanctioned way to move/copy a stroke: it shifts
  points + bounds and preserves color/width/pressure/tilt/time (the Notesprout
  "two-arg constructor silently flattens ink" trap is impossible by design — style
  fields can only be dropped deliberately).
- Ids are host-meaningful strings, unique among loaded strokes. Fresh engine-captured
  strokes get random UUIDs.

## Data in / data out

| Direction | API | Typical use |
|---|---|---|
| In | `loadStrokes(list)` | Page load, undo/redo replay, rejecting a move |
| In | `addStrokes(list)` / `removeStrokes(ids)` | Targeted undo/redo, paste |
| Out | `getStrokes()` (any thread) | Save-all, export |
| Out | `onStrokeCommitted` / `onStrokesErased` | Incremental persistence |
| — | `clear()` | User-facing "erase page" (host updates its own data; no erase callbacks fire) |
| — | `clearForContentSwap()` | Page turn: pixels hold until the next `loadStrokes` — single EPD refresh, no blank flash |

**Undo/redo is host-owned**: the host keeps its history and replays via
the load/add/remove calls (patterns in [host-responsibilities.md](host-responsibilities.md)).
**Pages are host-owned**: one surface;
`clearForContentSwap()` + `loadStrokes()` is a page turn.

## Template & page geometry

- `setTemplate(bitmap?)` — background behind everything; not erasable, never reported
  as data; null = white. Handles the EPD repaint handoff internally.
- `setPageSize(w, h)` — the page-coordinate rect content was authored in (the creating
  device's surface size). The template stretches into **this rect, not the view**, so
  ink/template registration survives moving data between different-sized screens.
  `0×0` = stretch-to-view (default). Sticky until the next call.

## Host content extension point

```kotlin
class MyObjectsRenderer : ContentRenderer {
    override val layer = ContentLayer.BELOW_STROKES   // or ABOVE_STROKES
    override fun draw(canvas: Canvas) { /* paper coordinates */ }
    override fun hitTargets() = objects.map { HitTarget(it.id, it.bounds) }
}
paper.addContentRenderer(renderer)
// after any host content change:
paper.notifyContentChanged()   // batched: once per group of changes
```

`draw` is called only while re-recording the committed layer (never per frame), may run
on a software canvas (EPD repaint paths), and is z-ordered relative to the ink.
`hitTargets()` opts host objects into lasso selection.

## Tools, selection, and events

`paper.tool` ∈ `NONE | PEN | ERASER | LASSO`; pen via `penColor` / `penWidth` /
`penStyle`, eraser via `eraserRadius`. Finger input is never a tool — it passes through
to the host — with one narrow exception: while a lasso selection is active, a single
finger inside the box drags the selection and a finger tap outside dismisses it, both
palm-gated (`isPenActive` refuses the contact, a pen turning active mid-drag cancels
it, a second pointer kills it, and the dismissal commits after the
`PEN_ACTIVE_TAIL_MS` escrow). Hosts with their own touch listeners on the paper view
must yield finger events while a selection is active or the component never sees them.

### Pen types (`StrokeStyle`)

`penStyle` ∈ `PEN | FOUNTAIN | MARKER | PENCIL | BRUSH | CALLIGRAPHY | DASH | CROSS`,
stored per stroke as `Stroke.style`. These are **abstract** styles, deliberately not the device
SDKs' native codes: stroke data must be portable across devices, so **core's committed
renderer defines the true appearance** (engine-independent), and each engine maps the
*live* ink to the nearest native style its firmware offers — the live stroke is a
preview, the baked stroke is the truth.

The set is the union of what the hardware can genuinely approximate live, grounded in
the device surveys (Notesprout `docs/onyx-pen-tools.md`: all 9 Onyx firmware styles
verified on five BOOX devices; the Ratta 0…31 pen-code sweep on Nomad + Manta):

| Style | Committed (all engines) | Onyx live (firmware style) | Ratta live (pen code) |
|---|---|---|---|
| `PEN` | uniform width | `STROKE_STYLE_PENCIL` (0) | `NEEDLE` (10) |
| `FOUNTAIN` | pressure/velocity width | `STROKE_STYLE_FOUNTAIN` (1) | `INK` (16) |
| `MARKER` | uniform, semi-transparent | `STROKE_STYLE_MARKER` (2) | `NEEDLE` (10) |
| `PENCIL` | grain texture | `STROKE_STYLE_CHARCOAL` (4) | `NEEDLE` (10) |
| `BRUSH` | broad, pressure-modulated | `STROKE_STYLE_NEO_BRUSH` (3) | `INK` (16) |
| `CALLIGRAPHY` | chisel nib, direction-dependent | `STROKE_STYLE_SQUARE_PEN` (7) | code 15 (14 fallback) |
| `DASH` | uniform, dashed | `STROKE_STYLE_DASH` (5) | code 4 (dash stream) |
| `CROSS` | stream of small x marks | `STROKE_STYLE_CHARCOAL` (4) — nearest texture, no x-stream in firmware | code 3 (x stream) |

Ratta codes with no `StrokeStyle`: 12 is broken firmware-side (never armed), 6/7/9/13
render nothing, 0/5/8/11 are redundant solid variants of `NEEDLE`, 17–31 alias `INK`.
The lasso gestures' trail chrome stays engine-internal (the engines arm trail styles
themselves during selection), but both trail *appearances* are host-usable pen types:
`DASH` (native live on both platforms) and `CROSS` (native live on Ratta, approximated
live on Onyx, exact when baked — each module implements whatever comes closest).

Rendering lands incrementally: the model carries `style` from day one (no breaking
migration for hosts), but engines may render richer styles as `PEN` until their
committed renderer is implemented. All live mappings above are confirmed on-device
(BOOX Tier-1 fleet; Supernote Nomad + Manta).

Committed-renderer status at v0.1.0 (`core/canvas/StrokeRenderer.kt`):
`PEN`, `MARKER` (translucent flat-cap), `DASH`, `CROSS` (x-marks along the path), and
`FOUNTAIN` (pressure-modulated width) render for real; the textured `PENCIL` / `BRUSH` /
`CALLIGRAPHY` currently render as `PEN`.
The enum may grow; hosts should treat unknown persisted values as `PEN`.

Selection (mechanics in the component, data in the host):

1. Lasso outline → `onSelectionCreated(Selection(strokeIds, contentIds, bounds))`.
   Strokes select on **touch** semantics (any point inside the outline); host content
   selects when the outline touches a `hitTargets()` rect anywhere. An outline that
   catches nothing creates no selection. The selection box is drawn slightly outside
   the tight `bounds` so thin selections stay grabbable.
2. Drag inside the box → `onSelectionDragStarted()` (once the pen travels past the
   ~8 dp threshold) … stylus lift →
   `onSelectionMoved(SelectionMove(strokeIds, contentIds, dx, dy))`. The component has
   already translated its in-memory strokes and re-rendered; the host applies the same
   delta to its persisted data (`Stroke.translated`) and its own content objects
   (reposition + `notifyContentChanged()`). During the drag, selected host content
   ghosts as a translated dashed outline by default; a renderer that implements the
   optional live-drag pair — the exclusion-aware `draw(canvas, excludedContentIds)`
   plus `drawObject(canvas, contentId)` — has its real object drawn under the pen
   (see `ContentRenderer`). The selection stays active at its new position. A
   sub-threshold tap inside the box keeps the selection **and reports
   `onSelectionTapped(x, y)`** (0.1.1; paper coordinates) — for a stylus tap at pen-up,
   for a single-finger tap after the same `PEN_ACTIVE_TAIL_MS` escrow as tap-to-dismiss
   (palm-gated, dropped if the pen turns active or the selection changes meanwhile). It
   fires for any selection contents; the host decides what a tap means (typically: open
   the tapped content object for editing). A cancelled drag dismisses the selection
   (`onSelectionDismissed`).
3. Tap outside / a new outline / tool change / `clearSelection()` →
   `onSelectionDismissed()`. Any data-in call (`loadStrokes`, `addStrokes`,
   `removeStrokes`, `clear`, `clearForContentSwap`) also dismisses first — the
   selected ids may be about to change; re-select via `setSelection` if needed.
4. `setSelection(ids, contentIds, bounds)` injects a selection (e.g. right after an
   `addStrokes` paste, so the pasted content lands selected and draggable). Host-
   initiated, so it does **not** echo `onSelectionCreated`.

`onPenLifted()` is a save/checkpoint trigger only — it implies nothing about overlay
state and must not drive tool or lifecycle changes.

### Pen-gesture recognizers (opt-in)

Two recognizers turn qualifying **pen-tool** strokes into actions instead of ink. Both
default **off** (`smartLassoEnabled` / `scribbleEraseEnabled`) and are evaluated only in
`Tool.PEN`. Shape classification is exclusive and scribble-shape is checked first: a
**scribble-shaped** stroke (dense oscillation) is an erase intent and is never treated
as a smart lasso — real scribbles routinely satisfy the loop gates too (they end near
their start and curled turnarounds accumulate winding; device-measured), while a
genuine selection loop is a smooth single pass that never reads scribble-shaped. A
candidate whose hit test comes up empty always falls through to an ordinary committed
stroke — writing "o" over blank paper stays writing, and an empty scribble never falls
back to a lasso. A consumed gesture stroke is chrome: never committed, never reported,
and `onPenLifted` does not fire for it.

- **Smart lasso** (`smartLassoEnabled`): a quick closed loop — velocity ≥ 0.5 px/ms,
  first-to-last ≤ 50 dp, winding ≥ 270° around its centroid, not scribble-shaped —
  that encloses at least one stroke or `hitTargets()` rect is consumed as a lasso. The
  component switches `tool` to `Tool.LASSO` itself (exactly as if the user had picked
  the lasso tool and drawn that outline), creates the selection, and fires
  `onSelectionCreated`. When the session's selection is dismissed without a successor
  (tap-away, `clearSelection`, any data-in call) the component restores `Tool.PEN`; a
  host-initiated tool change at any point ends the session without interference. Both
  component-initiated tool changes fire **`onToolChanged(tool)`** — sync toolbar UI
  there, not by re-reading `paper.tool` in the selection callbacks: the PEN restore can
  land *after* `onSelectionDismissed` (a pen tap-away dismisses at pen-down but
  restores at pen-up).
- **Scribble erase** (`scribbleEraseEnabled`): a dense zigzag — bounding-box diagonal
  ≥ 40 dp, pathLength/diagonal ≥ 3.0, ≥ 2 direction reversals after sub-2 px jitter is
  filtered — erases every stroke it touches (8 dp radius, whole-stroke: eraser-tool
  semantics), reported through the normal `onStrokesErased` in one batched call, so
  host persistence/undo paths work unchanged. Undo of a scribble is simply restoring
  the erased strokes. Host content objects are not scribble-erasable (consistent with
  the eraser tool).

**Raw input passthrough**: `setRawInputListener { event -> … }` observes the stylus
stream (`RawInputEvent`: action, tool end, x/y/pressure/tilt/time) regardless of active
tool. Plain data, not `MotionEvent` — on BOOX the ink path bypasses `MotionEvent`
entirely, so a common shape is synthesized. Observational only; cannot consume.

## Chrome cooperation & palm rejection

- `setExclusionRects(rects)` — view-coordinate rects the stylus must not ink (toolbar,
  menus, floating chrome). Push updates whenever chrome opens/closes/moves. Applied to
  the hardware pen layer **and** filtered model-side so data matches pixels.
- `releaseRender()` — call on finger interaction with chrome overlaying the paper so an
  EPD panel shows the UI change; re-arms on next pen-down; no-op off-EPD.
- `isPenActive` — true while writing **or hovering near the surface**, + 350 ms tail
  after either (`PaperView.PEN_ACTIVE_TAIL_MS`). Hover counts because the palm lands a
  beat before the pen tip touches; on EMR panels the gate closes as the pen approaches.
  **The host must gate its finger-gesture handlers on this** — on EPD engines a writing
  stylus produces no MotionEvents but a resting palm does, and an ungated handler that
  pokes the view mid-stroke drops ink. For tap-like gestures, re-check the gate at
  finger-**up**, so a palm that lands before the pen enters hover range is still caught.
  For tap-*actions* (anything that mutates state on a tap), go one step further and
  **commit the tap after a `PEN_ACTIVE_TAIL_MS` escrow**, dropping it if the gate closes
  meanwhile: a palm micro-tap can *complete* a beat before the pen enters hover range
  (~190 ms measured on BOOX), which no proximity signal can catch at up-time. The demo's
  host-object tap is the reference implementation. Contact size can't substitute for the
  gate — EPD touch panels may report no contact geometry at all (NA5C: zero
  size/touchMajor, palms classified as plain finger).

## Lifecycle contract

The EPD pen pipeline is process-global; the engines guard it internally (ownership
guard, visibility handling, leaked-pin healing), but three hooks need the host:

| Host moment | Call |
|---|---|
| `onResume` | `resumeDrawing()` — reclaim without relying on flaky focus events |
| Immediately before launching **another** paper-hosting screen | `releaseForHandoff()` |
| Immediately before `finish()`-ing **back to** a paper-hosting caller (same call, other direction) | `releaseForHandoff()` — the caller reclaims in its `onResume`, which runs *before* this window's visibility change would close the pipeline; a close landing after the caller's reclaim tears the caller's live session down (BOOX: ink / lasso trails invisible until a tool flip; seen cross-process, Notesprout Paper arc 6) |
| `onDestroy` | `release()` — final teardown, idempotent |

`renderToBitmap()` renders template + committed content to a fresh bitmap (thumbnails/
covers), safe while the overlay is live; null before layout.

## Engine selection

Explicit registration — no ServiceLoader, no reflection, R8-safe:

- Core's generic engine self-registers lazily (`GPaper.ENGINE_GENERIC`, priority 0).
- Device modules expose a one-liner (`OnyxEngine.register(application)` /
  `RattaEngine.register()`) registering a `PaperEngineProvider` at `PRIORITY_DEVICE`
  (100). The Onyx variant takes the `Application` because it must run in
  `Application.onCreate`: on BOOX hardware it also installs the SDK's hidden-API bypass
  and clears any EPD fast-mode pin leaked by a killed pen session (see `OnyxEngine`
  KDoc). Build-side, `gpaper-onyx` consumers add the BOOX maven repo
  (`http://repo.boox.com/repository/maven-public/`, insecure protocol allowed) and
  `android.enableJetifier=true`; generic-only consumers need neither. `gpaper-ratta`
  adds **no** dependencies at all — it drives the Supernote firmware's ink daemon
  directly over Binder; its availability probe requires Supernote hardware *and* a
  reachable ink service (absent either, selection falls through to the next engine).
- `GPaper.create(context)` picks the highest-priority available engine and logs the
  choice at `Log.i`; `GPaper.create(context, "onyx")` is an explicit override that
  **bypasses** the availability probe. No engine → `IllegalStateException`. **No runtime
  fallback ever** — post-construction engine failures are loud, never silently swapped.

## Threading rules

Main thread for everything except `getStrokes()` (any thread). All callbacks arrive on
the main thread. `RawInputListener` runs at input rate — keep it allocation-free.

## Design decisions (locked during development)

- Pressure + tilt captured in the model; rendering may ignore them initially.
- Pen types: abstract `StrokeStyle` in the model + API now — eight values spanning the
  union of Onyx firmware styles and Ratta pen codes, including both trail appearances
  (`DASH`, `CROSS`) as host-usable pens; committed rendering is core-owned and portable,
  live ink maps best-effort per engine, richer styles render incrementally (native
  device codes never surface in the public API). Trail chrome during lasso gestures
  stays engine-internal.
- One surface, host-swapped "pages"; host-owned undo/redo.
- Explicit engine registration (not ServiceLoader); explicit override bypasses probes.
- Stroke color as ARGB Int (not hex string); geometry in px, paper coordinates.
- Listener interfaces with default no-ops (not nullable `var` lambdas à la Notesprout).
- Eraser is whole-stroke with a radius; host content is never erased by the component.
