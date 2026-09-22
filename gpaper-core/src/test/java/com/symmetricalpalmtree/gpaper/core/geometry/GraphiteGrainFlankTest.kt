package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flank (Phase 36): what a leaned lead does that a round one cannot.
 *
 * The claim the whole lead rests on is that a pencil laid over does not grow a bigger
 * point — it lies **down**, so the graphite touching the paper is a strip running from the
 * tip towards the barrel, and what that strip leaves behind depends on which way the hand
 * drags it. Everything here is that sentence, checked:
 *
 *  - below the threshold the mark is bit for bit the upright one (the user's decision 1,
 *    and the only thing keeping an ordinary writing grip safe);
 *  - across the lean the band is [GraphiteGrain] `FLANK_EXTENT` lead-widths wide, and it
 *    is **one-sided** — all of it on the barrel's side of the nib;
 *  - along the lean it stays the lead's own width;
 *  - and the band pales as it widens, so a firm shading pass is grey rather than a slab.
 */
class GraphiteGrainFlankTest {

    private val flank = GraphiteGrain.Lead.FLANK
    private val round = GraphiteGrain.Lead.ROUND

    /** The one lead arc 46 offers. */
    private val lead = 4f

    private val nibY = 400f

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    /** A straight mark travelling `+x`, at a fixed grip. `azimuth` 90° leans down the screen. */
    private fun mark(
        leanDeg: Float,
        azimuthDeg: Float,
        pressure: Float = 0.65f,
        n: Int = 120,
    ): List<StrokePoint> = (0 until n).map {
        StrokePoint(
            x = 100f + it * 2.5f,
            y = nibY,
            pressure = pressure,
            tilt = deg(leanDeg),
            azimuth = deg(azimuthDeg),
        )
    }

    private fun assertSameGrain(a: GraphiteGrain.Grain, b: GraphiteGrain.Grain, what: String) {
        assertEquals("$what: fleck count", a.count, b.count)
        for (i in 0 until a.count) {
            assertEquals("$what: x[$i]", a.xy[i * 2], b.xy[i * 2], 0f)
            assertEquals("$what: y[$i]", a.xy[i * 2 + 1], b.xy[i * 2 + 1], 0f)
            assertEquals("$what: level[$i]", a.level[i], b.level[i])
            assertEquals("$what: pale[$i]", a.paleOf(i), b.paleOf(i), 0f)
        }
    }

    /** The middle 98 % of the mark's ink, as `(lowest y, highest y)` — threshold-free, the
     *  way this project settles width arguments. */
    private fun band(g: GraphiteGrain.Grain): Pair<Float, Float> {
        val ys = FloatArray(g.count) { g.xy[it * 2 + 1] }
        ys.sort()
        val lo = ys[(g.count * 0.01f).toInt()]
        val hi = ys[(g.count * 0.99f).toInt().coerceAtMost(g.count - 1)]
        return lo to hi
    }

    @Test
    fun `below the threshold the flank is the round lead held upright, fleck for fleck`() {
        // The decision that makes the flank safe to leave armed: every ordinary grip draws
        // the width the lead was set to. If this ever fails, a writing hand has started to
        // thicken its own handwriting — Phase 22's 10-15x bloom, back by another road.
        val upright = GraphiteGrain.of(mark(0f, 90f), lead, 4242, lead = round)
        assertTrue("the reference mark should lay graphite", upright.count > 0)
        for (leanDeg in floatArrayOf(0f, 8f, 17f, 30f, 39f, 43f, 44.9f)) {
            for (azimuthDeg in floatArrayOf(0f, 47f, 90f, 179f, -120f)) {
                assertSameGrain(
                    upright,
                    GraphiteGrain.of(mark(leanDeg, azimuthDeg), lead, 4242, lead = flank),
                    "flank at $leanDeg deg / azimuth $azimuthDeg deg",
                )
            }
        }
    }

    @Test
    fun `the bloom is nothing below 45 degrees, everything above 54, and monotone between`() {
        assertEquals(0f, GraphiteGrain.flankBloom(deg(0f)), 0f)
        assertEquals(0f, GraphiteGrain.flankBloom(deg(45f)), 0f)
        assertEquals(1f, GraphiteGrain.flankBloom(deg(54f)), 0f)
        assertEquals(1f, GraphiteGrain.flankBloom(deg(90f)), 0f)
        // A smoothstep, so the two ends are flat: the flank arrives rather than switching on,
        // and a hand hovering at the boundary does not see the mark flicker.
        assertTrue(GraphiteGrain.flankBloom(deg(45.3f)) < 0.01f)
        assertTrue(GraphiteGrain.flankBloom(deg(53.7f)) > 0.99f)
        assertEquals(0.5f, GraphiteGrain.flankBloom(deg(49.5f)), 0.01f)
        var previous = -1f
        var d = 40f
        while (d <= 60f) {
            val b = GraphiteGrain.flankBloom(deg(d))
            assertTrue("bloom went backwards at $d deg", b >= previous)
            previous = b
            d += 0.1f
        }
    }

    @Test
    fun `a shading sweep across the lean lays a band twenty lead-widths wide`() {
        val g = GraphiteGrain.of(mark(60f, 90f), lead, 77, lead = flank)
        val (lo, hi) = band(g)
        val extent = hi - lo
        // FLANK_EXTENT is 20; the measured 98 % extent is 19.8 lead-widths (offline render,
        // 2026-09-19). Generous either side, because what is pinned is the decision — "a
        // 4 px lead shades an 80 px band" — and not the falloff's last pixel.
        assertTrue("band was $extent px, expected about ${20 * lead}", extent > 15f * lead)
        assertTrue("band was $extent px, expected about ${20 * lead}", extent < 23f * lead)
    }

    @Test
    fun `the band is one-sided — it runs from the nib towards the barrel`() {
        // The asymmetry is the physics: the strip lies between the tip and the barrel, so
        // the mark grows on one side of the path only. A symmetric widening would put half
        // the band where there is no lead at all.
        val down = GraphiteGrain.of(mark(60f, 90f), lead, 77, lead = flank)
        val (lo, hi) = band(down)
        assertTrue("the band should start at the nib, not above it (lo=$lo)", lo > nibY - 2f * lead)
        assertTrue("the band should reach well past the nib (hi=$hi)", hi > nibY + 12f * lead)
        // And the other way up, the mirror: lean up the screen and the band is above.
        val up = GraphiteGrain.of(mark(60f, -90f), lead, 77, lead = flank)
        val (upLo, upHi) = band(up)
        assertTrue("the mirrored band should end at the nib (hi=$upHi)", upHi < nibY + 2f * lead)
        assertTrue("the mirrored band should reach well above the nib (lo=$upLo)", upLo < nibY - 12f * lead)
    }

    @Test
    fun `a stroke dragged along its own lean stays the width of the lead`() {
        // The strip retraces itself, so there is nothing to spread — and because the
        // paleness follows the band rather than the lean, it does not go pale either. A
        // deliberate 60 degree line drawn down its own lean is the lead's own line.
        val along = GraphiteGrain.of(mark(60f, 0f), lead, 88, lead = flank)
        val (lo, hi) = band(along)
        assertTrue("a stroke along the lean widened to ${hi - lo} px", hi - lo < 2.5f * lead)
        val upright = GraphiteGrain.of(mark(0f, 0f), lead, 88, lead = flank)
        val ratio = along.count.toFloat() / upright.count
        assertTrue(
            "a stroke along the lean laid $ratio x the upright mark's graphite",
            ratio > 0.75f && ratio < 1.35f,
        )
    }

    @Test
    fun `the flank pales as it widens, and stays grey however hard it is pressed`() {
        // Ink per unit of paper, which is what "grey" means here. Since Phase 38 the flank
        // fills its sites at the tip's density and carries the tone in each fleck's
        // paleness, so a fleck counts for its ink and not for one.
        fun ink(g: GraphiteGrain.Grain): Float {
            var t = 0f
            for (i in 0 until g.count) t += g.paleOf(i)
            return t
        }
        fun perArea(g: GraphiteGrain.Grain): Float {
            val (lo, hi) = band(g)
            return ink(g) / ((hi - lo + 1f) * 300f)
        }
        val uprightHard = GraphiteGrain.of(mark(0f, 90f, pressure = 1f), lead, 5, lead = flank)
        val flankHard = GraphiteGrain.of(mark(60f, 90f, pressure = 1f), lead, 5, lead = flank)
        assertTrue(
            "a hard-pressed flank should deposit less per unit of paper than a hard-pressed point",
            perArea(flankHard) < 0.65f * perArea(uprightHard),
        )
        // And it is not merely spread thinner: far more graphite goes down in total, because
        // far more lead is on the paper. That is the half of the argument [TILT_LIGHTEN]'s
        // KDoc makes and the reason the figure is not 1/width.
        //
        // **1.8x, re-fitted from 3x at the second walk** (2026-09-19) — and the number moves
        // for the third time for the same reason each time: the claim is about how much lead
        // is on the paper, and the *bound* is about where on `catches` the two marks work,
        // which is what keeps changing under it. 4.8x, then 3.82x when FLANK_TOOTH_WEIGHT
        // smoothed the curve, and 2.06x now that the flank reads the sheet proportionally
        // ([FLANK_TOOTH_DEPTH]) and FLANK_LIGHTEN was re-fitted 0.61 -> 0.84 against the
        // hand's **own** shading strokes rather than a synthetic sweep at a pressure nobody
        // shades at. The light end moved the other way, as it has every time — 3.61x at a
        // light touch, against 3.03x before — because what has gone is the stencil that used
        // to eat a light mark, and a light mark is what shading is.
        assertTrue(
            "a shading sweep should lay much more graphite in total than a hairline",
            ink(flankHard) > 1.8f * ink(uprightHard),
        )
        // And the Phase 38 claim itself: the band is **filled**, not sparse — its flecks per
        // unit of paper are the pressed point's, near enough, with the grey in their paleness.
        val (lo, hi) = band(flankHard)
        val (ulo, uhi) = band(uprightHard)
        val flankSites = flankHard.count / ((hi - lo + 1f) * 300f)
        val uprightSites = uprightHard.count / ((uhi - ulo + 1f) * 300f)
        assertTrue(
            "a flank's sites should be filled as densely as the point's (flank $flankSites, point $uprightSites)",
            flankSites > 0.7f * uprightSites,
        )
    }

    @Test
    fun `the flank is deterministic, and the azimuth is part of the mark`() {
        val a = GraphiteGrain.of(mark(60f, 90f), lead, 31, lead = flank)
        val b = GraphiteGrain.of(mark(60f, 90f), lead, 31, lead = flank)
        assertSameGrain(a, b, "the same stroke twice")
        val turned = GraphiteGrain.of(mark(60f, 45f), lead, 31, lead = flank)
        assertTrue("turning the pen should change the mark", turned.count != a.count)
    }

    @Test
    fun `a dab with the pen laid over is a streak along the lean, not a dot`() {
        // A tap is the one mark with no travel to hang a direction on, so the strip prints
        // whole. Upright it is still the disc of grit it always was.
        val dot = GraphiteGrain.of(
            listOf(StrokePoint(200f, nibY, 0.7f, deg(5f), azimuth = deg(90f))), lead, 9, lead = flank,
        )
        val streak = GraphiteGrain.of(
            listOf(StrokePoint(200f, nibY, 0.7f, deg(60f), azimuth = deg(90f))), lead, 9, lead = flank,
        )
        assertTrue("an upright dab should leave grit", dot.count > 0)
        val (dotLo, dotHi) = band(dot)
        val (streakLo, streakHi) = band(streak)
        assertTrue("the upright dab spread ${dotHi - dotLo} px", dotHi - dotLo < 2f * lead)
        assertTrue("the laid-over dab only spread ${streakHi - streakLo} px", streakHi - streakLo > 10f * lead)
        assertTrue("the streak should start at the nib", streakLo > nibY - 2f * lead)
    }

    @Test
    fun `every fleck lands inside the reach a dirty rect is padded by`() {
        // The one that keeps a shading sweep from being clipped out of its own bake: a
        // raster page composites and announces only within the dirty rects, which are the
        // path's bounds padded by [GraphiteGrain.reach]. A fleck outside it is a fleck the
        // page never receives and the host's undo never sees.
        for (leanDeg in floatArrayOf(0f, 30f, 47f, 50f, 60f, 85f)) {
            for (azimuthDeg in floatArrayOf(0f, 47f, 90f, 200f)) {
                val points = mark(leanDeg, azimuthDeg, pressure = 1f)
                val g = GraphiteGrain.of(points, lead, 6, lead = flank)
                val reach = GraphiteGrain.reach(lead, deg(leanDeg), flank)
                val minX = points.minOf { it.x } - reach
                val maxX = points.maxOf { it.x } + reach
                for (i in 0 until g.count) {
                    val x = g.xy[i * 2]
                    val y = g.xy[i * 2 + 1]
                    assertTrue(
                        "$leanDeg deg / $azimuthDeg deg: fleck ($x, $y) outside the reach $reach",
                        x >= minX && x <= maxX &&
                            y >= nibY - reach && y <= nibY + reach,
                    )
                }
            }
        }
        // And the round lead's own answer is its leaned half-width plus the same fleck and
        // jitter slack — a little over half the width the default padding uses, which is
        // why that padding has always been generous enough without anyone checking.
        val upright = GraphiteGrain.reach(lead, 0f, round)
        assertTrue("upright reach was $upright on a $lead px lead", upright > lead / 2f)
        assertTrue("upright reach was $upright on a $lead px lead", upright < lead)
        assertTrue(GraphiteGrain.reach(lead, deg(75f), round) > 4f * lead)
    }

    @Test
    fun `an absurd flank stroke stops at the fleck cap instead of stalling the frame`() {
        // MAX_FLECKS is the one hard refusal in this file, and the flank is the first lead
        // that can reach it with an ordinary lead size: a hundred lanes a station rather
        // than six. A host handing us a page-long shading sweep must degrade, not stall.
        val long = (0 until 4000).map {
            StrokePoint(10f + it * 2f, nibY, 1f, deg(70f), azimuth = deg(90f))
        }
        val g = GraphiteGrain.of(long, 96f, 3, lead = flank)
        // The cap is tested once a cross-section has landed, so a mark overshoots it by at
        // most one of them — and a flank's cross-section is the widest this file lays. Same
        // allowance the round lead's own cap test takes, for the same reason.
        assertTrue("capped, got ${g.count}", g.count in 1..1_050_000)
        // And an incremental sweep of the same stroke stops in the same place.
        val sweep = GraphiteGrain.begin(96f, 3, lead = flank)
        var laid = 0
        for (k in 2..long.size step 37) laid += sweep.extend(long.subList(0, k)).count
        laid += sweep.finish(long).count
        assertEquals(g.count, laid)
    }
}
