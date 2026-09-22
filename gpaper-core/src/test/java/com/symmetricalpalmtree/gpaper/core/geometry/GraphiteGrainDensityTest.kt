package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The density dial (Phase 28, the second Nomad walk).
 *
 * A shade on the Supernote panel is a *density of black flecks*, not a grey — the panel's
 * 16-grey waveform passes through black on its way to a grey, so a pale fleck trails the nib
 * and a black one lands. `GraphiteGrain.of(…, density = d)` therefore has to thin a mark
 * without moving it, and the three properties below are what "without moving it" means:
 *
 *  1. **`density = 1` is today's mark, exactly** — every engine but one passes it, so a dial
 *     that shifted the full mark by a hair would have changed every pencil stroke ever drawn
 *     to add a Supernote feature. (`GraphiteGrainPinTest` is the byte-identity half of this.)
 *  2. **`d < 1` is an ordered subset of `d = 1`** — the same specks, in the same places, at
 *     the same darknesses, with some left out. It holds because `catches` weighs a site's own
 *     fixed coin toss against the coverage, and density scales only the coverage.
 *  3. **A [GraphiteGrain.Sweep] at `d` agrees with `of(…, prefix = true, density = d)`**, in
 *     every chunking — the incremental invariant, which the preview rests on, must survive
 *     the dial.
 */
class GraphiteGrainDensityTest {

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    /** The same three strokes the pin and incremental tests use — straight, rolling, and one
     *  with a landing excursion — so a density failure can be read against them. */
    private fun straight(n: Int): List<StrokePoint> =
        (0 until n).map { StrokePoint(x = 80f + it * 3f, y = 420f, pressure = 0.65f) }

    private fun curved(n: Int): List<StrokePoint> = (0 until n).map {
        val t = it * 0.045f
        StrokePoint(
            x = 400f + 260f * sin(t),
            y = 500f + 180f * (1f - cos(t)),
            pressure = 0.35f + 0.5f * sin(t * 1.7f) * sin(t * 1.7f),
            tilt = deg(20f + 45f * t),
        )
    }

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

    /** Every stroke the file is exercised with here, with the lead each is drawn at. */
    private val cases: List<Triple<String, List<StrokePoint>, Float>> = listOf(
        Triple("straight", straight(90), 6f),
        Triple("curved", curved(120), 14f),
        Triple("slow start", slowStart(120), 12f),
        Triple("hairline", curved(80), 1.2f),
        Triple("widest lead", straight(70), 96f),
    )

    private fun assertSameGrain(what: String, a: GraphiteGrain.Grain, b: GraphiteGrain.Grain) {
        assertEquals("$what: fleck count", a.count, b.count)
        for (i in 0 until a.count) {
            assertEquals("$what: x[$i]", a.xy[i * 2], b.xy[i * 2], 0f)
            assertEquals("$what: y[$i]", a.xy[i * 2 + 1], b.xy[i * 2 + 1], 0f)
            assertEquals("$what: level[$i]", a.level[i], b.level[i])
            assertEquals("$what: pale[$i]", a.paleOf(i), b.paleOf(i), 0f)
        }
    }

    @Test
    fun `density 1 is the mark the file already made`() {
        for ((name, points, width) in cases) {
            val seed = name.hashCode()
            assertSameGrain(
                "$name, whole",
                GraphiteGrain.of(points, width, seed),
                GraphiteGrain.of(points, width, seed, density = 1f),
            )
            assertSameGrain(
                "$name, prefix",
                GraphiteGrain.of(points, width, seed, prefix = true),
                GraphiteGrain.of(points, width, seed, prefix = true, density = 1f),
            )
        }
        // And the tap — one point, a disc of grit, the one path that never reaches a station.
        val dot = listOf(StrokePoint(100f, 100f, 0.8f, deg(30f)))
        assertSameGrain(
            "tap",
            GraphiteGrain.of(dot, 18f, 99),
            GraphiteGrain.of(dot, 18f, 99, density = 1f),
        )
    }

    @Test
    fun `a thinned mark is an ordered subset of the full one`() {
        for ((name, points, width) in cases) {
            val seed = name.hashCode()
            val full = GraphiteGrain.of(points, width, seed)
            for (d in listOf(0.85f, 0.5f, 0.24f, 0.15f, 0.05f)) {
                val thin = GraphiteGrain.of(points, width, seed, density = d)
                assertTrue(
                    "$name at $d: a thinned mark cannot have more flecks than the full one " +
                        "(${thin.count} > ${full.count})",
                    thin.count <= full.count,
                )
                // Walk both in order: every fleck of the thin mark must be the next one in
                // the full mark that matches it exactly — position and darkness — and the
                // matches must arrive in order.
                var j = 0
                for (i in 0 until thin.count) {
                    val x = thin.xy[i * 2]
                    val y = thin.xy[i * 2 + 1]
                    val lvl = thin.level[i]
                    var found = false
                    while (j < full.count) {
                        val same = full.xy[j * 2].toRawBits() == x.toRawBits() &&
                            full.xy[j * 2 + 1].toRawBits() == y.toRawBits() &&
                            full.level[j] == lvl
                        j++
                        if (same) { found = true; break }
                    }
                    assertTrue(
                        "$name at $d: fleck $i ($x, $y, level $lvl) is not in the full mark, " +
                            "or is out of order",
                        found,
                    )
                }
            }
        }
    }

    @Test
    fun `thinning is monotone in the dial`() {
        // Not only a subset of the full mark: a subset of every denser one. Which is what a
        // shade ladder needs — picking the next lead up may only add graphite.
        val (name, points, width) = cases[1]
        val seed = name.hashCode()
        val ladder = listOf(1f, 0.8f, 0.63f, 0.51f, 0.32f, 0.15f)
        var previous = GraphiteGrain.of(points, width, seed, density = ladder[0]).count
        for (d in ladder.drop(1)) {
            val count = GraphiteGrain.of(points, width, seed, density = d).count
            assertTrue("$name: density $d laid $count, past the denser mark's $previous", count <= previous)
            previous = count
        }
    }

    @Test
    fun `the ladder is a ladder — each rung is visibly sparser`() {
        val (name, points, width) = cases[1]
        val seed = name.hashCode()
        val full = GraphiteGrain.of(points, width, seed, density = 1f).count
        val pale = GraphiteGrain.of(points, width, seed, density = 0.15f).count
        assertTrue("$name: the palest lead laid $pale of the full mark's $full", pale < full / 2)
        assertTrue("$name: the palest lead should still lay graphite", pale > 0)
    }

    @Test
    fun `density 0 lays nothing`() {
        for ((name, points, width) in cases) {
            val seed = name.hashCode()
            assertEquals("$name, whole", 0, GraphiteGrain.of(points, width, seed, density = 0f).count)
            assertEquals(
                "$name, prefix",
                0,
                GraphiteGrain.of(points, width, seed, prefix = true, density = 0f).count,
            )
        }
        val dot = listOf(StrokePoint(100f, 100f, 1f))
        assertEquals("tap", 0, GraphiteGrain.of(dot, 20f, 5, density = 0f).count)
        val sweep = GraphiteGrain.begin(14f, 7, 0f)
        assertEquals(0, sweep.extend(curved(120)).count)
        assertEquals(0, sweep.count)
    }

    @Test
    fun `the dial is clamped at both ends`() {
        val (name, points, width) = cases[0]
        val seed = name.hashCode()
        assertSameGrain(
            "over 1 is 1",
            GraphiteGrain.of(points, width, seed, density = 1f),
            GraphiteGrain.of(points, width, seed, density = 4.2f),
        )
        assertEquals("under 0 is 0", 0, GraphiteGrain.of(points, width, seed, density = -3f).count)
        // And the same through a sweep, whose constructor does its own clamping.
        val a = GraphiteGrain.begin(width, seed, 1f).extend(points)
        val b = GraphiteGrain.begin(width, seed, 9f).extend(points)
        assertSameGrain("sweep, over 1 is 1", a, b)
    }

    /**
     * A sweep at [density], fed in the chunks [cuts] describes, must equal
     * `of(points, …, prefix = true, density = density)` on the whole list — element for
     * element. Same invariant `GraphiteGrainIncrementalTest` pins at full density; the dial
     * must not be a way around it.
     */
    private fun assertChunkingAgrees(
        points: List<StrokePoint>,
        width: Float,
        seed: Int,
        density: Float,
        cuts: List<Int>,
        what: String,
    ) {
        val whole = GraphiteGrain.of(points, width, seed, prefix = true, density = density)
        val sweep = GraphiteGrain.begin(width, seed, density)
        val xy = ArrayList<Float>()
        val level = ArrayList<Int>()
        for (k in cuts) {
            val batch = sweep.extend(points.subList(0, k))
            assertEquals(
                "$what: the sweep's own count must track what it has handed out (at $k points)",
                level.size + batch.count,
                sweep.count,
            )
            for (i in 0 until batch.count) {
                xy.add(batch.xy[i * 2])
                xy.add(batch.xy[i * 2 + 1])
                level.add(batch.level[i])
            }
        }
        assertEquals("$what: fleck count", whole.count, level.size)
        for (i in 0 until whole.count) {
            assertEquals("$what: x[$i]", whole.xy[i * 2], xy[i * 2], 0f)
            assertEquals("$what: y[$i]", whole.xy[i * 2 + 1], xy[i * 2 + 1], 0f)
            assertEquals("$what: level[$i]", whole.level[i], level[i])
        }
        assertTrue("$what: the sweep laid nothing at all", whole.count > 0)
    }

    @Test
    fun `a thinned sweep agrees with a thinned prefix in every chunking`() {
        for ((name, points, width) in cases) {
            val seed = name.hashCode()
            val n = points.size
            for (d in listOf(0.8f, 0.51f, 0.15f)) {
                assertChunkingAgrees(points, width, seed, d, (2..n).toList(), "$name at $d, one at a time")
                assertChunkingAgrees(points, width, seed, d, listOf(n), "$name at $d, all at once")
                assertChunkingAgrees(points, width, seed, d, listOf(n / 2, n), "$name at $d, two halves")
                val rng = Random(name.hashCode() * 31 + d.toRawBits())
                repeat(3) { round ->
                    val cuts = ArrayList<Int>()
                    var at = 0
                    while (at < n) {
                        at = (at + 1 + rng.nextInt(17)).coerceAtMost(n)
                        cuts.add(at)
                    }
                    assertChunkingAgrees(points, width, seed, d, cuts, "$name at $d, random bites #$round")
                }
            }
        }
    }

    @Test
    fun `a thinned sweep is an ordered prefix of the thinned committed mark`() {
        // The property the panel rests on, at a density: the flecks previewed are the first N
        // the bake will lay, so pen-up moves none of them.
        val points = curved(120)
        val d = 0.51f
        val whole = GraphiteGrain.of(points, 14f, 4242, density = d)
        val sweep = GraphiteGrain.begin(14f, 4242, d)
        val xy = ArrayList<Float>()
        val level = ArrayList<Int>()
        for (k in 2..points.size) {
            val batch = sweep.extend(points.subList(0, k))
            for (i in 0 until batch.count) {
                xy.add(batch.xy[i * 2])
                xy.add(batch.xy[i * 2 + 1])
                level.add(batch.level[i])
            }
        }
        assertTrue("the preview should stop short of the whole's end cap", level.size < whole.count)
        for (i in 0 until level.size) {
            assertEquals("x[$i]", whole.xy[i * 2], xy[i * 2], 0f)
            assertEquals("y[$i]", whole.xy[i * 2 + 1], xy[i * 2 + 1], 0f)
            assertEquals("level[$i]", whole.level[i], level[i])
        }
    }
}
