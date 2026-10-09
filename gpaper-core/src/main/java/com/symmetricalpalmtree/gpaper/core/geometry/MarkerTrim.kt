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

    private fun dist2(a: StrokePoint, b: StrokePoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
