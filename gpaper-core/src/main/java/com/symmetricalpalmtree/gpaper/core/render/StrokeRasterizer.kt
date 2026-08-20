package com.symmetricalpalmtree.gpaper.core.render

import android.graphics.Canvas
import android.graphics.Paint
import com.symmetricalpalmtree.gpaper.core.canvas.StrokeRenderer
import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * Offline stroke rasterization (0.1.4): draw committed strokes onto any host [Canvas]
 * without a live, laid-out paper view — the host-facing door to the same internal
 * renderer every engine bakes committed ink through, so the result is pixel-identical
 * to how the strokes look on paper (width, caps, style, pressure modulation).
 *
 * Typical use: a host compositing content it owns (a link/group object's wrapped ink,
 * a thumbnail of rows never loaded on a surface) into its own bitmap:
 *
 * ```
 * val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
 * val canvas = Canvas(bmp)
 * canvas.translate(-bounds.left, -bounds.top)   // strokes are paper-space
 * StrokeRasterizer.draw(canvas, strokes)
 * ```
 *
 * Coordinates are paper-space; the caller applies any transform (translate/scale)
 * before calling. Strokes are drawn in list order — pass them in committed ("order")
 * order to match the paper's own layering. Safe on a software canvas; main thread not
 * required (nothing here touches views), but the canvas must not be shared across
 * threads mid-draw.
 */
object StrokeRasterizer {

    /** Draw [strokes] onto [canvas] in list order, in their committed appearance. */
    fun draw(canvas: Canvas, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        val paint = Paint()
        for (s in strokes) {
            StrokeRenderer.draw(canvas, s.points, s.color, s.width, s.style, paint)
        }
    }
}
