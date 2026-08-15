package com.symmetricalpalmtree.gpaper.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
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

    // ── Template & page geometry ─────────────────────────────────────────────

    /**
     * The background rendered behind all content. Not erasable, not a stroke, never
     * reported through data-out. Null = plain white. The bitmap is stretched into the
     * page rect (see [setPageSize]). Performs the required EPD repaint handoff.
     */
    fun setTemplate(bitmap: Bitmap?)

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
     */
    fun renderToBitmap(): Bitmap?

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
