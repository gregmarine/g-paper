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
 */
interface ContentRenderer {
    val layer: ContentLayer get() = ContentLayer.BELOW_STROKES

    fun draw(canvas: Canvas)

    fun hitTargets(): List<HitTarget> = emptyList()
}
