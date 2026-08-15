package com.symmetricalpalmtree.gpaper.core.model

/**
 * A live lasso selection, as reported by [com.symmetricalpalmtree.gpaper.core.PaperListener.onSelectionCreated]
 * and injectable via [com.symmetricalpalmtree.gpaper.core.PaperView.setSelection].
 *
 * @property strokeIds Ids of the selected strokes ([Stroke.id]).
 * @property contentIds Ids of selected host content objects, matched from the
 *   [com.symmetricalpalmtree.gpaper.core.render.HitTarget]s the host's content renderers
 *   exposed. Empty if no host content participated.
 * @property bounds Union bounding box of everything selected, in paper coordinates —
 *   also the rect the selection box overlay is drawn around.
 */
data class Selection(
    val strokeIds: Set<String>,
    val contentIds: Set<String>,
    val bounds: Bounds,
)

/**
 * The completed result of a selection drag-move, reported once on stylus lift via
 * [com.symmetricalpalmtree.gpaper.core.PaperListener.onSelectionMoved].
 *
 * The engine has already translated its **in-memory** strokes and re-rendered, so the
 * screen is correct when this fires; the host's job is to apply the same translation to
 * its persistent data (e.g. via [Stroke.translated]) and to any moved content objects.
 * A host that must reject the move restores the previous state with
 * [com.symmetricalpalmtree.gpaper.core.PaperView.loadStrokes].
 *
 * @property strokeIds Ids of the strokes that moved.
 * @property contentIds Ids of the host content objects that moved (host re-renders them
 *   at their new position and calls `notifyContentChanged()`).
 * @property dx Applied horizontal translation in px.
 * @property dy Applied vertical translation in px.
 */
data class SelectionMove(
    val strokeIds: Set<String>,
    val contentIds: Set<String>,
    val dx: Float,
    val dy: Float,
)
