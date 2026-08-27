# Host Responsibilities

g-paper is paper. It captures pen input and renders what it is told to render — everything else
is the hosting app's job. This page spells out that division of labor with working patterns.
API details are in [api.md](api.md); build setup is in
[integration-guide.md](integration-guide.md).

## Persistence

The component holds only an in-memory working copy of loaded strokes, for rendering and hit
testing. The host owns the real data, keyed by stroke id (host-meaningful strings; fresh
engine-captured strokes get random UUIDs). Mirror every change out of the callbacks:

```kotlin
paper.setPaperListener(object : PaperListener {
    override fun onStrokeCommitted(stroke: Stroke) = db.save(stroke)       // new ink
    override fun onStrokesErased(ids: List<String>) = db.delete(ids)       // batched
    override fun onSelectionMoved(move: SelectionMove) {                   // drag finished
        db.translate(move.strokeIds, move.dx, move.dy)                     // Stroke.translated
        myObjects.translate(move.contentIds, move.dx, move.dy)
    }
    override fun onPenLifted() = scheduleSaveCheckpoint()                  // save trigger only
})
```

- Strokes are plain Kotlin data classes with no serialization opinions — persist them in
  whatever format you like (rows, JSON, protobuf).
- `Stroke.translated(dx, dy)` is the sanctioned move/copy: it shifts points + bounds and
  preserves everything else.
- `onPenLifted()` is a save/checkpoint trigger only. It implies nothing about overlay state and
  must not drive tool or lifecycle changes.
- `getStrokes()` (callable from any thread) is the save-all/export path.
- `clear()` is the user-facing "erase page" — it fires **no** erase callbacks; update your own
  data in the same action that called it.

## Pages

There is one surface; "pages" are host-swapped content:

```kotlin
fun turnPage(next: PageId) {
    saveCurrentPage()                    // getStrokes() or your incremental mirror
    paper.clearForContentSwap()          // pixels hold — no blank flash on e-ink
    paper.loadStrokes(db.loadStrokes(next))
    paper.notifyContentChanged()         // if host content changed too
}
```

`clearForContentSwap()` + `loadStrokes()` is a single EPD refresh; `clear()` +
`loadStrokes()` would flash blank in between.

## Undo / redo

Host-owned, by design — the component exposes deterministic load/add/remove instead of a
history. Keep an operation stack and replay:

| User action | Callback you record | Undo | Redo |
|---|---|---|---|
| Drew a stroke | `onStrokeCommitted(s)` | `removeStrokes([s.id])` | `addStrokes([s])` |
| Erased strokes | `onStrokesErased(ids)` (you still have the strokes) | `addStrokes(strokes)` | `removeStrokes(ids)` |
| Scribble-erased strokes + content (`scribbleEraseEnabled`) | `onScribbleErased(strokeIds, contentIds)` — one call per gesture; the scribble itself was never committed | `addStrokes(strokes)` + restore your content rows | `removeStrokes(ids)` + delete them again |
| Moved a selection | `onSelectionMoved(m)` | `removeStrokes` + `addStrokes(translated back)` — or `loadStrokes` the page | re-apply the delta |
| Cleared the page | your own clear action | `loadStrokes(saved)` | `clear()` |

`loadStrokes(list)` is the blunt instrument (full page replay); `addStrokes`/`removeStrokes`
are the targeted ones. Any data-in call dismisses an active selection first; re-select via
`setSelection` if the operation should leave content selected (e.g. paste).

## Gestures and palm rejection

Finger input passes through the component untouched — pan, zoom, page-turn swipes, taps on
host objects are all yours. Two contracts come with that:

**1. Gate every finger handler on `paper.isPenActive`.** On the e-paper engines a writing
stylus produces *no* MotionEvents (the firmware paints), but the resting palm does — an ungated
handler that pokes the view mid-stroke drops ink. The gate is true while the pen is writing
**or hovering near the surface**, plus a 350 ms tail (`PaperView.PEN_ACTIVE_TAIL_MS`).

- Continuous gestures (pan/zoom): refuse to start while `isPenActive`; cancel if it turns
  active mid-gesture.
- Tap-like gestures: re-check the gate at finger-**up**, not just down — the palm can land
  before the pen enters hover range.
- Tap-*actions* (anything that mutates state on a tap): commit after a `PEN_ACTIVE_TAIL_MS`
  **escrow**, dropping the action if the gate closes meanwhile. A palm micro-tap can complete
  ~190 ms before the pen is in hover range — invisible to any proximity check at up-time. The
  demo's host-object tap is the reference implementation.
- Contact size is not a substitute: e-ink touch panels may report zero contact geometry, with
  palms classified as plain finger.

**2. Yield finger events while a selection is active.** The one finger interaction the
component itself claims: while a lasso selection is active, a single finger inside the box
drags it and a finger tap outside dismisses it (all palm-gated internally). A host touch
listener on the paper view must stand down during an active selection or the component never
sees those events.

## Pen-gesture recognizers (opt-in)

`smartLassoEnabled` and `scribbleEraseEnabled` (both default off, pen tool only) turn
qualifying strokes into actions — full contract in [api.md](api.md). Two host obligations
come with enabling them:

- **Smart lasso changes `tool` on your behalf.** On trigger the component sets
  `tool = Tool.LASSO`; when that session's selection is dismissed it restores `Tool.PEN`.
  Both changes fire `onToolChanged(tool)` — re-style your toolbar there (the demo does
  exactly this). Don't rely on re-reading `paper.tool` inside the selection callbacks:
  the PEN restore can arrive after `onSelectionDismissed` fires.
- **A scribble arrives as one `onScribbleErased(strokeIds, contentIds)`** — not as separate
  stroke and content callbacks, because one gesture has to be one undo entry: a scribble
  that takes ink and a heading together must not cost the user two undos. The default
  implementation forwards to `onStrokesErased` / `onContentErased`, so an existing host
  keeps working without changes; override it once your undo can record both kinds at once.
  Content is handled almost as for `onContentErased` — the component owns none of it, so you
  delete the rows — but **do not call `notifyContentChanged()` here**: a gesture ends at this
  callback and the component re-records the moment it returns. Repainting as well costs a
  second frame, and on an EPD engine that is a second visible refresh whose first half shows
  the ink gone and the content still standing. The gesture stroke itself is never committed
  or reported.

## Snap to guides (opt-in, 0.1.6)

`snapToGuides` makes a dragged selection catch on the page's edges, margins and centres and
on the other content objects' edges, centres and ±margin proximities — full table in
[api.md](api.md). Default off; three host obligations:

- **Set `snapMarginPx`.** g-paper holds no dimens, so with the default 0 the margin guides
  collapse onto the page edges and half the feature is gone. Pass whatever your edge chrome
  is thick — content snapped to a margin then lands exactly clear of the toolbar.
- **Apply `onSelectionMoved`'s delta as-is.** It is the snapped delta. A host that recomputes
  a move from its own pointer tracking would silently undo the snap the user just watched.
- **Own the toggle.** The component draws the guides but never the switch; put it wherever
  the selection is acted on, and flip it between drags rather than during one.

Nothing else changes: objects are snap targets automatically through the `hitTargets()` you
already return, and strokes are deliberately excluded.

## Host content

Register a `ContentRenderer` to draw your objects into the committed layer (z-ordered below or
above the ink), and return `hitTargets()` to opt them into lasso selection. Call
`notifyContentChanged()` after any change to your content — once per batch; `draw` runs only
when the committed layer re-records, never per frame, and may run on a software canvas.

For selected objects to visibly follow a drag, implement the optional live-drag pair —
`draw(canvas, excludedContentIds)` plus `drawObject(canvas, contentId)` — **both or neither**.
Without it, dragged objects ghost as a translated dashed outline until you apply the move.
When `onSelectionMoved` arrives, reposition your objects and call `notifyContentChanged()`.
The component never erases host content; the eraser reports stroke ids only.

**Tap-to-edit** (0.1.1): a sub-threshold stylus or single-finger tap *inside* the active
selection box reports `onSelectionTapped(x, y)` (paper coordinates) and leaves the selection in
place; the finger variant is escrowed and palm-gated exactly like tap-to-dismiss. It fires for
every selection (strokes-only included) — hit-test `(x, y)` against your own object bounds and
open the object that contains it; ignore the rest. Drags are unchanged.

**Tap-to-place** (0.1.5): the companion signal. A sub-threshold **stylus** tap in `Tool.LASSO`
with **no selection active** reports `onPaperTapped(x, y)` (paper coordinates, the pen-up point)
— the natural "paste my clipboard here" hook. Stylus only, so a palm or a stray finger can never
fire it. The tap that *dismissed* a selection never reports: that contact is spent on the
dismissal, and the user taps again to place. A tap over unselected ink still fires — "bare paper"
means "no selection box", not "no content".

Two host-side traps come with it:

- **Arm the lasso yourself after a copy/cut.** If `smartLassoEnabled` is on, the dismissal that
  ends a selection restores `Tool.PEN` (`onToolChanged`) — the placement tap would then *ink the
  page*. A host-initiated `tool = Tool.LASSO` ends the session cleanly and re-arms.
- **Show the user that a tap will place something.** Nothing about the surface changes when a
  clipboard is loaded; the affordance is the host's to draw (an icon state, a chrome hint).

## Chrome cooperation

The paper view sits in a `FrameLayout` with your chrome on top. Three obligations:

- `setExclusionRects(rects)` — view-coordinate rects the stylus must not ink. Push a fresh
  list whenever chrome opens/closes/moves; the rects are applied to the hardware pen layer and
  filtered model-side so data matches pixels.
- `releaseRender()` — call on finger interaction with chrome overlaying the paper, so an
  e-ink panel repaints and actually shows your menu/dialog. Re-arms on the next pen-down;
  no-op off-EPD, so call it unconditionally.
- On BOOX, apply system-bar insets to your layout (a real status bar overlays the window top).

## Lifecycle

| Host moment | Call |
|---|---|
| `onResume` | `resumeDrawing()` — reclaims the pen pipeline without relying on focus events |
| Immediately before launching another paper-hosting screen | `releaseForHandoff()` |
| Immediately before `finish()`-ing **back to** a paper-hosting caller (same call, other direction) | `releaseForHandoff()` — the caller reclaims in its `onResume`, which runs *before* this window's visibility change would close the pipeline; a close landing after the caller's reclaim tears the caller's live session down (BOOX: ink / lasso trails invisible until a tool flip; seen cross-process, Notesprout Paper arc 6) |
| `onDestroy` | `release()` — final teardown, idempotent |

The e-paper pen pipelines are process-global. The engines guard ownership internally; these
three calls are the only lifecycle wiring the host provides.

## What the host must NOT do

- Don't construct `CanvasPaperView` (or the device views) directly — `GPaper.create` only.
  `core/canvas/` is public solely for the device modules to subclass.
- Don't build a runtime engine fallback. Engine choice is once, at creation; failures after
  that are loud by design.
- Don't treat live ink as the ground truth for appearance — the committed (baked) rendering
  is; live ink is a per-engine preview.
- Don't drive tool changes or lifecycle from `onPenLifted()`.
- Don't block in `RawInputListener` — it runs at input rate; keep it allocation-free.
- Don't invalidate views (counters, debug readouts, any chrome) while `isPenActive` —
  ideally present **no app frames at all during writing**. The raw stream's `MOVE` and
  `HOVER` events arrive at input rate (EMR pens hover between every stroke), and on
  Supernote even occasional per-stroke frames hurt: pixels under firmware overlay ink
  are frozen against app updates, so every frame presented mid-writing pays a masking
  cost that grows with the accumulated unbaked ink — felt as progressively lagging ink
  (measured on the Nomad). Defer chrome updates until the pen gate opens (~350 ms after
  the pen leaves); the demo's status line is the reference implementation.
