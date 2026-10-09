package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint

/**
 * The marker's ends, kept flat (Phase 52, 0.1.69 — the host's decision, 2026-10-08: "keep flat
 * ends, trim the wobble").
 *
 * A marker is one path with butt ends and round joins, and a hand's last few samples wobble
 * inside the marker's own width: a short stub turning out of the round join's disc shows as a
 * notch, a small hook as a rounded end, a straight finish as the flat end the style promises.
 * So the samples that lie **within half the width of either end** are dropped before the path
 * is drawn — the end is then one straight segment from the last sample outside that zone to the
 * end point itself, and the butt cap is perpendicular to it. Nothing beyond the zone moves, and
 * the end point itself is always kept, so the mark still ends exactly where the pen lifted.
 *
 * Pure, and the one place the rule lives: the renderer trims every marker it draws, and the
 * Ratta direct path asks [stableIndex] where the end's zone begins so its live layer can be
 * rewritten there (the trimmed end changes as the stroke grows) rather than merged.
 */
object MarkerTrim {

    /**
     * The index of the last sample that is **not** within [half] of the end — the sample the
     * end's one straight segment starts from. `0` for a path whose every interior sample lies in
     * the zone (the end segment then runs from the first point). For a path of fewer than three
     * points there is nothing to trim: the last index but one.
     */
    fun stableIndex(points: List<StrokePoint>, half: Float): Int {
        if (points.size < 3) return (points.size - 2).coerceAtLeast(0)
        val end = points[points.size - 1]
        val limit = half * half
        var i = points.size - 2
        while (i > 0 && dist2(points[i], end) < limit) i--
        return i
    }

    /** [stableIndex] for the start: the first sample not within [half] of the first point. */
    fun stableStartIndex(points: List<StrokePoint>, half: Float): Int {
        if (points.size < 3) return (points.size - 1).coerceAtLeast(0).coerceAtMost(1)
        val start = points[0]
        val limit = half * half
        var i = 1
        while (i < points.size - 1 && dist2(points[i], start) < limit) i++
        return i
    }

    /**
     * [points] with the wobble trimmed from both ends: the first point, the samples outside both
     * zones, and the last point. Never fewer than two points for an input of two or more, and the
     * input itself when nothing is in either zone.
     */
    fun trim(points: List<StrokePoint>, half: Float): List<StrokePoint> {
        if (points.size < 3) return points
        val s = stableStartIndex(points, half)
        val e = stableIndex(points, half)
        if (s == 1 && e == points.size - 2) return points
        if (e < s) return listOf(points[0], points[points.size - 1])
        val out = ArrayList<StrokePoint>(e - s + 3)
        out += points[0]
        for (i in s..e) out += points[i]
        out += points[points.size - 1]
        return out
    }

    /**
     * Where a marker too small to be a path is laid as one **dab** (0.1.70) — a square the
     * marker's width, centred on the middle of the mark's bounds — or null when it is a path.
     *
     * Trimming both ends of a small scrub leaves the segment between its first and last
     * points, which can be a sliver or nothing at all while the hand clearly made a mark. So
     * a mark is a dab when it is one sample; when the whole of it lies in the two end zones
     * and its ends are within [half] of each other (it never left a width-sized patch); or
     * when what the trim leaves is under a pixel long. The renderer asks this before it
     * trims, so the bake and every engine's live lay agree.
     */
    fun dab(points: List<StrokePoint>, half: Float): StrokePoint? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points[0]
        if (points.size >= 3 &&
            stableIndex(points, half) < stableStartIndex(points, half) &&
            dist2(points[0], points[points.size - 1]) < half * half
        ) return centre(points)
        if (length(trim(points, half)) < 1f) return centre(points)
        return null
    }

    /**
     * Whether the start's zone is still open: no sample but the newest has left it, so the
     * trimmed path's first segment (and whether the mark is a [dab] at all) can still change
     * as samples arrive. A live lay must then rewrite the mark from its first point.
     */
    fun startUnsettled(points: List<StrokePoint>, half: Float): Boolean =
        points.size < 3 || stableStartIndex(points, half) >= stableIndex(points, half)

    private fun centre(points: List<StrokePoint>): StrokePoint {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        return StrokePoint((minX + maxX) / 2f, (minY + maxY) / 2f)
    }

    private fun length(points: List<StrokePoint>): Float {
        var total = 0f
        for (i in 1 until points.size) total += kotlin.math.sqrt(dist2(points[i - 1], points[i]))
        return total
    }

    private fun dist2(a: StrokePoint, b: StrokePoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
