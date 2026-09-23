package com.symmetricalpalmtree.gpaper.ratta

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The lasso's dashed trail, laid a segment at a time — the pure half of the app-painted
 * trail (Phase 42, the user's decision 2: *"the lasso trail is app-painted on the
 * panel"*).
 *
 * A dash pattern is a property of the whole path: drawn segment by segment with a fresh
 * pattern each time, the dashes would restart at every join and the trail would read as a
 * stutter of short marks. So this keeps the one thing a segment cannot know on its own —
 * **how far along the pattern the path already is** — and hands each new segment the
 * phase to start at, so the dashes continue across the join exactly as one path's would.
 * The joins themselves are exact for the same reason the pen's are (Phase 29): the last
 * point already laid is the first point of the next segment.
 *
 * Pure Kotlin — no Android imports; the caller turns the answer into a `Path` and a
 * `DashPathEffect` with the phase given. Everything is in view coordinates.
 */
internal class TrailSweep(
    /** The dash pattern's period — the sum of its on and off lengths, in px; with
     *  [crosses], the pitch between one x-mark and the next. */
    private val period: Float,
    /**
     * The lasso eraser's trail (0.1.57): instead of a dash phase, each segment carries the
     * **centres of the x-marks** that fall on it — one every [period] of arc length from
     * the outline's first point, laid by the same arc-length rule the committed `CROSS`
     * style uses, so the marks keep their pitch across a join exactly as the dashes keep
     * their phase. A mark that lands on a join belongs to the segment that reached it.
     */
    private val crosses: Boolean = false,
) {
    /** One new stretch of the trail: the points `[from, to)` of the list given, the dash
     *  phase they start at, their bounds (unpadded), and — with [crosses] — the x-mark
     *  centres on it as `x, y` pairs (empty otherwise). */
    class Segment(
        val from: Int,
        val to: Int,
        val phase: Float,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val marks: FloatArray = FloatArray(0),
    )

    /** How many of the outline's points are already laid — a count, so "nothing new" is
     *  one comparison (the pen's [RattaPaperView] rule). */
    var laid = 0
        private set

    /** Arc length laid so far, in px — what the phase is read off. */
    var length = 0f
        private set

    /**
     * The outline grew to [points]: the new segment, or null when nothing new arrived.
     * Starts *at* the last point already laid (the shared join), never after it; a single
     * first point is a segment of one — a dot, which a tap's trail is.
     */
    fun advance(points: List<StrokePoint>): Segment? {
        if (points.size <= laid) return null
        val from = if (laid == 0) 0 else laid - 1
        val phase = length % period
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var prevX = 0f
        var prevY = 0f
        val marks = if (crosses) ArrayList<Float>() else null
        // The next mark's arc length: the first point carries one; after that the first
        // multiple of the pitch *past* what is laid — a mark exactly on the join was the
        // previous segment's (its reach is inclusive).
        var nextAt = if (laid == 0) 0f else (floor(length / period) + 1f) * period
        if (marks != null && laid == 0) {
            marks.add(points[0].x); marks.add(points[0].y)
            nextAt = period
        }
        for (i in from until points.size) {
            val p = points[i]
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            if (i > from) {
                val segLen = hypot(p.x - prevX, p.y - prevY)
                if (marks != null && segLen > 0f) {
                    while (length + segLen >= nextAt) {
                        val t = (nextAt - length) / segLen
                        marks.add(prevX + t * (p.x - prevX)); marks.add(prevY + t * (p.y - prevY))
                        nextAt += period
                    }
                }
                length += segLen
            }
            prevX = p.x
            prevY = p.y
        }
        val to = points.size
        laid = to
        return Segment(from, to, phase, minX, minY, maxX, maxY, marks?.toFloatArray() ?: FloatArray(0))
    }
}
