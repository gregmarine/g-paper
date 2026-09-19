package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The prefix invariant (Phase 28), pinned: **a prefix's flecks are the first N of the whole
 * stroke's.**
 *
 * It is what lets a live preview paint each fleck straight onto a panel once and never move
 * it — there is no frame to repaint on that path, so a fleck laid in the wrong place stays
 * wrong until the mark is baked. The whole stroke's end cap is the one exception, and it is
 * an exception by construction: the dome at the lifting end is the tip of the lead, and the
 * tip has not stopped anywhere while the pen is still down.
 */
class GraphiteGrainPrefixTest {

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

    /**
     * A pen still arriving: it lands, the hand settles through a small excursion, and only
     * then does the stroke set off — the case the landing trim exists for, and the one where
     * a prefix could most easily disagree with the whole about where the mark begins.
     */
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
     * Every prefix of [points] is an ordered prefix of the whole stroke's grain: same
     * coordinates, same darknesses, same order, exactly — never "close enough". The end cap
     * is not compared, because the whole stroke's grain ends with one and a prefix's does
     * not; what is compared is that the prefix's flecks ARE the whole's first `count`.
     */
    private fun assertEveryPrefixHolds(points: List<StrokePoint>, width: Float, seed: Int) {
        val whole = GraphiteGrain.of(points, width, seed)
        var laid = 0
        for (k in 1..points.size) {
            val prefix = GraphiteGrain.of(points.take(k), width, seed, prefix = true)
            assertTrue(
                "prefix of $k points laid ${prefix.count} flecks, more than the whole stroke's " +
                    "${whole.count}",
                prefix.count <= whole.count,
            )
            assertTrue(
                "prefix of $k points went backwards: ${prefix.count} after $laid",
                prefix.count >= laid,
            )
            laid = prefix.count
            for (i in 0 until prefix.count) {
                assertEquals("k=$k x[$i]", whole.xy[i * 2], prefix.xy[i * 2], 0f)
                assertEquals("k=$k y[$i]", whole.xy[i * 2 + 1], prefix.xy[i * 2 + 1], 0f)
                assertEquals("k=$k level[$i]", whole.level[i], prefix.level[i])
            }
        }
        assertTrue("the full-length prefix laid nothing at all", laid > 0)
        assertTrue(
            "the full-length prefix ($laid) should stop short of the whole stroke's end cap " +
                "(${whole.count})",
            laid < whole.count,
        )
    }

    @Test
    fun `every prefix of a straight stroke is an ordered prefix of the whole`() {
        assertEveryPrefixHolds(straight(90), 6f, "straight".hashCode())
    }

    @Test
    fun `every prefix of a curved, rolling stroke is an ordered prefix of the whole`() {
        assertEveryPrefixHolds(curved(120), 14f, "curved".hashCode())
    }

    @Test
    fun `every prefix of a stroke with a landing excursion is an ordered prefix of the whole`() {
        assertEveryPrefixHolds(slowStart(120), 12f, "slow-start".hashCode())
    }

    @Test
    fun `a hairline prefixes as exactly as a broad lead does`() {
        assertEveryPrefixHolds(curved(80), 1.2f, "hairline".hashCode())
    }

    @Test
    fun `nothing is laid until the landing window has been decided`() {
        // Below 2 x LANDING_TRIM_PX of settled travel the arrival trim and the filter seeds
        // would be read from a shorter stretch of path than the finished stroke will read
        // them from — so the honest answer is no graphite yet, not graphite in the wrong place.
        val pts = straight(60)
        assertEquals(0, GraphiteGrain.of(pts.take(2), 6f, 7, prefix = true).count)
        assertEquals(0, GraphiteGrain.of(pts.take(8), 6f, 7, prefix = true).count)
        assertTrue(
            "a stroke well past the landing window should be previewing by now",
            GraphiteGrain.of(pts.take(40), 6f, 7, prefix = true).count > 0,
        )
    }

    @Test
    fun `a single sample previews nothing`() {
        // A tap is a disc of grit, and a disc is not a prefix of a sweep: the contact may yet
        // become a stroke, and the grain it would grow into puts its flecks somewhere else.
        val one = listOf(StrokePoint(100f, 100f, 0.6f))
        assertEquals(0, GraphiteGrain.of(one, 6f, 11, prefix = true).count)
        assertTrue("a committed tap still leaves grit", GraphiteGrain.of(one, 6f, 11).count > 0)
    }

    @Test
    fun `the whole stroke is unaffected by asking for prefixes of it`() {
        // Prefix mode is a different question about the same stroke, never a different stroke.
        val pts = curved(60)
        val before = GraphiteGrain.of(pts, 10f, 99)
        GraphiteGrain.of(pts.take(30), 10f, 99, prefix = true)
        val after = GraphiteGrain.of(pts, 10f, 99)
        assertEquals(before.count, after.count)
        for (i in 0 until before.count) {
            assertEquals(before.xy[i * 2], after.xy[i * 2], 0f)
            assertEquals(before.xy[i * 2 + 1], after.xy[i * 2 + 1], 0f)
            assertEquals(before.level[i], after.level[i])
        }
    }
}
