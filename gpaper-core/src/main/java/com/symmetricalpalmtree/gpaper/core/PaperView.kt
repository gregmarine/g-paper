package com.symmetricalpalmtree.gpaper.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.gpaper.core.render.ContentRenderer

/**
 * A writable/drawable paper surface.
 *
 * g-paper's single host-facing contract, implemented by every engine (generic Canvas,
 * BOOX/Onyx, Supernote/Ratta). The component is **paper**: it captures stylus input and
 * renders what it is told to render. It holds an in-memory working copy of the loaded
 * strokes for rendering and hit-testing, but the host owns the data — persistence,
 * pages, undo/redo, gestures, and all app logic live in the host.
 *
 * ### Threading
 * Every method must be called on the main thread unless noted. [getStrokes] is safe from
 * any thread. All callbacks fire on the main thread.
 *
 * ### Host data flow
 * - **Out:** [PaperListener.onStrokeCommitted] / [PaperListener.onStrokesErased] /
 *   selection events. Fire-and-persist; ids are the join key.
 * - **In:** [loadStrokes] (replace all — page load, undo/redo replay), [addStrokes] /
 *   [removeStrokes] (targeted undo/redo and paste), [ContentRenderer]s +
 *   [notifyContentChanged] for non-ink content.
 * - **Raster pages (0.1.25):** with [pageMode] = [PageMode.RASTER] the page is images
 *   rather than a list of strokes — **two of them since 0.1.39** ([RasterLayer]): graphite
 *   for the pencil, ink for everything else, seen as the ink drawn over the graphite,
 *   because the rubber must lift graphite and leave ink and a pixel cannot say which tool
 *   laid it. Out: [PaperListener.onRasterWillChange] / [PaperListener.onRasterChanged]
 *   around every change, each naming its layer, plus [getPageRaster] / [copyPageRaster].
 *   In: [loadPageRaster]. Every raster call has a layered form and an un-layered one that
 *   means [RasterLayer.GRAPHITE], so a host written against 0.1.38 is unaffected — as are
 *   stroke pages.
 *
 * ### "Pages"
 * The component has no page concept. A page turn is:
 * `clearForContentSwap()` → host loads its next page's data → `loadStrokes(...)` (+
 * [setTemplate]/[setPageSize] as needed). The old pixels stay on screen until the new
 * content lands, so EPD panels see a single refresh and no blank flash.
 *
 * ### Lifecycle contract (the host-facing shape of the engine ownership guards)
 * The hardware pen pipeline on EPD devices is a **process-global** resource; g-paper
 * guards it internally, but three hooks need host cooperation:
 * 1. [resumeDrawing] from the host Activity's `onResume` — reclaims the surface without
 *    depending on window-focus events (unreliable on e-ink).
 * 2. [releaseForHandoff] immediately before launching *another* screen that hosts a
 *    [PaperView] — the outgoing surface releases the pipeline cleanly while it still
 *    owns it.
 * 3. [release] when the host is done with the view (its `onDestroy`) — final teardown.
 * Everything else (window visibility/focus loss, superseded-view protection, leaked
 * fast-mode healing) is handled inside the engines.
 */
interface PaperView {

    /** This instance as an Android [View], for adding to the host's layout. */
    fun asView(): View

    /** Id of the engine behind this view (e.g. `"generic"`, `"onyx"`, `"ratta"`). */
    val engineId: String

    // ── Tool & pen style ─────────────────────────────────────────────────────

    /**
     * The active stylus tool. Defaults to [Tool.PEN]. Switching tools performs any
     * EPD overlay handoff internally; switching away from [Tool.LASSO] dismisses an
     * active selection (with [PaperListener.onSelectionDismissed]).
     */
    var tool: Tool

    /**
     * ARGB color new strokes are captured and rendered with (committed strokes keep
     * their own color). On e-ink, live ink is approximated to the panel's grey levels;
     * the stored [Stroke.color] keeps the true value.
     */
    var penColor: Int

    /** Width in px for new strokes. */
    var penWidth: Float

    /**
     * Abstract pen type for new strokes (committed strokes keep their own
     * [Stroke.style]). The committed appearance is engine-independent; live ink maps to
     * the nearest native style per device — see [StrokeStyle] for the mapping table and
     * the incremental-rendering caveat. Defaults to [StrokeStyle.PEN].
     */
    var penStyle: StrokeStyle

    /** Eraser hit radius in px around the stylus position. */
    var eraserRadius: Float

    /**
     * How hard the rubber rubs on a raster page (0.1.30): the lift per pass at a light
     * and a firm touch, and how much of the radius is feathered. Ignored in stroke mode,
     * where the eraser takes whole marks. See [RasterRubbing] for the defaults.
     */
    var rasterRubbing: RasterRubbing

    /**
     * Reach of the finger smudge on a raster page (0.1.54), in px around each sample of
     * the sweep — a fingertip, not a nib. See [rasterSmudging] and [smudgeAlong].
     */
    var smudgeRadius: Float

    /**
     * Reach of the **stylus** smudge ([Tool.SMUDGE], 0.1.60), in px around each sample of
     * the nib's sweep — a stump, not a fingertip. The same [rasterSmudging] does the
     * blending; only the reach differs from [smudgeRadius].
     */
    var smudgeToolRadius: Float

    /**
     * How a finger smudges graphite on a raster page (0.1.54): the pull toward the
     * neighbourhood mean per batch, the neighbourhood's spread, the feathered edge, and
     * the little that is carried off. Ignored in stroke mode. See [RasterSmudging].
     */
    var rasterSmudging: RasterSmudging

    /**
     * Begin a finger smudge on a raster page (0.1.54). The host owns the gesture — a
     * finger is never a tool, and this engine's touch handling never starts one — and
     * feeds the finger's samples through [smudgeAlong] until [endSmudge]. Within
     * [smudgeRadius] of the sweep the **graphite** image's pixels are pulled toward the
     * mean of their neighbourhood ([RasterSmudging]) so separate pencil lines run together
     * into a tone; the ink image is never read, never allocated and never announced —
     * the rubber's rule, held by the same construction. Each batch fires
     * `onRasterWillChange(GRAPHITE, rect)` → blend → `onRasterChanged(GRAPHITE, rect)` like a
     * rub, and [endSmudge] fires `onPenLifted` so a host that closes an undo entry per
     * contact closes this one too. A no-op in stroke mode and on a page with no graphite.
     */
    fun beginSmudge()

    /**
     * One batch of the smudge's samples, in view coordinates — every sample since the last
     * call, in order; the previous batch's last sample is chained on so the sweep stays a
     * connected polyline. Nothing happens before [beginSmudge] or after [endSmudge].
     */
    fun smudgeAlong(points: List<StrokePoint>)

    /** The finger left the glass: present the last of the smudge and close the contact. */
    fun endSmudge()

    // ── Pen-gesture recognizers (opt-in, default off) ────────────────────────

    /**
     * Smart lasso: a quick **closed** pen stroke (velocity ≥ 0.5 px/ms, first-to-last
     * ≤ 50 dp, winding ≥ 270° around its centroid) that encloses at least one stroke or
     * content hit target is consumed as a lasso instead of committing as ink. Default
     * **false**; evaluated only while [tool] is [Tool.PEN].
     *
     * On trigger the component switches [tool] to [Tool.LASSO] itself — exactly as if
     * the user had picked the lasso tool and drawn that outline — then creates the
     * selection and fires [PaperListener.onSelectionCreated]. When the selection is
     * dismissed without a successor (tap-away, model mutation, [clearSelection]) the
     * component restores [Tool.PEN]; a host-initiated tool change ends the session
     * without interference. Both component-initiated changes are announced via
     * [PaperListener.onToolChanged] — sync toolbar UI there, not by re-reading [tool]
     * in the selection callbacks (the PEN restore can land after
     * [PaperListener.onSelectionDismissed]). The gesture stroke itself is chrome: never
     * committed, never reported. A candidate that encloses nothing commits as ordinary
     * ink — writing "o" over blank paper stays writing. A **scribble-shaped** stroke
     * (the dense-oscillation gates of [scribbleEraseEnabled]) is never treated as a
     * smart lasso: real scribbles routinely satisfy the loop gates too, while a genuine
     * selection loop is a smooth single pass that never reads scribble-shaped.
     */
    var smartLassoEnabled: Boolean

    /**
     * Scribble erase: a dense zigzag pen stroke (bounding-box diagonal ≥ 40 dp,
     * pathLength/diagonal ≥ 3.0, ≥ 2 direction reversals after jitter filtering) crosses
     * out everything it goes through — strokes it touches (8 dp radius, whole-stroke —
     * eraser-tool semantics) and, since 0.1.23, host content objects it travels **through**
     * (≥ 14 dp of path inside a [ContentRenderer.hitTargets]
     * [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.hitTargets] rectangle,
     * whole-object). Content uses that penetration rule rather than the eraser tool's
     * touch rule because a scribble is a large gesture: touching-counts would take a
     * heading every time the ink beside it was scribbled out. Default **false**; evaluated
     * only while [tool] is [Tool.PEN]. Scribble shape is classified before the smart lasso
     * and is exclusive (see [smartLassoEnabled]).
     *
     * Both lists arrive in **one** [PaperListener.onScribbleErased] call, because one
     * gesture must be one host undo entry; that method's default forwards to
     * [PaperListener.onStrokesErased] / [PaperListener.onContentErased], so a host that
     * has not adopted it keeps working. The scribble stroke itself is never committed or
     * reported; undo of a scribble is simply restoring what it erased. A scribble that
     * touches nothing commits as ordinary ink — it never falls back to a smart lasso.
     */
    var scribbleEraseEnabled: Boolean

    // ── Snap to guides (opt-in, default off) ─────────────────────────────────

    /**
     * Snap-to-guide for a selection drag-move. Default **false**, in which case a drag
     * is exactly the pen's own path.
     *
     * When on, a dragged selection is pulled to the nearest *meaningful* position within
     * 20 dp and a dashed guide is drawn where it caught:
     *
     * - **Page guides** — the page's edges, [snapMarginPx] inset from each edge, and the
     *   centre, on both axes. Measured against the rect [setPageSize] declared (the
     *   view's own bounds until it does), so guides agree with the template.
     * - **Object guides** — for every content object *not* in the selection, its edges,
     *   its centre, and one [snapMarginPx] outside each edge, on both axes. Bounds come
     *   from [ContentRenderer.hitTargets], snapshotted when the drag begins. Strokes are
     *   never snap targets: on a handwriting page a guide per stroke is a thicket.
     *
     * The selection contributes three anchors per axis (leading edge, centre, trailing
     * edge) from its **tight** bounds, not the inflated box the overlay draws — the user
     * is aligning content, so an object snapped to the top margin starts *at* the margin.
     * Axes are decided independently. Nothing is clamped: a guide holds only while the
     * pen stays within the threshold, so dragging on always releases.
     *
     * [PaperListener.onSelectionMoved] reports the **snapped** delta — apply it as-is.
     * Toggle between drags; a change mid-drag takes effect on the next sample, without
     * the object guides the drag did not start with.
     */
    var snapToGuides: Boolean

    /**
     * Margin inset in px for [snapToGuides] — both the page margin guides and the
     * object proximity gap. Zero (the default) collapses the margin guides onto the page
     * edges and the proximity guides onto the object edges; harmless, but it throws away
     * half the feature, so hosts that arm [snapToGuides] should set this.
     *
     * g-paper holds no dimens, so the host chooses the value. A good one is whatever the
     * app's own edge chrome is thick, so content snapped to a margin lands exactly clear
     * of it.
     */
    var snapMarginPx: Float

    // ── Stroke data in ───────────────────────────────────────────────────────

    /**
     * Replace the entire in-memory stroke model with [strokes] and re-render. The
     * primary data-in call: page load, undo/redo replay, rejecting a move. Stroke ids
     * must be unique within the list.
     */
    fun loadStrokes(strokes: List<Stroke>)

    /** Add [strokes] to the model and re-render — undo of an erase, paste. */
    fun addStrokes(strokes: List<Stroke>)

    /** Remove the strokes with these ids (unknown ids ignored) and re-render — undo of a draw. */
    fun removeStrokes(ids: Collection<String>)

    /**
     * Snapshot of the in-memory stroke model. Safe from any thread; the returned list
     * and its strokes are immutable.
     */
    fun getStrokes(): List<Stroke>

    /**
     * Erase everything to blank paper (template stays) with a proper full EPD handoff —
     * the user-facing "clear page". Erased ids are NOT reported through
     * [PaperListener.onStrokesErased]; the host initiated this and updates its own data.
     */
    fun clear()

    /**
     * Drop the in-memory model WITHOUT repainting: the current pixels stay on screen
     * until the next [loadStrokes] swaps content in one refresh. Use for page
     * turns/content swaps instead of [clear] (which would double-flash EPD panels).
     */
    fun clearForContentSwap()

    // ── Page mode & the raster page (0.1.25) ─────────────────────────────────

    /**
     * What this page is once the pen lifts — see [PageMode]. Defaults to
     * [PageMode.STROKE], which is the engine exactly as it was before 0.1.25.
     *
     * Setting it to a different mode drops the view's content without repainting, as
     * [clearForContentSwap] does: a mode belongs to an empty page, set before its content
     * is loaded, and is never flipped under ink. Setting the mode it already has does
     * nothing. In [PageMode.RASTER]: [loadStrokes] and [addStrokes] composite the given
     * strokes into the page image instead of keeping them (that *is* the one-way bake of a
     * stroke page); [getStrokes] is always empty; [removeStrokes] does nothing; the eraser
     * and the pen-gesture recognizers find no strokes to hit.
     */
    var pageMode: PageMode

    /**
     * Whether a **stroke** page may be painted onto the panel by the engine itself, where
     * an engine can (Phase 42, 0.1.56 — Supernote's direct path; every other engine ignores
     * it). Off by default, so a host that never sets it has the engine exactly as before.
     *
     * On Supernote a raster page has previewed by painting `/dev/ebc` directly since 0.1.41,
     * with the ink daemon disabled across it; a stroke page stayed the daemon's because that
     * path flattens every live pixel against the page images and a stroke page has none.
     * With this on, the flatten base is the **committed picture** — white, template, host
     * content, the strokes — kept as a page image of its own, and the live pen, the point
     * eraser and the lasso's trail all go through the panel the way the raster tools do:
     * nothing changes at pen-up, an erased stroke vanishes under the tip, and the glass
     * shows a blue-noise dither of the page (the export keeps its true greys). Where the
     * panel refuses to open the page stays the daemon's with every law intact, so a host
     * may set this unconditionally.
     *
     * Set it with the page's other properties, before content; flipping it under ink is
     * legal but re-renders the page. No effect in [PageMode.RASTER], which is direct
     * already.
     */
    var directInk: Boolean

    /**
     * Replace [layer]'s page image with a copy of [bitmap] and re-render — the raster twin
     * of [loadStrokes]. Null is a blank layer. The image is page-space: its top-left is
     * the page's, and it is expected to be the page's size ([setPageSize]); a different
     * size is copied at 1:1 from the origin, never stretched, because a page image that no
     * longer registers with the page it was drawn on is a host bug worth seeing rather
     * than hiding.
     *
     * The other layer is untouched, so a two-raster page is loaded with two calls — and
     * a host that persists only one of them leaves the other as it found it, which is why
     * a page turn goes through [clearForContentSwap] first.
     *
     * **Fires nothing on the listener (0.1.33)**, as [swapPageRaster] never has: a
     * change the host made itself — this load, that swap — is the host's own news, and
     * the host already holds whatever history it wants of the page it just handed over.
     * A change the pen or a bake made is announced; this one is not, so a host needs no
     * "we are loading, ignore the callbacks" flag around it. (Until 0.1.32 this call
     * announced a whole-page change, and such a flag was the usual way to swallow it —
     * which only worked because the callbacks happen to be synchronous.)
     *
     * A no-op in [PageMode.STROKE].
     */
    fun loadPageRaster(layer: RasterLayer, bitmap: Bitmap?)

    /** The 0.1.38 form — [RasterLayer.GRAPHITE], the page a pencil-only host always had. */
    fun loadPageRaster(bitmap: Bitmap?) = loadPageRaster(RasterLayer.GRAPHITE, bitmap)

    /**
     * A **copy** of [layer]'s page image — never the live bitmap — or null when that layer
     * is blank or the mode is [PageMode.STROKE]. The raster twin of [getStrokes], and like
     * it the host's save-all: a save encodes the copy off the main thread while the artist
     * keeps drawing into the live one, which is only sound because this is a copy. Main
     * thread only; the copy costs about as long as a `memcpy` of the page.
     *
     * This is one layer, not the picture. What the artist sees is the ink drawn over the
     * graphite (0.1.44; `DARKEN` before it), which is what [renderToBitmap] renders; a host saving a page it means to
     * reload and keep drawing on saves both layers and reloads both, because a flatten
     * cannot be taken apart again.
     */
    fun getPageRaster(layer: RasterLayer): Bitmap?

    /** The 0.1.38 form — [RasterLayer.GRAPHITE]. */
    fun getPageRaster(): Bitmap? = getPageRaster(RasterLayer.GRAPHITE)

    /**
     * A copy of [layer]'s page image inside [rect] (page space, clipped to the page), or
     * null when the rect misses the page, that layer is blank, or the mode is
     * [PageMode.STROKE]. Sized for the host's undo: called from
     * [PaperListener.onRasterWillChange] with the layer and rect it was given, it is the
     * before-image of exactly what is about to change.
     */
    fun copyPageRaster(layer: RasterLayer, rect: Rect): Bitmap?

    /** The 0.1.38 form — [RasterLayer.GRAPHITE]. */
    fun copyPageRaster(rect: Rect): Bitmap? = copyPageRaster(RasterLayer.GRAPHITE, rect)

    /**
     * The pixels of [layer] inside [rect] (page space, clipped to the page) as a
     * [RasterPatch], or null when the rect misses the page or the mode is
     * [PageMode.STROKE] (0.1.29). The before-image for a host's undo, in the form the undo
     * will hand back: call it from [PaperListener.onRasterWillChange] with the layer and
     * rect it was given. A layer that has no image yet reads as transparent — the honest
     * before-image of a first mark, which an undo must be able to take back to nothing.
     * Main thread only.
     */
    fun readPageRaster(layer: RasterLayer, rect: Rect): RasterPatch?

    /** The 0.1.38 form — [RasterLayer.GRAPHITE]. */
    fun readPageRaster(rect: Rect): RasterPatch? = readPageRaster(RasterLayer.GRAPHITE, rect)

    /**
     * Swap [patches] into [layer]'s page image and repaint what they cover (0.1.29). Each
     * patch's pixels replace that layer's inside its rect, and **the patch's array is left
     * holding what the layer held there** — so an undo that swaps a before-image in
     * turns that same patch into the redo, with no second copy and no second call
     * shape. Patches whose rects overlap must be swapped in the reverse of the order
     * they were read to undo, and in reading order to redo; disjoint patches can go in
     * any order. A rect that is not wholly on the page is skipped with a log line
     * rather than clipped, because a patch that no longer registers with the page it was
     * read from is a host bug worth seeing. Fires nothing on the listener: the host made
     * this change and already holds its history. Repaints once for all the patches — on
     * e-ink, only the region they cover. A no-op in [PageMode.STROKE]. Main thread only.
     *
     * **A patch carries no layer of its own** ([RasterPatch] is a rect and pixels), so the
     * layer is this call's parameter and a patch read from one layer must be swapped back
     * into that same one. A host whose undo entry spans both groups its patches by layer
     * and makes one call per layer.
     */
    fun swapPageRaster(layer: RasterLayer, patches: List<RasterPatch>)

    /** The 0.1.38 form — [RasterLayer.GRAPHITE]. */
    fun swapPageRaster(patches: List<RasterPatch>) = swapPageRaster(RasterLayer.GRAPHITE, patches)

    // ── Template & page geometry ─────────────────────────────────────────────

    /**
     * The background rendered behind all content. Not erasable, not a stroke, never
     * reported through data-out. Null = plain white. The bitmap is stretched into the
     * page rect (see [setPageSize]). Performs the required EPD repaint handoff.
     */
    fun setTemplate(bitmap: Bitmap?)

    /**
     * A display-only underlay for a raster page (Phase 46, 0.1.61) — a grid to lay a drawing
     * out on, a reference photo to trace: page-sized ARGB with alpha, drawn over white (and
     * the template) and **under** both page images, on the window and on Supernote's panel
     * alike. Never in [renderToBitmap], never in [getPageRaster], never touched by a rub or
     * a smudge — it is not the page, it is what the page lies on.
     *
     * Read 1:1 from the page origin, never stretched. Held by reference, so the host keeps
     * it alive and unchanged while it is set. Null clears. Sticky across page loads until the
     * next call; dropped when [pageMode] changes and on [release]. A no-op in
     * [PageMode.STROKE].
     *
     * Not a wider [setTemplate]: the template is part of the page a host exports; this is
     * the opposite.
     */
    fun setSheet(bitmap: Bitmap?)

    /**
     * The page-coordinate rect (anchored top-left) that content was authored in — i.e.
     * the surface size of the device the data was created on. The template stretches
     * into this rect, not the view, so ink and template stay registered when data
     * travels between different-sized screens. `0×0` (the default) = use the view's
     * own size. Sticky until the next call.
     */
    fun setPageSize(width: Int, height: Int)

    // ── Host content extension point ─────────────────────────────────────────

    /**
     * Register a renderer that draws host content into the committed layer, z-ordered
     * by [ContentRenderer.layer]. Triggers a re-record. Renderers draw in paper
     * coordinates and may expose [ContentRenderer.hitTargets] to join lasso selection.
     */
    fun addContentRenderer(renderer: ContentRenderer)

    /** Unregister [renderer] (unknown renderer ignored) and re-record. */
    fun removeContentRenderer(renderer: ContentRenderer)

    /**
     * The host's content changed — re-record the committed layer (calling every
     * registered renderer) and repaint, with the appropriate EPD handoff. Batch: call
     * once after a group of changes, not per object.
     */
    fun notifyContentChanged()

    // ── Chrome, exclusion, and gesture cooperation ───────────────────────────

    /**
     * Rects, in view coordinates, where the stylus must NOT ink — the host's toolbar,
     * open menus, floating chrome. Applied live (hardware pen-exclusion on EPD engines;
     * model-side filtering keeps captured data consistent with what was painted).
     * Empty list = no exclusion. Push an updated list whenever chrome opens/closes/moves.
     */
    fun setExclusionRects(rects: List<Rect>)

    /**
     * Release the EPD writing overlay so the next normal refresh shows UI changes —
     * call on any finger interaction with host chrome overlaying the paper (EPD panels
     * otherwise won't show the pressed state / menu until the overlay lets go). No-op
     * on non-EPD engines; re-arms automatically on the next pen-down. Guard calls with
     * [isPenActive] so a resting palm can't drop a live stroke.
     */
    fun releaseRender()

    /**
     * True while the stylus is writing **or hovering near the surface**, and for a short
     * tail (~[PEN_ACTIVE_TAIL_MS]) after either ends. Hover counts because the palm
     * lands a beat before the pen tip touches — on pen hardware that reports proximity
     * (EMR panels), the gate closes as the pen approaches, before any contact.
     *
     * The host's palm-rejection gate: check this at the top of every finger-gesture
     * handler and suppress the gesture when true — on EPD engines a writing stylus
     * produces no MotionEvents but the resting palm does, and an ungated handler that
     * touches the view mid-stroke drops ink. For tap-like gestures, check the gate at
     * finger-**up** (not just down), so a palm that lands before the pen enters hover
     * range is still caught. For taps that *mutate state*, additionally commit after a
     * [PEN_ACTIVE_TAIL_MS] escrow and drop the tap if the gate closes meanwhile — a
     * palm micro-tap can complete before the pen enters hover range (~190 ms measured),
     * which no proximity signal can catch at up-time.
     */
    val isPenActive: Boolean

    // ── Selection (lasso) ────────────────────────────────────────────────────

    /**
     * Dismiss the active selection, if any (fires
     * [PaperListener.onSelectionDismissed]). No-op when nothing is selected.
     */
    fun clearSelection()

    /**
     * Inject a selection from outside — e.g. right after the host pastes strokes via
     * [addStrokes], so the pasted content lands selected and draggable. [strokeIds]
     * must already be loaded; [contentIds] are host object ids; [bounds] is the
     * selection box rect in paper coordinates.
     */
    fun setSelection(strokeIds: Set<String>, contentIds: Set<String>, bounds: Bounds)

    // ── Transform mode (0.1.27) ──────────────────────────────────────────────

    /**
     * Enter transform mode on one host content object: an engine-drawn overlay — the
     * oriented dashed box, eight resize handles, a rotate knob above the top edge — that
     * the stylus (or a palm-gated single finger) drags to move, resize and rotate the
     * geometry. The engine knows nothing about what the object is: it edits [box] and
     * reports it ([PaperListener.onTransformChanged] live, [PaperListener.onTransformEnded]
     * once at exit); the host's renderer draws the object at the reported box through
     * [ContentRenderer.drawObject] while the mode lasts (the committed layer excludes it,
     * as during a lasso drag).
     *
     * Host-initiated, so it dismisses an active selection **without**
     * [PaperListener.onSelectionDismissed] (the [setSelection] rule) and ends a mode
     * already running (with its [PaperListener.onTransformEnded]). Requires [Tool.LASSO]
     * — the pen otherwise inks — and is a no-op in any other tool. A resize honours
     * [aspectLocked] (the ratio of [box]) and clamps every side at [minSizePx]; a rotate
     * snaps within [com.symmetricalpalmtree.gpaper.core.geometry.TransformGeometry.ROTATION_SNAP_DEG]
     * of the cardinals. Exits: [endTransform], a contact outside the overlay (which then
     * proceeds as an ordinary lasso contact — never an [PaperListener.onPaperTapped]),
     * a tool change, any data-in call, [release].
     */
    fun beginTransform(contentId: String, box: OrientedBox, aspectLocked: Boolean, minSizePx: Float)

    /** Leave transform mode (the host's Done): fires [PaperListener.onTransformEnded]
     *  and restores the object to the committed layer. No-op when not transforming. */
    fun endTransform()

    /** Flip the aspect lock of the running mode (the host's toggle). Takes effect on the
     *  next resize; no-op when not transforming. */
    fun setTransformAspectLocked(locked: Boolean)

    /** The content id under transform, or null. */
    val transformingContentId: String?

    /** The box as the mode currently has it (live during a drag), or null. */
    val transformBox: OrientedBox?

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Call from the host Activity's `onResume`. Reclaims the (process-global) pen
     * pipeline without depending on window-focus events: reopens it if released,
     * restarts it if another paper surface claimed it while this one was away, or
     * simply re-enables input. Idempotent.
     */
    fun resumeDrawing()

    /**
     * Call immediately before launching (and finishing into) ANOTHER screen that hosts
     * a [PaperView], so the pipeline is released cleanly while this view still owns it
     * and the successor gets an uncontested claim. Not needed for ordinary navigation
     * to non-paper screens (visibility handling covers that).
     */
    fun releaseForHandoff()

    /**
     * Final teardown: release the pen pipeline (if owned), display resources, and
     * internal buffers. Call from the host's `onDestroy`. The view must not be used
     * afterwards. Safe to call more than once.
     */
    fun release()

    // ── Output ───────────────────────────────────────────────────────────────

    /**
     * Render the current content (template + committed layer) into a new [Bitmap] —
     * for host thumbnails/covers. Independent of the screen state and safe to call
     * while the EPD overlay is live. Returns null if the view is not laid out yet.
     *
     * On a raster page this is the **flatten**: both [RasterLayer]s, ink over graphite —
     * the composite the panel shows — over the white and the template. It is the only
     * call that hands back the picture rather than a layer of it.
     */
    fun renderToBitmap(): Bitmap?

    /**
     * Something other than drawing is about to happen — chrome is about to open over the
     * page, the chrome is flipping, a panel is about to hang over it — and anything the
     * engine has been showing provisionally should be shown as it truly is **first**
     * (0.1.47). On Supernote's direct raster path a baked pen mark stays on the glass as
     * the dither it was drawn as until the next non-drawing event; the engine sees a tool
     * or pen change, a rub, an undo and a page load by itself, but a host's own chrome it
     * cannot see, and a settle painted straight onto the panel *after* a bar has opened
     * would paint over the bar. Call this before showing the chrome. A no-op on every
     * other engine and whenever nothing is waiting.
     */
    fun settleDisplay() {}

    // ── Listeners ────────────────────────────────────────────────────────────

    /** Set (or clear with null) the primary event listener. */
    fun setPaperListener(listener: PaperListener?)

    /** Set (or clear with null) the raw stylus passthrough listener. */
    fun setRawInputListener(listener: RawInputListener?)

    companion object {
        /**
         * How long [isPenActive] stays true after pen-up. Deliberately longer than the
         * platform double-tap window (~300 ms) so the second half of a palm-induced
         * "double tap" can't slip in just after the pen lifts; short enough that a
         * deliberate finger tap right after writing still registers.
         */
        const val PEN_ACTIVE_TAIL_MS: Long = 350L
    }
}
