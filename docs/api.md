# g-paper Public API

> The guided tour of the host-facing surface, as of **v0.1.22**. The authoritative surface
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
| Page mode (0.1.25) | `PageMode` — `STROKE` (default) / `RASTER`; `pageMode`, `loadPageRaster`, `getPageRaster`, `copyPageRaster` |
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
| Out | `onContentErased` (0.1.4) | Eraser swept over host content: whole-object ids; the host deletes its rows + `notifyContentChanged()` (the component owns no content, so nothing disappears by itself). At most once per id per gesture. The **eraser tool** reports here; a scribble reports through `onScribbleErased` |
| Out | `onScribbleErased(strokeIds, contentIds)` (0.1.23) | One scribble-erase gesture, whole: the strokes it crossed and the content objects it went through, in **one** call so the host can record one undo entry. Defaults to forwarding to `onStrokesErased` + `onContentErased`, so a host that has not adopted it keeps working |
| Out | `onPaperTapped` (0.1.5) | Stylus tap on bare paper in `Tool.LASSO` with nothing selected — the "paste here" hook. Stylus only; never the tap that dismissed a selection |
| — | `clear()` | User-facing "erase page" (host updates its own data; no erase callbacks fire) |
| — | `clearForContentSwap()` | Page turn: pixels hold until the next `loadStrokes` — single EPD refresh, no blank flash |

### Raster pages (0.1.25)

`pageMode = PageMode.RASTER` makes the page **one image** instead of a list of strokes.
Everything up to pen-up is shared with stroke mode — live ink, palm gate, EPD handoffs, the
committed renderer — and only what is *kept* differs: the mark is composited into a
page-sized transparent bitmap through the same renderer and seed, and the object is dropped.
Set the mode on an empty page (it drops content like `clearForContentSwap()`), before the
content loads; it is never flipped under ink, and a host that never mentions it gets the
engine it always had.

| Direction | API | Notes |
|---|---|---|
| In | `loadPageRaster(bitmap?)` | Page load / undo replay; copied in at 1:1 from the page origin, null = blank. Handles the EPD handoff |
| In | `loadStrokes` / `addStrokes` | Composite into the image — the **one-way bake** of a stroke page. `removeStrokes` does nothing; `getStrokes()` is empty |
| Out | `onRasterWillChange(rect)` → change → `onRasterChanged(rect)` | Around every change, page space, rect generous and page-clipped. The first is the host's before-image moment (`copyPageRaster(rect)`), the second its dirty flag. `onStrokeCommitted` still fires for a composited mark (timestamps and counts from one place) — don't store that stroke as a row |
| Out | `getPageRaster()` | A **copy**, or null when blank — encode it off the main thread for a save |
| Out | `copyPageRaster(rect)` | A copy of a patch — the before-image for undo |
| — | Eraser (0.1.26) | The same sweep as stroke mode, but it **rubs pixels**: every pixel within `eraserRadius` of the sweep goes transparent. Each batch fires `onRasterWillChange(rect)` → clear → `onRasterChanged(rect)` (accumulate the tiles into one undo entry, closed at `onPenLifted`); `onStrokesErased` never fires. The Onyx engine repaints the changed region per throttled batch so rubbing reads live on the panel |

The image is a layer *over* the paper (white + template still draw under it), so the eraser
clears to transparent rather than painting white. Format is ARGB_8888; about
18 MB at a 1860 × 2480 page — one per view, for the life of the page.

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
`hitTargets()` opts host objects into lasso selection — and, since 0.1.4, into
whole-object erase: the eraser tool sweeping a hit target reports its id through
`PaperListener.onContentErased` (square-corner radius tolerance, like the lasso's
overlap test) and the host deletes + `notifyContentChanged()`.

**`StrokeRasterizer.draw(canvas, strokes)`** (0.1.4) is the offline twin of the
committed layer: it draws host-owned strokes onto any canvas through the same internal
renderer every engine bakes ink with — pixel-identical appearance without a live,
laid-out view. Paper coordinates; the caller applies transforms and passes strokes in
committed order. For hosts compositing content they own (a group/link object's wrapped
ink, thumbnails of rows never loaded on a surface).

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
| `PENCIL` | graphite grain on tooth; pressure → darkness (tilt → width where an engine reports a lean; none does today) | `STROKE_STYLE_PENCIL` (0) | `NEEDLE` (10) |
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

Committed-renderer status at v0.1.24 (`core/canvas/StrokeRenderer.kt`):
`PEN`, `MARKER` (translucent flat-cap), `DASH`, `CROSS` (x-marks along the path),
`FOUNTAIN` (pressure-modulated width) and `PENCIL` (graphite grain — 0.1.7) render for
real; `BRUSH` and `CALLIGRAPHY` still render as `PEN`.
The enum may grow; hosts should treat unknown persisted values as `PEN`.

**`PENCIL` (0.1.7; tilt 0.1.9, refit 0.1.10, density 0.1.11, grain 0.1.13/0.1.14, tilt smoothing 0.1.15; upright again 0.1.24).** Graphite is laid down as a scatter of
flecks on the paper's tooth with bare paper between them, not as a tinted line. **Pressure
darkens.** Leaning on the pencil fills in more of the tooth and darkens what lands, without
moving the width. The renderer also knows what a *lean* does — laying the pencil over draws with
the flank of the lead rather than its point, and the mark grows many times wider *and paler*, the
same graphite spread over a broader band leaving less of itself on any one peak — and it will do
that for any engine that reports a tilt. **No engine does today.** The Onyx engine measured one
model (≈1× wide at 9°, ≈4.9× at 44°, ≈10.9× at 75°, against an artist's eye on a NoteAir5C), drove
the width from it for fourteen releases, and turned it off in 0.1.24 when the artist sketched with
the result and rejected it: too broad in an ordinary grip, and not a pencil to look at. `tilt = 0`
is a pencil held upright, a fixed-width mark, and that is the pencil this style now is. A mark's
apparent width comes out roughly `width + 1 px`, the bleed of one fleck — except that **a fleck is
never wider than the lead that lays it** (0.1.24), so a hairline lead bakes as the hairline it
previewed as rather than at twice its width.

**Onyx live ink for `PENCIL` is the plain even line, style 0 (0.1.24).** It was `CHARCOAL_V2`
from 0.1.9 to 0.1.23. That style broadens with the pen's lean inside the firmware and
`TouchHelper` offers no way to switch that off, so a textured live preview cannot be had
without a tilt response; taking the lean out of the mark meant taking the texture out of the
live ink. The live line is now exactly the width the host asked for and the bake adds grain and
pressure → darkness at pen-up — the pen-up change is tone and texture, never size. The 1.3×
overdraw correction that `CHARCOAL_V2` needed went with it. How much graphite lands inside that width was set by photographing strokes
live on a NoteAir5C's panel and again after the bake, and comparing ink per unit length — the
two covered the same width and the bake was depositing about 30% less inside it (0.1.11). Hosts choosing distinguishable pencil sizes should space them by more
than that.

**A stroke ends in a dome, not a chisel (0.1.17, 0.1.19).** A lead meets the paper as a patch, so
the ink ends in a half-round of the mark's own half-width rather than a straight cut with corners on
it. The cap's strips narrow as it closes, and their coverage is corrected for that (0.1.19) — without
it the lane-count rounding over-deposits every narrow strip and the excess draws a dark bead round
the cap's own outline. Stopping at the
last cross-section leaves a straight cut clean across the mark, corners and all. The touch-down cap
is laid before the body so ink already on the paper keeps its place as the stroke grows; only the
lifting cap travels with the pen, as the real one does.

**The direction of travel is averaged before a cross-section is laid across it (0.1.16).** This is
the one that matters most, because the error is multiplied by the width of the mark. Taking the pen's
direction from one adjacent pair of samples measures jitter rather than travel — at 2 px spacing, a
third of a pixel of digitizer noise swings the angle with ~14° of standard deviation — and every
cross-section of grain is then rotated by that much. On a lead laid over it throws grain tens of
pixels out of line and the stroke grows bristles: a pipe cleaner. Smoothed over ~10 px of arc,
causally. **A texture cannot be fixed by working on the texture when the frame it is laid in is
noisy.**

**The pen's arrival is dropped, not drawn (0.1.22).** A pen touches down, the hand settles, and the
path takes a small excursion before the stroke sets off. On a fine lead nobody sees it; on a lead
laid over the mark folds across itself there and graphite laid twice composites to solid black — a
knot at the start of every broad stroke. `PENCIL` finds the last sample within 25 px at which the pen
was travelling more than 60° off where the stroke turned out to go, and begins after it. A clean
touch-down never is, so nothing is trimmed from one.

**Darkness follows a slower lean than width does (0.1.21).** Tilt drives the two in opposite
directions — laying the pen over makes a mark broader *and* paler — so read from the same instant
they compound, and a moment of near-upright inside a laid-over stroke comes out ten times narrower
*and* nearly twice as dark: a hard black nub, right where touch-down is most likely to catch the pen
upright. Width follows the lean closely; darkness follows the lean the hand has settled into. A
stroke held flat is still paler than one held upright, which is what side-of-lead shading looks like.

**Tilt is averaged along the path before it sets a width (0.1.15).** A digitizer's tilt reading
jitters by several degrees sample to sample and a hand cannot roll a pencil that fast; fed in raw
it becomes *geometry*, and the mark grows a fringe of fine hairs down both edges. The renderer
smooths it over ~40 px of arc, **causally** — never over samples that have not arrived — so a
stroke still being drawn matches the same stroke committed. Pressure is deliberately left raw: it
sets darkness, and darkness noise reads as grain. The rule is that noise which becomes *shape*
must be smoothed and noise which becomes *tone* need not be.

**Tilt is supplied per-model, and zero everywhere else.** BOOX puts `tiltX`/`tiltY` on every
raw point but the SDK has no `getMaxTilt()`, and a five-device survey found the raw numbers on
wildly different scales — one model reporting in the thousands. So `gpaper-onyx` carries a list
of models whose tilt has actually been *measured*, and every model not on it reports `0`.
Measured on a NoteAir5C: `hypot(tiltX, tiltY)` is degrees from vertical, directly (a hand at a
deliberate 45° read 44.3). **`tilt = 0` is not a degraded mode** — it means a pencil held
upright, so an unmeasured device gets a fixed-width pencil rather than a broken one. Adding a
model to that list is a measurement, never an inference.

The grain is **deterministic**: it is seeded from the stroke's `id`, so the same stroke
re-renders fleck for fleck on every reload, in `StrokeRasterizer`, and on any device. The
live preview is seeded with the id the stroke is about to be committed with, so a pencil
mark does not reshuffle at pen-up on engines that preview through the core renderer.
Where the *live* ink is firmware the preview is the firmware's and the bake is ours — see the
live-vs-baked caveat above; a pop at pen-up is expected there, not a bug.

**Onyx arms `STROKE_STYLE_CHARCOAL_V2` (6), and the bake is fitted to match it.** That style also
**overdraws** — its mark is ~1.3× the width handed to `setStrokeWidth`, constant across pen angle
(measured NoteAir5C, 0.1.12) — so `gpaper-onyx` divides the width it asks for. That keeps
**`Stroke.width` meaning the width of the mark** on every engine and in `StrokeRasterizer`, rather
than making it a per-device fiction; a host that scales its own pen widths to suit hands the firmware
the same number as before, so the live ink is unchanged and only the bake moves to meet it. The firmware's
textured pens draw far wider than the width they are given when the pen is laid over, and that extra
width is **tilt**, not a scale factor — measured on a NoteAir5C, not inferred. `TouchHelper` exposes
only style, colour and width, so a textured live style cannot be had without its tilt response;
`GraphiteGrain` therefore widens the bake on the same curve (≈1× at 9°, 4.9× at 44°, 10.9× at 75°).
Live and baked agree on the mark's size at every angle, and a stroke gains its tooth at pen-up
rather than changing size. Ratta's `NEEDLE` is a plain solid line and disagrees by texture alone.

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
   A sub-threshold stylus tap on bare paper **with nothing selected** is the companion
   signal **`onPaperTapped(x, y)`** (0.1.5; paper coordinates, the pen-up point) — the
   host's "paste here" hook. Stylus only (the finger path only ever drags or dismisses an
   *active* selection, so nothing needs escrowing), never the tap that dismissed a
   selection (that contact is spent — the user taps again), never a cancelled contact,
   and only in `Tool.LASSO`. A tap over unselected ink still fires: "bare paper" means
   "no selection box", not "no content".
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
  filtered — crosses out everything it goes through. **Strokes** it touches (8 dp radius,
  whole-stroke: eraser-tool semantics) and, **since 0.1.23, host content objects** it
  travels through: ≥ 14 dp of scribble path *inside* a `hitTargets()` rectangle,
  whole-object. Both arrive in **one** `onScribbleErased(strokeIds, contentIds)` call,
  because one gesture must be one host undo entry; that method's default forwards to
  `onStrokesErased` / `onContentErased`, so a host that has not adopted it keeps working.
  Undo of a scribble is simply restoring what it erased. The host must **not** call
  `notifyContentChanged()` from it — the component re-records the moment it returns, and a
  second repaint is a second EPD refresh whose first half shows the ink gone and the content
  still standing.

  Content uses a **penetration** rule, not the eraser tool's touch rule
  (`EraseHitTest.scribbleContentIds` vs `hitContentIds`), and this is the whole reason the
  two are separate functions: a scribble is a large gesture, so "touched the inflated rect"
  would take a heading every time the ink beside it was scribbled out. Penetration
  accumulates across passes, so scribbling back and forth over an object registers while a
  corner-graze does not. There is no per-object opt-out — anything a `ContentRenderer`
  exposes as a `HitTarget` is scribble-erasable, exactly as it is eraser-erasable.

### Snap to guides (opt-in)

`snapToGuides` (default **off**) pulls a **dragged selection** to the page's own
structure and to the objects already on it, drawing a dashed rule edge to edge wherever
it caught. `snapMarginPx` (default 0) is the inset it measures margins by — set it to
whatever your edge chrome is thick and content snapped to a margin lands exactly clear
of the toolbar.

| Axis | Page guides | Per non-selected content object |
|---|---|---|
| X | `0` · `margin` · `pageWidth/2` · `pageWidth − margin` · `pageWidth` | `left − margin` · `left` · `centerX` · `right` · `right + margin` |
| Y | `0` · `margin` · `pageHeight/2` · `pageHeight − margin` · `pageHeight` | `top − margin` · `top` · `centerY` · `bottom` · `bottom + margin` |

- **Page** means the rect `setPageSize` declared (the view's bounds until it does), so
  guides agree with the template rather than the window. A non-positive page dimension
  simply drops that axis's page guides.
- **Object bounds come from `hitTargets()`**, snapshotted when the drag begins, minus
  whatever is selected. **Strokes are never snap targets** — on a handwriting page ink is
  everywhere, and a guide per stroke box is a thicket that fights the pen. The ±margin
  *proximity* guides are what make equal spacing fall out of a drag: drag one object
  below another and it catches exactly one margin-width from its neighbour's edge.
- **Anchors** are the selection's leading edge, centre, and trailing edge per axis, taken
  from its **tight** bounds — not the 12 px-inflated box the overlay draws, because the
  user is aligning content. The nearest (anchor, guide) pair within **20 dp** wins; axes
  are decided independently. Ties go to the page over an object, and to the leading edge
  over the centre over the trailing edge.
- **Nothing is clamped.** A guide holds only while the pen stays inside the threshold, so
  dragging on always releases — snapping must never read as the page resisting the hand.
- `onSelectionMoved` reports the **snapped** delta. Apply it as-is; do not recompute one
  from pointer positions.
- Toggle it between drags (a selection toolbar is the natural home). A change mid-drag
  takes effect on the next sample, but without the object guides the drag did not start
  with.

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
| Immediately before `finish()`-ing **back to** a paper-hosting caller (same call, other direction) | `releaseForHandoff()` — the caller reclaims in its `onResume`, which runs *before* this window's visibility change would close the pipeline; a close landing after the caller's reclaim tears the caller's live session down (BOOX: ink / lasso trails invisible until a tool flip; seen cross-process, Notesprout Paper arc 6). **0.1.2:** on Ratta the handoff also *drops* the ownership token — the engines' ownership guards are process-local statics, so a successor in another process cannot overwrite them; before 0.1.2 the departing screen's focus-loss and `release()` teardowns re-sent `enableFullUiAuto(false)` after the caller's reclaim (the caller's session stayed live, but every frame repainted with the slow waveform until a later re-arm — a "sluggish drag" on a Nomad). After a handoff, `resumeDrawing()` (or a focus gain) re-claims for the same view if the launch falls through. On Onyx (0.1.3) the reopen pins the fast handwriting waveform for whatever drawing tool is armed — a reopen on the LASSO used to stay unpinned, so selection drags after a handoff ran on the slow waveform until the pen was next armed. |
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
- Eraser is whole-stroke with a radius. Host content is never *removed* by the component;
  since 0.1.4 the eraser tool *reports* swept content whole (`onContentErased`) and the
  host removes it — before that, host content was entirely eraser-immune. Since 0.1.23 a
  scribble reports content the same way (`onScribbleErased`), on a stricter penetration
  rule; before that, content was scribble-immune.
