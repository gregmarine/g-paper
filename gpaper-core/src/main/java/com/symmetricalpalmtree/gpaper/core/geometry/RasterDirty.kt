package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The patch of page a mark is about to change, in raster mode (0.1.25).
 *
 * A host keeping undo for a raster page needs a before-image of exactly the pixels a
 * mark will touch, and it needs it *before* the mark lands. The engine cannot hand it
 * the pixels — the host owns history — so it hands it this rectangle, in page space,
 * through [com.symmetricalpalmtree.gpaper.core.PaperListener.onRasterWillChange]. It
 * must therefore err outward: a rect that misses one fleck leaves one fleck that undo
 * cannot take back, and the artist sees a mark that will not quite go away.
 *
 * The rect is the mark's point bounds pushed out by its full width plus [MARGIN_PX].
 * The full width rather than half of it because the styles do not all stay inside
 * their nominal width — the graphite fleck sits jittered off its lattice site and is
 * drawn as a round dot of up to the lead's width, and the fountain nib swells with
 * pressure — and the margin covers antialiasing and the rounding to whole pixels. The
 * cost of generosity is a few extra pixels copied; the cost of meanness is a wrong
 * undo. Then it is snapped outward to integers and clipped to the page, and a mark
 * wholly off the page yields `null`: nothing will change, so nothing is announced.
 *
 * Pure Kotlin so the rule is proved by a JVM test rather than read off a screenshot.
 */
object RasterDirty {

    /** Slack beyond the width on every side, in px: antialiasing and the rounding out. */
    const val MARGIN_PX: Float = 2f

    /**
     * The integer, page-clipped rect a mark of [width] drawn through [bounds] may
     * touch on a page of [pageWidth] × [pageHeight], or `null` when the mark cannot
     * touch the page at all. Edges are whole numbers stored as floats so callers can
     * bridge to an `android.graphics.Rect` without a second rounding.
     */
    fun of(bounds: Bounds, width: Float, pageWidth: Int, pageHeight: Int): Bounds? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val pad = (if (width > 0f) width else 0f) + MARGIN_PX
        val left = floor(bounds.left - pad).coerceAtLeast(0f)
        val top = floor(bounds.top - pad).coerceAtLeast(0f)
        val right = ceil(bounds.right + pad).coerceAtMost(pageWidth.toFloat())
        val bottom = ceil(bounds.bottom + pad).coerceAtMost(pageHeight.toFloat())
        if (left >= right || top >= bottom) return null
        return Bounds(left, top, right, bottom)
    }

    /** The whole page, for a change that replaces it (a load, a clear). */
    fun wholePage(pageWidth: Int, pageHeight: Int): Bounds? =
        if (pageWidth <= 0 || pageHeight <= 0) null
        else Bounds(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
}
