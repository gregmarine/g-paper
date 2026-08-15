package com.symmetricalpalmtree.gpaper.core.model

/**
 * An immutable axis-aligned rectangle in paper coordinates (pixels).
 *
 * A pure-Kotlin stand-in for `android.graphics.RectF` so the data model has no Android
 * dependency and stays JVM-testable. Edges are **inclusive** for [contains] and [intersects]
 * — a point on the border counts as inside, and two rects that merely touch count as
 * intersecting. This is deliberately more permissive than `RectF` (which uses strict
 * comparisons) because the primary use is broad-phase hit rejection, where a false positive
 * is corrected by the narrow-phase check and a false negative loses ink.
 */
data class Bounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * True when this rect has no area (`left >= right || top >= bottom`), matching `RectF`
     * semantics. Note a single-point stroke produces zero-size — and therefore "empty" —
     * bounds; callers doing hit tests should [inflated] by their hit radius first, which
     * turns a degenerate rect into a testable one.
     */
    val isEmpty: Boolean get() = left >= right || top >= bottom

    /** Inclusive-edge containment test. */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** Inclusive-edge overlap test — touching rects intersect. */
    fun intersects(other: Bounds): Boolean =
        left <= other.right && other.left <= right &&
            top <= other.bottom && other.top <= bottom

    /** Smallest rect containing both this and [other]. */
    fun union(other: Bounds): Bounds = Bounds(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    /** This rect grown outward by [amount] px on every side (negative shrinks). */
    fun inflated(amount: Float): Bounds =
        Bounds(left - amount, top - amount, right + amount, bottom + amount)

    /** This rect shifted by ([dx], [dy]). */
    fun offset(dx: Float, dy: Float): Bounds =
        Bounds(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        /** The canonical zero rect. */
        val ZERO = Bounds(0f, 0f, 0f, 0f)

        /**
         * Tight bounding box of [points], computed in one pass. Returns [ZERO] for an
         * empty list.
         */
        fun of(points: List<StrokePoint>): Bounds {
            if (points.isEmpty()) return ZERO
            var minX = points[0].x
            var minY = points[0].y
            var maxX = minX
            var maxY = minY
            for (i in 1 until points.size) {
                val p = points[i]
                if (p.x < minX) minX = p.x else if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y else if (p.y > maxY) maxY = p.y
            }
            return Bounds(minX, minY, maxX, maxY)
        }
    }
}
