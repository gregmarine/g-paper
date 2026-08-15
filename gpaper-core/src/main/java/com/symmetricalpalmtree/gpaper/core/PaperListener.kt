package com.symmetricalpalmtree.gpaper.core

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
}
