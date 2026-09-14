package com.symmetricalpalmtree.gpaper.core

import android.graphics.Rect
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The host's window into everything the paper does. All callbacks fire on the main
 * thread. Every method has an empty default so hosts implement only what they use.
 *
 * Set via [PaperView.setPaperListener] before the first user interaction.
 */
interface PaperListener {

    /**
     * A stroke was completed and entered the component's in-memory model. Fires once per
     * stroke, on pen-up. This is the data-out moment: persist [stroke] (keyed by
     * [Stroke.id]) and record it in the host's undo history.
     *
     * On EPD engines the visual "bake" of the stroke may be deferred past this call
     * (firmware overlay handoff) — the data is final even when the pixels are still
     * the live overlay's.
     */
    fun onStrokeCommitted(stroke: Stroke) {}

    /**
     * Strokes were erased from the in-memory model (eraser tool). May fire multiple
     * times during one erase gesture as the eraser sweeps; each id is reported exactly
     * once. The host soft-deletes/deletes its rows and records undo.
     */
    fun onStrokesErased(strokeIds: List<String>) {}

    /**
     * The eraser tool swept over host content objects (0.1.4) — whole-object semantics:
     * touching any part of a [ContentRenderer hit target]
     * [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.hitTargets] erases the
     * whole object, exactly as whole-stroke erase treats ink. May fire multiple times
     * during one erase gesture as the eraser sweeps; each id is reported at most once per
     * gesture. The component owns no content, so nothing disappears by itself: the host
     * deletes its rows, records undo, and calls [PaperView.notifyContentChanged] — until
     * then the object stays on the committed layer (and its hit target keeps it findable,
     * which is why the per-gesture dedup matters). The eraser tool (and the stylus
     * barrel/eraser end) is what reports here; a **scribble** reports through
     * [onScribbleErased] instead, so one gesture stays one host undo entry.
     */
    fun onContentErased(contentIds: List<String>) {}

    /**
     * A scribble-erase gesture was consumed (0.1.23): a dense zigzag pen stroke crossed out
     * [strokeIds] and [contentIds] in one act. Fires **once**, on pen lift; the scribble
     * stroke itself is never committed and never reported.
     *
     * Semantics per kind are exactly the eraser tool's — whole strokes, whole content
     * objects — but they arrive together **because they are one gesture**: a host that
     * recorded [onStrokesErased] and [onContentErased] separately would leave the user two
     * undo steps to reverse one scribble. Either list may be empty (never both). Content
     * ids come from [ContentRenderer.hitTargets]
     * [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.hitTargets] and are
     * decided by a **penetration** rule, not the eraser's touch rule
     * ([com.symmetricalpalmtree.gpaper.core.geometry.EraseHitTest.scribbleContentIds]).
     *
     * The strokes are already out of the component's model; the content is not — the
     * component owns no content, so the host deletes its rows here, exactly as it does for
     * [onContentErased].
     *
     * **Do not call [PaperView.notifyContentChanged] from this callback.** Unlike the
     * eraser tool's mid-sweep reports, the component re-records the committed layer itself
     * the moment this call returns — a gesture ends here. A host that repaints as well
     * costs a second frame, and on an EPD engine that is a second visible refresh whose
     * first half shows the ink gone and the content still standing: one gesture reading as
     * two erases. Mutate your content and return.
     *
     * **The default preserves pre-0.1.23 behaviour**: it forwards to [onStrokesErased] and
     * [onContentErased], so a host that has not adopted this method behaves exactly as
     * before (two callbacks, and content that a scribble could not reach before now can).
     * Override it to make one scribble one undo entry.
     */
    fun onScribbleErased(strokeIds: List<String>, contentIds: List<String>) {
        if (strokeIds.isNotEmpty()) onStrokesErased(strokeIds)
        if (contentIds.isNotEmpty()) onContentErased(contentIds)
    }

    /**
     * One [Tool.LASSO_ERASER] outline, whole (0.1.28): the strokes the loop took (any point
     * inside — [com.symmetricalpalmtree.gpaper.core.geometry.LassoHitTest.hitStrokeIds]) and
     * the host content objects it touched
     * ([com.symmetricalpalmtree.gpaper.core.geometry.LassoHitTest.polygonIntersectsBounds]
     * over [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.hitTargets]) — the
     * lasso's own selection rule, so a loop erases exactly what it would have selected. In
     * **one** call so the host can record one undo entry. Never fires for a loop that took
     * nothing, and never for a tap-sized contact.
     *
     * The strokes are already out of the component's model; the content is not — the host
     * deletes its rows here, exactly as for [onScribbleErased]. **Do not call
     * [PaperView.notifyContentChanged] from this callback** — the component re-records the
     * committed layer itself the moment this call returns (the scribble rule: a second
     * repaint is a second EPD refresh showing one erase as two).
     *
     * **The default forwards** to [onStrokesErased] and [onContentErased], so a host that has
     * not adopted this method keeps working (two callbacks, two undo entries). Override it to
     * make one loop one undo entry.
     */
    fun onLassoErased(strokeIds: List<String>, contentIds: List<String>) {
        if (strokeIds.isNotEmpty()) onStrokesErased(strokeIds)
        if (contentIds.isNotEmpty()) onContentErased(contentIds)
    }

    /**
     * The page image is about to change inside [rect] (page space) — [PageMode.RASTER]
     * only (0.1.25). Fires on the main thread immediately before the pixels are touched,
     * for every change: a mark composited at pen-up, a load, a clear, and (from 0.1.26)
     * each batch of an eraser sweep. This is the host's one chance at a before-image for
     * undo — [PaperView.readPageRaster] with this rect, now, holds exactly the pixels the
     * change will overwrite, in the shape [PaperView.swapPageRaster] takes back (0.1.29;
     * [PaperView.copyPageRaster] is the bitmap form). The engine keeps no history in
     * either mode. The rect is
     * generous by design (the mark's bounds pushed out by its width and a margin, clipped
     * to the page), so a before-image taken from it always covers the change.
     */
    fun onRasterWillChange(rect: Rect) {}

    /**
     * The page image changed inside [rect] (page space) — the closing half of
     * [onRasterWillChange], same rect, after the pixels were touched. The host's dirty
     * flag: a raster page has no per-mark rows to write, so this is what schedules its
     * save. In [PageMode.RASTER] a composited mark still reports through
     * [onStrokeCommitted] as well (the host keeps its timestamps and edit counts from
     * one place in both modes) — the stroke it carries is the one just composited, and
     * a raster host must not store it as a row.
     */
    fun onRasterChanged(rect: Rect) {}

    /**
     * The stylus lifted after writing. Fires after [onStrokeCommitted] for the same
     * contact. A save/checkpoint trigger only — it implies nothing about the EPD
     * overlay state, and hosts must not drive tool or lifecycle changes from it.
     */
    fun onPenLifted() {}

    /**
     * A lasso gesture completed and selected at least one stroke or content object.
     * The component is already displaying the selection box overlay; the host typically
     * shows its own selection chrome (toolbar etc.) anchored to [Selection.bounds],
     * pushing any chrome rect via [PaperView.setExclusionRects].
     */
    fun onSelectionCreated(selection: Selection) {}

    /**
     * The active selection was dismissed (tap outside, tool change, [PaperView.clearSelection]).
     * The overlay is already gone; the host hides its selection chrome.
     */
    fun onSelectionDismissed() {}

    /**
     * A drag-move on the current selection crossed the drag threshold. The host hides
     * floating chrome for the duration; the matching end signal is [onSelectionMoved]
     * (or [onSelectionDismissed] if the drag is cancelled).
     */
    fun onSelectionDragStarted() {}

    /**
     * A drag-move completed. The component has already translated its in-memory strokes
     * and re-rendered; the host applies the same delta to its persisted strokes and its
     * own content objects (see [SelectionMove]) and records undo. The selection remains
     * active at its new position.
     */
    fun onSelectionMoved(move: SelectionMove) {}

    /**
     * A sub-threshold tap landed **inside** the active selection box — a stylus tap, or a
     * single-finger tap — and the selection stays put (0.1.1). ([x], [y]) are paper
     * coordinates. Fires for any selection contents (strokes-only included); the host
     * decides what a tap means (typically: open the tapped content object for editing,
     * ignore otherwise). Drags are unaffected: this fires only when the drag threshold
     * was never crossed. The finger variant is palm-gated and commits after the
     * [PaperView.PEN_ACTIVE_TAIL_MS] escrow, exactly like the component's own finger
     * tap-to-dismiss; a stylus tap fires at pen-up. A tap **outside** the box still
     * dismisses ([onSelectionDismissed]) and never reaches this.
     */
    fun onSelectionTapped(x: Float, y: Float) {}

    /**
     * A sub-threshold **stylus** tap landed on bare paper in [Tool.LASSO] with **no
     * selection active** (0.1.5) — the companion to [onSelectionTapped], which owns every
     * tap *inside* a selection box. ([x], [y]) are paper coordinates (the pen-up point).
     * Fires at pen-up, once per contact; the host decides what an empty-handed lasso tap
     * means (typically: paste the clipboard centred here).
     *
     * Never fires for a finger or a palm — the component's finger path only ever drags or
     * dismisses an *active* selection, so a contact reaching here was a stylus by
     * construction; there is nothing to escrow. Never fires for the tap that **dismissed**
     * a selection: a selection was active at pen-down, so that contact is spent on the
     * dismissal ([onSelectionDismissed]) and the user must tap again. Never fires for an
     * outline that caught something (that is [onSelectionCreated]), for a cancelled
     * contact, or in any other tool.
     *
     * A tap over existing *unselected* ink still fires — "bare paper" here means "no
     * selection box", not "no content".
     */
    fun onPaperTapped(x: Float, y: Float) {}

    // ── Transform mode (0.1.27) ──────────────────────────────────────────────

    /**
     * The box under transform moved — **live**, throttled to the lasso refresh cadence
     * while a handle, the knob or the body is being dragged, and once more, unthrottled,
     * when the contact lifts. The host updates its working copy of the object's
     * geometry and nothing else: the engine repaints straight after this returns, drawing
     * the object through [com.symmetricalpalmtree.gpaper.core.render.ContentRenderer.drawObject]
     * (the live-drag pair), so a renderer that reads the working copy shows the object
     * at [box]. Persisting here would write once per sample; persist on
     * [onTransformEnded]. Never fires for a contact that changed nothing (a tap on the
     * body).
     */
    fun onTransformChanged(contentId: String, box: OrientedBox) {}

    /**
     * Transform mode ended — by [PaperView.endTransform], a contact outside the overlay,
     * a tool change, any data-in call, or a new [PaperView.beginTransform]. Fires exactly
     * once per mode, on every exit including the host's own, with the box the mode began
     * on and the box it ends on (equal when nothing moved). The overlay is already gone
     * and the committed layer draws the object again; the host persists [after], records
     * `before → after` for undo, and tears down its transform chrome. Nothing is
     * selected afterwards — re-select with [PaperView.setSelection] if the object should
     * stay under the lasso.
     */
    fun onTransformEnded(contentId: String, before: OrientedBox, after: OrientedBox) {}

    /**
     * The component changed [PaperView.tool] **itself** — sync toolbar/tool UI here.
     * Fired only for component-initiated changes; host assignments to
     * [PaperView.tool] are never echoed. Today the only source is the smart-lasso
     * session ([PaperView.smartLassoEnabled]): the switch to [Tool.LASSO] on trigger
     * (immediately before [onSelectionCreated]) and the restore to [Tool.PEN] when the
     * session's selection lifecycle ends. The restore can arrive *after*
     * [onSelectionDismissed] (a pen tap-away dismisses at pen-down but restores at
     * pen-up), which is why reading [PaperView.tool] inside the selection callbacks is
     * not a reliable substitute for this signal.
     */
    fun onToolChanged(tool: Tool) {}
}
