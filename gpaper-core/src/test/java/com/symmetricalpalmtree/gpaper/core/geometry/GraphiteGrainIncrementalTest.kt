package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The incremental sweep, pinned against the whole (Phase 28).
 *
 * [GraphiteGrain.Sweep] exists because asking `of(…, prefix = true)` once per MotionEvent
 * re-decides every station of the stroke so far, every time: a 1252-sample stroke drawn
 * slowly on a Nomad spent 4352 ms of the UI thread in it, 3.5 ms an event and rising with
 * the length, and the hand feels that as ink lagging the nib. The sweep resumes instead of
 * restarting — and the whole reason it is *allowed* to is that nothing in the file looks
 * ahead, so a station decided now is the station the finished stroke will have.
 *
 * Which makes the property below the one that matters: **the flecks laid by any sequence of
 * [GraphiteGrain.Sweep.extend] calls are exactly the flecks `of(…, prefix = true)` lays on
 * the finished list — element for element, in the same order, whatever sizes the points
 * arrived in.** If that ever stops holding, the live preview on a Supernote panel and the
 * mark that bakes are two different marks, and the panel has no frame to repaint with.
 */
class GraphiteGrainIncrementalTest {

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    /** A plain straight line, 3 px a sample. */
    private fun straight(n: Int): List<StrokePoint> =
        (0 until n).map { StrokePoint(x = 80f + it * 3f, y = 420f, pressure = 0.65f) }

    /** A curve with the pen rolling over as it goes — every filter in the file gets exercised. */
    private fun curved(n: Int): List<StrokePoint> = (0 until n).map {
        val t = it * 0.045f
        StrokePoint(
            x = 400f + 260f * sin(t),
            y = 500f + 180f * (1f - cos(t)),
            pressure = 0.35f + 0.5f * sin(t * 1.7f) * sin(t * 1.7f),
            tilt = deg(20f + 45f * t),
        )
    }

    /** A pen still arriving: it lands, the hand settles through a small excursion, and only
     *  then does the stroke set off — the case the landing trim exists for. */
    private fun slowStart(n: Int): List<StrokePoint> {
        val pts = ArrayList<StrokePoint>()
        for (i in 0 until 12) {
            val t = i / 11f
            pts.add(
                StrokePoint(
                    x = 300f + 9f * sin(t * 3.1f),
                    y = 400f + 5f * cos(t * 3.1f),
                    pressure = 0.5f,
                    tilt = deg(64f),
                )
            )
        }
        for (i in 1 until n) {
            pts.add(StrokePoint(300f + i * 2.5f, 400f + i * 0.4f, 0.7f, deg(64f)))
        }
        return pts
    }

    /**
     * A hand that pauses. A digitizer keeps reporting while the pen stands still, so a real
     * stroke is full of zero-length steps — and a resumable loop is exactly the kind of thing
     * that miscounts them, because a segment it skips must not also be a segment it forgets.
     */
    private fun stalling(n: Int): List<StrokePoint> {
        val pts = ArrayList<StrokePoint>()
        var x = 120f
        for (i in 0 until n) {
            pts.add(StrokePoint(x, 300f + 0.6f * i, 0.55f, deg(30f)))
            // Every fourth sample the pen has not moved at all; every seventh it stands for
            // three in a row.
            if (i % 4 != 0) x += 2.7f
            if (i % 7 == 0) {
                pts.add(StrokePoint(x, 300f + 0.6f * i, 0.55f, deg(30f)))
                pts.add(StrokePoint(x, 300f + 0.6f * i, 0.55f, deg(30f)))
            }
        }
        return pts
    }

    /** Pressure and tilt both moving under the hand, which is what the two lean filters and
     *  the darkness ramp are for — they carry the most state across a resume. */
    private fun kneaded(n: Int): List<StrokePoint> = (0 until n).map {
        val t = it / 9f
        StrokePoint(
            x = 200f + it * 3.4f,
            y = 600f + 40f * sin(t * 0.5f),
            pressure = (0.15f + 0.8f * (0.5f + 0.5f * sin(t * 1.3f))).coerceIn(0f, 1f),
            tilt = deg(12f + 60f * (0.5f + 0.5f * sin(t * 0.37f))),
        )
    }

    /** Every fleck an incremental sweep laid, flattened in the order it laid them. */
    private class Laid {
        val xy = ArrayList<Float>()
        val level = ArrayList<Int>()
        fun add(g: GraphiteGrain.Grain) {
            for (i in 0 until g.count) {
                xy.add(g.xy[i * 2])
                xy.add(g.xy[i * 2 + 1])
                level.add(g.level[i])
            }
        }
    }

    /**
     * Run [points] through a sweep in the chunks [cuts] describes (each entry a point count
     * to have arrived by that call, ending at the whole list) and assert the result is
     * `of(points, …, prefix = true)` exactly.
     */
    private fun assertChunkingAgrees(
        points: List<StrokePoint>,
        width: Float,
        seed: Int,
        cuts: List<Int>,
        what: String,
    ) {
        val whole = GraphiteGrain.of(points, width, seed, prefix = true)
        val sweep = GraphiteGrain.begin(width, seed)
        val laid = Laid()
        var previously = 0
        for (k in cuts) {
            val batch = sweep.extend(points.subList(0, k))
            assertEquals(
                "$what: the sweep's own count must track what it has handed out (at $k points)",
                laid.level.size + batch.count,
                sweep.count,
            )
            laid.add(batch)
            assertTrue(
                "$what: a sweep may only ever add flecks (at $k points)",
                laid.level.size >= previously,
            )
            assertTrue(
                "$what: the sweep laid ${laid.level.size} flecks, past the whole's ${whole.count} " +
                    "(at $k points)",
                laid.level.size <= whole.count,
            )
            previously = laid.level.size
        }
        assertEquals("$what: fleck count", whole.count, laid.level.size)
        for (i in 0 until whole.count) {
            assertEquals("$what: x[$i]", whole.xy[i * 2], laid.xy[i * 2], 0f)
            assertEquals("$what: y[$i]", whole.xy[i * 2 + 1], laid.xy[i * 2 + 1], 0f)
            assertEquals("$what: level[$i]", whole.level[i], laid.level[i])
        }
        assertTrue("$what: the sweep laid nothing at all", whole.count > 0)
    }

    /** Every chunking a stroke can arrive in: sample by sample, in random bites, and whole. */
    private fun assertEveryChunkingAgrees(points: List<StrokePoint>, width: Float, seed: Int, name: String) {
        val n = points.size
        assertChunkingAgrees(points, width, seed, (2..n).toList(), "$name, one sample at a time")
        assertChunkingAgrees(points, width, seed, listOf(n), "$name, all at once")
        val rng = Random(name.hashCode())
        repeat(6) { round ->
            val cuts = ArrayList<Int>()
            var at = 0
            while (at < n) {
                at = (at + 1 + rng.nextInt(17)).coerceAtMost(n)
                cuts.add(at)
            }
            assertChunkingAgrees(points, width, seed, cuts, "$name, random bites #$round")
        }
        // And the same stroke fed in two halves, which is the shortest resume there is.
        assertChunkingAgrees(points, width, seed, listOf(n / 2, n), "$name, two halves")
    }

    @Test
    fun `a straight stroke sweeps the same in any chunking`() {
        assertEveryChunkingAgrees(straight(90), 6f, "straight".hashCode(), "straight")
    }

    @Test
    fun `a curved, rolling stroke sweeps the same in any chunking`() {
        assertEveryChunkingAgrees(curved(120), 14f, "curved".hashCode(), "curved")
    }

    @Test
    fun `a stroke with a landing excursion sweeps the same in any chunking`() {
        assertEveryChunkingAgrees(slowStart(120), 12f, "slow-start".hashCode(), "slow start")
    }

    @Test
    fun `a stroke full of zero-length steps sweeps the same in any chunking`() {
        assertEveryChunkingAgrees(stalling(90), 8f, "stalling".hashCode(), "stalling")
    }

    @Test
    fun `a stroke with varying pressure and tilt sweeps the same in any chunking`() {
        assertEveryChunkingAgrees(kneaded(140), 20f, "kneaded".hashCode(), "kneaded")
    }

    @Test
    fun `a hairline sweeps the same as a broad lead does`() {
        assertEveryChunkingAgrees(curved(80), 1.2f, "hairline".hashCode(), "hairline")
        assertEveryChunkingAgrees(straight(70), 96f, "wide".hashCode(), "widest lead")
    }

    @Test
    fun `what a sweep lays is an ordered prefix of the committed mark`() {
        // Which is the property the panel actually rests on: the flecks previewed are the
        // first N the bake will lay, so pen-up moves none of them. The whole's end cap is
        // the remainder, and it is the remainder by construction.
        val points = curved(120)
        val whole = GraphiteGrain.of(points, 14f, 4242)
        val sweep = GraphiteGrain.begin(14f, 4242)
        val laid = Laid()
        for (k in 2..points.size) laid.add(sweep.extend(points.subList(0, k)))
        assertTrue("the preview should stop short of the whole's end cap", laid.level.size < whole.count)
        for (i in 0 until laid.level.size) {
            assertEquals("x[$i]", whole.xy[i * 2], laid.xy[i * 2], 0f)
            assertEquals("y[$i]", whole.xy[i * 2 + 1], laid.xy[i * 2 + 1], 0f)
            assertEquals("level[$i]", whole.level[i], laid.level[i])
        }
    }

    @Test
    fun `extending by nothing lays nothing`() {
        val points = curved(60)
        val sweep = GraphiteGrain.begin(10f, 7)
        val first = sweep.extend(points)
        assertTrue("the stroke should be well past the landing window", first.count > 0)
        val before = sweep.count
        repeat(5) {
            assertEquals("the same points again are not new points", 0, sweep.extend(points).count)
        }
        assertEquals(before, sweep.count)
        // And the same again for a sweep that has not even begun.
        val young = GraphiteGrain.begin(10f, 7)
        assertEquals(0, young.extend(points.subList(0, 3)).count)
        assertEquals(0, young.extend(points.subList(0, 3)).count)
        assertEquals(0, young.count)
    }

    @Test
    fun `a sweep lays nothing until the landing window has been decided`() {
        // Same rule as prefix mode's, because it is the same code: below 2 x LANDING_TRIM_PX
        // of settled travel the arrival trim and the filter seeds would be read from a
        // shorter stretch of path than the finished stroke will read them from.
        val points = straight(60)
        val sweep = GraphiteGrain.begin(6f, 7)
        assertEquals(0, sweep.extend(points.subList(0, 2)).count)
        assertEquals(0, sweep.extend(points.subList(0, 8)).count)
        assertTrue(
            "a stroke well past the landing window should be previewing by now",
            sweep.extend(points.subList(0, 40)).count > 0,
        )
    }

    @Test
    fun `a sweep of one point or none lays nothing`() {
        // A tap is a disc of grit, and a disc is not a prefix of a sweep.
        val sweep = GraphiteGrain.begin(6f, 11)
        assertEquals(0, sweep.extend(emptyList()).count)
        assertEquals(0, sweep.extend(listOf(StrokePoint(100f, 100f, 0.6f))).count)
        assertEquals(0, sweep.count)
    }

    @Test
    fun `two sweeps of the same stroke lay the same graphite`() {
        // Nothing in a sweep may reach the hashes: the grain is still a pure function of
        // (points, width, seed), and a second contact with the same id must repeat it.
        val points = kneaded(80)
        val a = Laid()
        val b = Laid()
        val sa = GraphiteGrain.begin(9f, 555)
        val sb = GraphiteGrain.begin(9f, 555)
        for (k in 2..points.size) a.add(sa.extend(points.subList(0, k)))
        b.add(sb.extend(points))
        assertEquals(a.level.size, b.level.size)
        for (i in a.level.indices) {
            assertEquals(a.xy[i * 2], b.xy[i * 2], 0f)
            assertEquals(a.xy[i * 2 + 1], b.xy[i * 2 + 1], 0f)
            assertEquals(a.level[i], b.level[i])
        }
    }
}
