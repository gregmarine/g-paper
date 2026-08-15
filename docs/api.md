# g-paper Public API

> Phase 1 contract. The authoritative surface is the code in
> `gpaper-core/src/main/java/com/symmetricalpalmtree/gpaper/core/` — this document is the
> guided tour. Everything here compiles today; the engines behind it arrive in Phases 2–5.
>
> **Implementation status (Phase 3):** the generic Canvas engine (Phase 2) and the BOOX
> engine (`gpaper-onyx`, Phase 3) are live; selection/lasso lands in Phase 5 on every
> engine (`Tool.LASSO` observes without inking, `setSelection` warns and is ignored).
> `OnyxPaperView` subclasses the shared `CanvasPaperView` base — committed rendering,
> erase hit-testing, and the stroke model are literally the same code; only the live-ink
> pipeline (SDK raw drawing) and the EPD handoffs are Onyx-specific. Hosts still never
> touch `canvas/` directly. `GPaper.create(context)` works with zero registration calls
> on generic devices; BOOX apps add one `OnyxEngine.register(application)` call.

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
RattaEngine.register()     // gpaper-ratta, Phase 4

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

- **Pressure and tilt are captured** on hardware that reports them (decided Phase 1);
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

**Undo/redo is host-owned** (decided Phase 1): the host keeps its history and replays via
the load/add/remove calls. **Pages are host-owned** (decided Phase 1): one surface;
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
to the host.

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
committed renderer is implemented. Live mappings are confirmed on-device in the engine
phases (3 = Onyx, 4 = Ratta; committed renderers per style are scheduled there too).

Committed-renderer status (Phase 2 first slice, `core/canvas/StrokeRenderer.kt`):
`PEN`, `MARKER` (translucent flat-cap), `DASH`, `CROSS` (x-marks along the path), and
`FOUNTAIN` (pressure-modulated width) render for real; the textured `PENCIL` / `BRUSH` /
`CALLIGRAPHY` currently render as `PEN`.
The enum may grow; hosts should treat unknown persisted values as `PEN`.

Selection (mechanics in the component, data in the host — Phase 5 implements):

1. Lasso outline → `onSelectionCreated(Selection(strokeIds, contentIds, bounds))`.
2. Drag inside the box → `onSelectionDragStarted()` … stylus lift →
   `onSelectionMoved(SelectionMove(strokeIds, contentIds, dx, dy))`. The component has
   already translated its in-memory strokes and re-rendered; the host applies the same
   delta to its persisted data (`Stroke.translated`) and its own content objects.
3. Tap outside / tool change / `clearSelection()` → `onSelectionDismissed()`.
4. `setSelection(ids, contentIds, bounds)` injects a selection (e.g. after paste).

`onPenLifted()` is a save/checkpoint trigger only — it implies nothing about overlay
state and must not drive tool or lifecycle changes.

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
| `onDestroy` | `release()` — final teardown, idempotent |

`renderToBitmap()` renders template + committed content to a fresh bitmap (thumbnails/
covers), safe while the overlay is live; null before layout.

## Engine selection

Explicit registration — no ServiceLoader, no reflection, R8-safe (decided Phase 1):

- Core's generic engine self-registers lazily (`GPaper.ENGINE_GENERIC`, priority 0).
- Device modules expose a one-liner (`OnyxEngine.register(application)` /
  `RattaEngine.register()`) registering a `PaperEngineProvider` at `PRIORITY_DEVICE`
  (100). The Onyx variant takes the `Application` because it must run in
  `Application.onCreate`: on BOOX hardware it also installs the SDK's hidden-API bypass
  and clears any EPD fast-mode pin leaked by a killed pen session (see `OnyxEngine`
  KDoc). Build-side, `gpaper-onyx` consumers add the BOOX maven repo
  (`http://repo.boox.com/repository/maven-public/`, insecure protocol allowed) and
  `android.enableJetifier=true`; generic-only consumers need neither.
- `GPaper.create(context)` picks the highest-priority available engine and logs the
  choice at `Log.i`; `GPaper.create(context, "onyx")` is an explicit override that
  **bypasses** the availability probe. No engine → `IllegalStateException`. **No runtime
  fallback ever** — post-construction engine failures are loud, never silently swapped.

## Threading rules

Main thread for everything except `getStrokes()` (any thread). All callbacks arrive on
the main thread. `RawInputListener` runs at input rate — keep it allocation-free.

## Decisions locked in this phase

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
