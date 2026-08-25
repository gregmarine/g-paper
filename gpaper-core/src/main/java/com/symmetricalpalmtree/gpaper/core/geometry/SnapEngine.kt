package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import kotlin.math.abs

/**
 * One guide line the selection caught during a snapped drag. Positions are in paper
 * coordinates; a guide is drawn edge to edge across the surface, so only the one
 * coordinate matters.
 */
sealed class SnapGuide {
    data class Vertical(val x: Float) : SnapGuide()
    data class Horizontal(val y: Float) : SnapGuide()
}

/**
 * The outcome of one snap pass: the drag delta to actually apply, plus the guides to
 * draw. [guides] holds at most one of each orientation and is empty when nothing was
 * within range — in which case [dx]/[dy] are the raw delta untouched.
 */
data class SnapResult(
    val dx: Float,
    val dy: Float,
    val guides: List<SnapGuide>,
)

/**
 * Snap-to-guide for a selection drag: pure geometry, no Android, no state.
 *
 * The idea is a paper one. While a selection is dragged, a small number of *meaningful*
 * positions on the page pull it in — the page's own edges, a margin inset from them, the
 * page centre, and the edges and centres of the other objects already placed. The result
 * is alignment that costs no extra gesture: you drag roughly where you meant, and it
 * lands exactly there.
 *
 * ### What is a guide
 *
 * **Page guides** (per axis, five each): the two edges, the two margin insets, the
 * centre. Measured against the page rect the host declared, so they agree with the
 * template rather than with the window.
 *
 * **Object guides** (per non-selected target, five per axis): `left − margin`, `left`,
 * `centerX`, `right`, `right + margin`, and the same on Y. The two ±margin *proximity*
 * guides are what make equal spacing fall out of an ordinary drag: dragging one object
 * below another catches exactly one margin-width from its neighbour's edge.
 *
 * ### How one is chosen
 *
 * The selection contributes three anchors per axis — leading edge, centre, trailing edge.
 * Every (anchor, guide) pair on an axis is measured; the nearest within [thresholdPx]
 * wins and the delta is adjusted by `guide − anchor`, which pulls that one anchor flush.
 * The axes are decided independently, so a selection can be centred horizontally while
 * riding a neighbour's top edge.
 *
 * Nothing is ever clamped. A guide holds only while the pen is within the threshold of
 * it; keep dragging and the selection simply leaves. That is the whole reason the
 * threshold is small — snapping must never feel like resistance.
 */
object SnapEngine {

    /**
     * Snap [box] — the selection's **tight** bounds in its pre-drag position — as it is
     * dragged by ([rawDx], [rawDy]).
     *
     * Tight, not the inflated box the overlay draws: the user is aligning content, so an
     * object snapped to the top margin should start at the margin, not a chrome inset
     * inside it. [targets] are the other objects' tight bounds, on the same footing.
     *
     * [pageWidth]/[pageHeight] are the page rect; a non-positive value drops that axis's
     * page guides (object guides still apply). [marginPx] serves as both the page margin
     * inset and the object proximity gap; zero collapses the margin guides onto the
     * edges and the proximity guides onto the object edges, which is harmless.
     */
    fun computeSnap(
        box: Bounds,
        rawDx: Float,
        rawDy: Float,
        pageWidth: Float,
        pageHeight: Float,
        marginPx: Float,
        thresholdPx: Float,
        targets: List<Bounds> = emptyList(),
    ): SnapResult {
        if (thresholdPx <= 0f) return SnapResult(rawDx, rawDy, emptyList())

        val vGuides = ArrayList<Float>(5 + targets.size * 5)
        if (pageWidth > 0f) {
            vGuides.add(0f)
            vGuides.add(marginPx)
            vGuides.add(pageWidth / 2f)
            vGuides.add(pageWidth - marginPx)
            vGuides.add(pageWidth)
        }
        for (t in targets) {
            vGuides.add(t.left - marginPx)
            vGuides.add(t.left)
            vGuides.add((t.left + t.right) / 2f)
            vGuides.add(t.right)
            vGuides.add(t.right + marginPx)
        }

        val hGuides = ArrayList<Float>(5 + targets.size * 5)
        if (pageHeight > 0f) {
            hGuides.add(0f)
            hGuides.add(marginPx)
            hGuides.add(pageHeight / 2f)
            hGuides.add(pageHeight - marginPx)
            hGuides.add(pageHeight)
        }
        for (t in targets) {
            hGuides.add(t.top - marginPx)
            hGuides.add(t.top)
            hGuides.add((t.top + t.bottom) / 2f)
            hGuides.add(t.bottom)
            hGuides.add(t.bottom + marginPx)
        }

        val x = bestSnap(
            anchors = floatArrayOf(
                box.left + rawDx,
                (box.left + box.right) / 2f + rawDx,
                box.right + rawDx,
            ),
            guides = vGuides,
            thresholdPx = thresholdPx,
        )
        val y = bestSnap(
            anchors = floatArrayOf(
                box.top + rawDy,
                (box.top + box.bottom) / 2f + rawDy,
                box.bottom + rawDy,
            ),
            guides = hGuides,
            thresholdPx = thresholdPx,
        )

        val guides = ArrayList<SnapGuide>(2)
        x?.let { guides.add(SnapGuide.Vertical(it.at)) }
        y?.let { guides.add(SnapGuide.Horizontal(it.at)) }

        return SnapResult(
            dx = if (x != null) rawDx + x.adjust else rawDx,
            dy = if (y != null) rawDy + y.adjust else rawDy,
            guides = guides,
        )
    }

    /** A caught guide: where it sits, and how far the delta must move to reach it. */
    private class Catch(val at: Float, val adjust: Float)

    /**
     * Nearest (anchor, guide) pair within [thresholdPx], or null. Ties keep the first
     * pair found, so the ordering above (page guides before object guides, leading edge
     * before centre before trailing) is what breaks them — deterministic, and it favours
     * the page's own structure over an object that happens to sit on it.
     */
    private fun bestSnap(anchors: FloatArray, guides: List<Float>, thresholdPx: Float): Catch? {
        var best: Catch? = null
        var bestDist = thresholdPx
        for (anchor in anchors) {
            for (guide in guides) {
                val dist = abs(anchor - guide)
                if (dist < bestDist) {
                    bestDist = dist
                    best = Catch(at = guide, adjust = guide - anchor)
                }
            }
        }
        return best
    }
}
