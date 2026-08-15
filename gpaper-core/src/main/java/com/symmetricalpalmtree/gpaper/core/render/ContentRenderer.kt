package com.symmetricalpalmtree.gpaper.core.render

import android.graphics.Canvas
import com.symmetricalpalmtree.gpaper.core.model.Bounds

/** Where a [ContentRenderer] draws relative to the committed stroke layer. */
enum class ContentLayer {
    /** Above the template/background, below the ink. */
    BELOW_STROKES,

    /** Above the ink. */
    ABOVE_STROKES,
}

/**
 * A host content object's participation in lasso selection.
 *
 * @property contentId Host-chosen stable id; comes back in
 *   [com.symmetricalpalmtree.gpaper.core.model.Selection.contentIds] and
 *   [com.symmetricalpalmtree.gpaper.core.model.SelectionMove.contentIds].
 * @property bounds The object's hit rectangle in paper coordinates.
 */
data class HitTarget(
    val contentId: String,
    val bounds: Bounds,
)

/**
 * The host's extension point for rendering its own content (text, images, shapes, links —
 * anything) into the paper's committed layer.
 *
 * g-paper never owns or interprets host content: it only calls [draw] while re-recording
 * the committed layer, z-ordered by [layer] relative to the ink. When the host's content
 * changes, it calls [com.symmetricalpalmtree.gpaper.core.PaperView.notifyContentChanged]
 * to trigger a re-record — [draw] is never called spontaneously per frame, so it may be
 * moderately expensive but must not assume any call frequency.
 *
 * Contract for [draw]:
 * - Called on the main thread with a canvas in **paper coordinates** (same space as
 *   stroke points). Draw only; never touch views or call back into [PaperView].
 * - May be invoked on a software canvas (EPD repaint paths) — avoid hardware-only
 *   canvas features.
 *
 * [hitTargets] lets host objects participate in lasso selection: return current bounds
 * for each selectable object (empty list = content is not selectable). Called on the
 * main thread when a lasso gesture completes.
 *
 * ### Live drag of selected host content (optional)
 *
 * The component cannot repaint host objects itself, so by default a selected object
 * stays put during a selection drag-move (a dashed ghost of its bounds rides the drag)
 * and jumps once the host repositions it on
 * [com.symmetricalpalmtree.gpaper.core.PaperListener.onSelectionMoved]. A renderer opts
 * into **true live drag** by implementing both hooks:
 * - the exclusion-aware [draw] overload, so the original disappears from the committed
 *   layer while its live copy is dragged, and
 * - [drawObject], which paints just one object; the component translates the canvas by
 *   the drag delta before calling it.
 * Implement both or neither — exclusion without [drawObject] makes the object vanish
 * during the drag; [drawObject] without exclusion draws it twice.
 */
interface ContentRenderer {
    val layer: ContentLayer get() = ContentLayer.BELOW_STROKES

    fun draw(canvas: Canvas)

    /**
     * Draw all content EXCEPT the objects in [excludedContentIds]. Used while a
     * selection drag-move re-records the committed layer, so an object being dragged
     * is not also painted at its original spot. The default ignores the exclusion
     * (delegates to [draw]) — safe, but the dragged object then appears frozen at its
     * origin until the drop. See the class KDoc for the live-drag contract.
     */
    fun draw(canvas: Canvas, excludedContentIds: Set<String>) = draw(canvas)

    fun hitTargets(): List<HitTarget> = emptyList()

    /**
     * Draw ONLY the object [contentId], at its current (pre-drag) position — the
     * component has already translated the canvas by the drag delta. Return true if
     * drawn; the default false makes the component fall back to a dashed ghost of the
     * object's [HitTarget] bounds. Same canvas contract as [draw]; called at drag
     * refresh rate, so keep it cheap.
     */
    fun drawObject(canvas: Canvas, contentId: String): Boolean = false
}
