package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sweep costs, on the JVM, per stroke (Phase 28's measurement, extended to the
 * flank in Phase 36).
 *
 * The number this exists to protect is a **hand's** number, not a benchmark's: on Supernote
 * the live preview runs `Sweep.extend` once per MotionEvent on the UI thread, and the
 * moment that stops fitting inside the input cadence the artist feels the ink lag the nib.
 * Phase 28 measured the round lead at **6 ms for a whole 1252-event stroke** on a JVM
 * (3402 ms before the sweep resumed instead of restarting, which is what the phase was for).
 *
 * The flank is the first lead that can make that question interesting again, and by a
 * larger factor than anything before it: a 4 px round lead lays six lanes a station and a
 * 4 px flank lays a hundred and six. So the whole stroke is measured here — extend by
 * extend, exactly as the panel drives it — and printed, because **a cost is only
 * comparable if it is written down**; the assertion is a loose ceiling whose job is to
 * catch a regression of the quadratic kind, never to pin a machine's speed.
 */
class GraphiteGrainCostTest {

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    /** The worst case the hand can actually produce: a long shading sweep, laid right over,
     *  travelling across its own lean, at 2000 samples — a slow deliberate stroke. */
    private fun flatShading(n: Int): List<StrokePoint> = (0 until n).map {
        val t = it * 0.0016f
        StrokePoint(
            x = 60f + it * 0.7f,
            y = 500f + 120f * t,
            pressure = 0.7f,
            tilt = deg(58f),
            azimuth = deg(92f),
        )
    }

    /**
     * The same sweep with a **hand's wander** in it — the second walk's stroke (Phase 36).
     *
     * The straight version below costs what it costs because every station lays one comb.
     * A turning mark splits its stations so the fan cannot skip a row of tooth
     * (`GraphiteGrain.FAN_SPLIT_MAX`), and the user's own shading passes on the Manta ask
     * for about 2.8x as many — which is the cost that has to be paid on the live path, so
     * it is the cost that gets printed. The wander here is a 0.5 px sine at 24 px, which
     * puts the smoothed direction's turn in the same band the probe measured.
     */
    private fun wanderingShading(n: Int): List<StrokePoint> = (0 until n).map {
        val t = it * 0.0016f
        val x = 60f + it * 0.7f
        StrokePoint(
            x = x,
            y = 500f + 120f * t + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * x / 24f),
            pressure = 0.7f,
            tilt = deg(58f),
            azimuth = deg(92f),
        )
    }

    /** One stroke driven through a sweep the way a live contact drives it: the whole stroke
     *  so far, once per event. Returns nanoseconds spent inside the grain. */
    private fun sweepNanos(points: List<StrokePoint>, width: Float, lead: GraphiteGrain.Lead): Pair<Long, Int> {
        val sweep = GraphiteGrain.begin(width, points.size, lead = lead)
        var laid = 0
        val t0 = System.nanoTime()
        for (k in 2..points.size) laid += sweep.extend(points.subList(0, k)).count
        laid += sweep.finish(points).count
        return (System.nanoTime() - t0) to laid
    }

    @Test
    fun `a two-thousand event flank sweep stays inside the input cadence`() {
        val points = flatShading(2000)
        // Warm the JIT: an interpreted first pass says nothing about a path that runs on
        // every pen sample of every session.
        repeat(3) {
            sweepNanos(points, 4f, GraphiteGrain.Lead.FLANK)
            sweepNanos(points, 4f, GraphiteGrain.Lead.ROUND)
        }
        val (flankNs, flankFlecks) = sweepNanos(points, 4f, GraphiteGrain.Lead.FLANK)
        val (roundNs, roundFlecks) = sweepNanos(points, 4f, GraphiteGrain.Lead.ROUND)
        val flankMs = flankNs / 1e6
        val roundMs = roundNs / 1e6
        println(
            "\nGraphiteGrain.Sweep over a 2000-event stroke, 4 px lead (JVM):\n" +
                "  flank, 58 deg across the lean : %7.2f ms  (%.4f ms/event, %d flecks)\n".format(
                    flankMs, flankMs / points.size, flankFlecks,
                ) +
                "  round, same stroke            : %7.2f ms  (%.4f ms/event, %d flecks)\n".format(
                    roundMs, roundMs / points.size, roundFlecks,
                ) +
                "  Phase 28's reference          :    6.00 ms over 1252 events (round, 0.1.41)\n",
        )
        repeat(3) { sweepNanos(wanderingShading(2000), 4f, GraphiteGrain.Lead.FLANK) }
        val (fanNs, fanFlecks) = sweepNanos(wanderingShading(2000), 4f, GraphiteGrain.Lead.FLANK)
        val fanMs = fanNs / 1e6
        println(
            "  flank, the same stroke wandering: %7.2f ms  (%.4f ms/event, %d flecks)\n".format(
                fanMs, fanMs / points.size, fanFlecks,
            ),
        )
        assertTrue("the flank laid nothing", flankFlecks > 0)
        assertTrue("the wandering flank laid nothing", fanFlecks > 0)
        assertTrue("a 2000-event wandering flank sweep took $fanMs ms", fanMs < 4000.0)
        // Loose by design — a shared CI machine is slower than a laptop and both are a poor
        // model of an RK3566. What this catches is the shape of the cost going wrong
        // (a re-derivation creeping back in), which is a factor of hundreds, not of two.
        assertTrue("a 2000-event flank sweep took $flankMs ms", flankMs < 2000.0)
    }

    @Test
    fun `the sweep is linear in the stroke, not quadratic`() {
        // The property Phase 28 bought and the one thing a slow machine cannot disguise:
        // doubling the samples must roughly double the work. Asking
        // `of(..., prefix = true)` per event instead made this ratio ~4.
        repeat(3) { sweepNanos(flatShading(1600), 4f, GraphiteGrain.Lead.FLANK) }
        val short = sweepNanos(flatShading(800), 4f, GraphiteGrain.Lead.FLANK).first
        val long = sweepNanos(flatShading(1600), 4f, GraphiteGrain.Lead.FLANK).first
        val ratio = long.toDouble() / short
        println("flank sweep 800 -> 1600 events: %.2f x the time".format(ratio))
        assertTrue("doubling the stroke cost ${ratio}x — that is a quadratic", ratio < 3.0)
    }
}
