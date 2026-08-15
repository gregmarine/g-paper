package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognizer gates, pinned against the reference thresholds. Tests pass px thresholds
 * directly (density 1): closure 50 px, min diagonal 40 px.
 */
class GestureRecognizerTest {

    private val closurePx = GestureRecognizer.SMART_LASSO_CLOSURE_DISTANCE_DP
    private val minDiagonalPx = GestureRecognizer.SCRIBBLE_MIN_DIAGONAL_DP

    /** [sweepDegrees] of a circle around (cx, cy), evenly timed across [durationMs]. */
    private fun arc(
        cx: Float,
        cy: Float,
        radius: Float,
        sweepDegrees: Float,
        durationMs: Long,
        steps: Int = 48,
    ): List<StrokePoint> = (0..steps).map { i ->
        val angle = Math.toRadians((sweepDegrees * i / steps).toDouble())
        StrokePoint(
            x = cx + radius * cos(angle).toFloat(),
            y = cy + radius * sin(angle).toFloat(),
            pressure = 1f,
            tilt = 0f,
            timeMillis = durationMs * i / steps,
        )
    }

    /** Horizontal zigzag: [passes] traversals of [width] px with a slight continuous
     *  downward drift (so pass turnarounds are true direction reversals, not two
     *  perpendicular steps). */
    private fun zigzag(width: Float, passes: Int, pointsPerPass: Int = 12): List<StrokePoint> {
        val out = ArrayList<StrokePoint>()
        var t = 0L
        var y = 0f
        for (pass in 0 until passes) {
            for (i in 0 until pointsPerPass) {
                val frac = i / (pointsPerPass - 1f)
                val x = if (pass % 2 == 0) width * frac else width * (1f - frac)
                out.add(StrokePoint(x, y, 1f, 0f, t))
                t += 8L
                y += 0.25f
            }
        }
        return out
    }

    // ── Smart lasso ──────────────────────────────────────────────────────────

    @Test
    fun `fast closed loop is a smart-lasso candidate`() {
        // r=100: ~628 px path in 400 ms → 1.57 px/ms; closed; winds 360°.
        val loop = arc(200f, 200f, 100f, 360f, durationMs = 400L)
        assertTrue(GestureRecognizer.isSmartLassoCandidate(loop, closurePx))
    }

    @Test
    fun `slow loop fails the velocity gate`() {
        // Same geometry at 2000 ms → 0.31 px/ms: careful handwriting, not a gesture.
        val loop = arc(200f, 200f, 100f, 360f, durationMs = 2000L)
        assertFalse(GestureRecognizer.isSmartLassoCandidate(loop, closurePx))
    }

    @Test
    fun `open arc fails closure and winding`() {
        // 200° of a circle: ends ~197 px apart (> 50) and winds < 270°.
        val open = arc(200f, 200f, 100f, 200f, durationMs = 300L)
        assertFalse(GestureRecognizer.isSmartLassoCandidate(open, closurePx))
    }

    // NOTE on the winding gate: almost any genuinely closed path encloses its own
    // centroid and winds ≥360° (even a flat pen retrace — a hairpin is topologically a
    // very thin loop). What the gate rejects is oscillation without circulation — see
    // the zigzag test below, which passes closure AND velocity and fails only on
    // winding. Loop-shaped false positives over blank paper are instead absorbed by
    // the empty-hit-test fallthrough in the view wiring.

    @Test
    fun `overwound loop qualifies in either direction`() {
        // Two full turns, counter-clockwise (negative sweep) — |winding| counts.
        val loop = arc(200f, 200f, 80f, -720f, durationMs = 600L, steps = 96)
        assertTrue(GestureRecognizer.isSmartLassoCandidate(loop, closurePx))
    }

    @Test
    fun `fast straight line is not a smart-lasso candidate`() {
        val line = (0..20).map { StrokePoint(it * 20f, 100f, 1f, 0f, it * 10L) }
        assertFalse(GestureRecognizer.isSmartLassoCandidate(line, closurePx))
    }

    @Test
    fun `zigzag fails the winding gate`() {
        // The isolated winding-gate case: 4 fast passes end ~12 px from the start
        // (closure passes, velocity passes) but sweep back and forth across the
        // centroid — the angular deltas cancel instead of accumulating.
        assertFalse(GestureRecognizer.isSmartLassoCandidate(zigzag(150f, 4), closurePx))
    }

    @Test
    fun `zero-duration gesture is rejected`() {
        val loop = arc(200f, 200f, 100f, 360f, durationMs = 400L)
            .map { it.copy(timeMillis = 0L) }
        assertFalse(GestureRecognizer.isSmartLassoCandidate(loop, closurePx))
    }

    // ── Scribble ─────────────────────────────────────────────────────────────

    @Test
    fun `dense zigzag is a scribble candidate`() {
        // 4 passes of 150 px: diagonal ~150, path ~600 → density 4; 3 reversals.
        assertTrue(GestureRecognizer.isScribbleCandidate(zigzag(150f, 4), minDiagonalPx))
    }

    @Test
    fun `straight line fails the density gate`() {
        val line = (0..30).map { StrokePoint(it * 10f, 50f, 1f, 0f, it * 8L) }
        assertFalse(GestureRecognizer.isScribbleCandidate(line, minDiagonalPx))
    }

    @Test
    fun `tiny zigzag fails the diagonal gate`() {
        // Dense and reversing, but only 20 px across — jitter, not a deletion.
        assertFalse(GestureRecognizer.isScribbleCandidate(zigzag(20f, 6), minDiagonalPx))
    }

    @Test
    fun `single back-and-forth fails the reversal gate`() {
        // 2 passes = 1 reversal; density 2*150/150 ≈ 2 also fails — use 3 half-passes
        // over a short width to isolate reversals: width 60, 3 passes → path 180,
        // diagonal ~60 → density 3.0, reversals 2 → qualifies; 2 passes → 1 reversal.
        assertFalse(GestureRecognizer.isScribbleCandidate(zigzag(60f, 2), minDiagonalPx))
        assertTrue(GestureRecognizer.isScribbleCandidate(zigzag(60f, 4), minDiagonalPx))
    }

    @Test
    fun `closed loop is not a scribble`() {
        // Direction turns gradually — no negative dot products, zero reversals.
        val loop = arc(200f, 200f, 100f, 540f, durationMs = 500L, steps = 72)
        assertFalse(GestureRecognizer.isScribbleCandidate(loop, minDiagonalPx))
    }

    // ── The recognizer overlap (why the view screens scribble-shape first) ───

    @Test
    fun `circular scrubbing passes BOTH gate sets`() {
        // A "spiky coil" — one fast turn with strong radial oscillation, i.e. the
        // circular scrubbing motion people naturally erase with: it closes, winds
        // 360°, AND is dense with many reversals. This ambiguity is real hardware
        // behavior (Nomad scribbles were selecting instead of erasing), and it is why
        // CanvasPaperView classifies scribble-shape FIRST and never treats a
        // scribble-shaped stroke as a smart lasso. Pinned here so a threshold tweak
        // that silently resolves the overlap (and would make that screen look
        // removable) fails a test instead.
        val steps = 120
        val points = (0..steps).map { i ->
            val t = i * 2.0 * Math.PI / steps
            val r = 80f + 35f * sin(18 * t).toFloat()
            StrokePoint(
                x = 200f + r * cos(t).toFloat(),
                y = 200f + r * sin(t).toFloat(),
                pressure = 1f,
                tilt = 0f,
                timeMillis = i * 4L,
            )
        }
        assertTrue(GestureRecognizer.isSmartLassoCandidate(points, closurePx))
        assertTrue(GestureRecognizer.isScribbleCandidate(points, minDiagonalPx))
    }

    @Test
    fun `sub-2px jitter reversals are noise-filtered away`() {
        // Forward 2.5 px, back 1.9 px, repeated: dense (density ≈ 7) and full of raw
        // reversals, but every backward step is under the 2 px filter — the kept path
        // is monotone forward, so it must NOT read as a scribble.
        val points = ArrayList<StrokePoint>()
        var x = 0f
        var t = 0L
        points.add(StrokePoint(x, 0f, 1f, 0f, t))
        repeat(140) {
            x += 2.5f
            points.add(StrokePoint(x, 0f, 1f, 0f, ++t))
            x -= 1.9f
            points.add(StrokePoint(x, 0f, 1f, 0f, ++t))
        }
        assertFalse(GestureRecognizer.isScribbleCandidate(points, minDiagonalPx))
    }
}
