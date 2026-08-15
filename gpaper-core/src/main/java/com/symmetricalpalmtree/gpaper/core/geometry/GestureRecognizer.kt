package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure-JVM pen-gesture recognition shared by every engine: is a completed pen stroke a
 * **smart lasso** (a quick closed loop meant as a selection) or a **scribble erase**
 * (a dense zigzag meant as a deletion)? Geometry classification only — the recognizers
 * say *candidate*, the caller's hit test says *gesture* (a candidate that touches
 * nothing stays ordinary ink, so writing "o" over blank paper is never consumed).
 *
 * Thresholds are the reference engine's (Notesprout `NotebookConstants`), hardware-tuned
 * there and re-verified on-device this phase. Distance thresholds are expressed in dp
 * and converted by the caller (this object stays Android-free); the velocity gate is
 * deliberately raw px/ms, matching the reference on every panel it shipped on.
 */
object GestureRecognizer {

    /** Minimum smart-lasso speed: pathLength / duration (px/ms). A deliberate selection
     *  loop is quick; careful handwriting (an "O", a zero) is slower. */
    const val SMART_LASSO_MIN_VELOCITY_PX_PER_MS = 0.5f

    /** Maximum first-to-last distance for a "closed" smart-lasso path, in dp. */
    const val SMART_LASSO_CLOSURE_DISTANCE_DP = 50f

    /** Minimum angular sweep around the gesture centroid, in degrees — the circularity
     *  gate: letters and open arcs never wind 270°+ around a central point; loops do. */
    const val SMART_LASSO_MIN_WINDING_DEGREES = 270f

    /** Minimum scribble bounding-box diagonal, in dp — keeps jitter-heavy micro-strokes
     *  from accidentally satisfying the density ratio. */
    const val SCRIBBLE_MIN_DIAGONAL_DP = 40f

    /** Minimum pathLength / boundingBoxDiagonal for a scribble — a natural 3-pass
     *  back-and-forth satisfies this; ordinary writing does not. */
    const val SCRIBBLE_DENSITY_RATIO = 3.0f

    /** Minimum direction reversals (negative dot product between consecutive movement
     *  vectors) on the noise-filtered path. */
    const val SCRIBBLE_MIN_DIRECTION_REVERSALS = 2

    /** Stroke-touch radius for the scribble hit test, in dp (whole-stroke erase on any
     *  touch — eraser-tool semantics). Callers feed this to [EraseHitTest]. */
    const val SCRIBBLE_STROKE_TOUCH_RADIUS_DP = 8f

    /** Points closer than 2 px to the last kept point are collapsed before counting
     *  reversals — digitizer jitter would otherwise fake zigzags. */
    private const val NOISE_MIN_DISTANCE_SQ = 4f

    /**
     * True when [points] reads as a smart-lasso candidate: fast (≥
     * [SMART_LASSO_MIN_VELOCITY_PX_PER_MS], duration taken from the samples' own
     * timestamps), closed (first-to-last ≤ [closureDistancePx] — the dp threshold
     * converted by the caller), and circular (winds ≥ [SMART_LASSO_MIN_WINDING_DEGREES]
     * around its centroid, in either direction).
     */
    fun isSmartLassoCandidate(points: List<StrokePoint>, closureDistancePx: Float): Boolean {
        if (points.size < 4) return false
        val durationMs = points.last().timeMillis - points.first().timeMillis
        if (durationMs <= 0L) return false

        if (pathLength(points) / durationMs < SMART_LASSO_MIN_VELOCITY_PX_PER_MS) return false

        val first = points.first()
        val last = points.last()
        if (hypot(last.x - first.x, last.y - first.y) > closureDistancePx) return false

        // Winding: accumulate signed angular change around the centroid, each step
        // unwrapped to [-π, π] so we measure true incremental rotation, not jumps.
        var cx = 0f
        var cy = 0f
        for (p in points) {
            cx += p.x
            cy += p.y
        }
        cx /= points.size
        cy /= points.size
        var totalAngle = 0.0
        var prevAngle = atan2((points[0].y - cy).toDouble(), (points[0].x - cx).toDouble())
        for (i in 1 until points.size) {
            val angle = atan2((points[i].y - cy).toDouble(), (points[i].x - cx).toDouble())
            var delta = angle - prevAngle
            while (delta > PI) delta -= 2.0 * PI
            while (delta < -PI) delta += 2.0 * PI
            totalAngle += delta
            prevAngle = angle
        }
        return abs(Math.toDegrees(totalAngle)) >= SMART_LASSO_MIN_WINDING_DEGREES
    }

    /**
     * True when [points] reads as a scribble candidate: big enough (bounding-box
     * diagonal ≥ [minDiagonalPx] — the dp threshold converted by the caller), dense
     * ([SCRIBBLE_DENSITY_RATIO]), and zigzagging ([SCRIBBLE_MIN_DIRECTION_REVERSALS]
     * reversals after sub-2 px jitter is collapsed).
     */
    fun isScribbleCandidate(points: List<StrokePoint>, minDiagonalPx: Float): Boolean {
        if (points.size < 4) return false
        val bounds = Bounds.of(points)
        val diagonal = hypot(bounds.width, bounds.height)
        if (diagonal < minDiagonalPx) return false
        if (pathLength(points) / diagonal < SCRIBBLE_DENSITY_RATIO) return false

        val filtered = ArrayList<StrokePoint>(points.size)
        filtered.add(points[0])
        for (p in points) {
            val kept = filtered[filtered.size - 1]
            val dx = p.x - kept.x
            val dy = p.y - kept.y
            if (dx * dx + dy * dy >= NOISE_MIN_DISTANCE_SQ) filtered.add(p)
        }
        if (filtered.size < 3) return false
        var reversals = 0
        for (i in 2 until filtered.size) {
            val ax = filtered[i - 1].x - filtered[i - 2].x
            val ay = filtered[i - 1].y - filtered[i - 2].y
            val bx = filtered[i].x - filtered[i - 1].x
            val by = filtered[i].y - filtered[i - 1].y
            if (ax * bx + ay * by < 0f) reversals++
        }
        return reversals >= SCRIBBLE_MIN_DIRECTION_REVERSALS
    }

    private fun pathLength(points: List<StrokePoint>): Float {
        var length = 0f
        for (i in 1 until points.size) {
            length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return length
    }
}
