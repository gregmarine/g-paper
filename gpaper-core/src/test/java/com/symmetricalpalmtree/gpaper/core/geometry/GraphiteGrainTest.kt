package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pencil's grain, pinned. Everything here is about one promise: a mark, once drawn,
 * looks the same every time anyone draws it again — while the pen is still down, after the
 * bake, after a reload days later, and through the offline rasterizer. A grain that
 * reshuffles is a drawing that changes behind the artist's back.
 */
class GraphiteGrainTest {

    private fun line(
        n: Int,
        pressure: Float = 0.6f,
        x0: Float = 10f,
        y0: Float = 10f,
        step: Float = 4f,
    ): List<StrokePoint> = (0 until n).map {
        StrokePoint(x = x0 + it * step, y = y0 + it * step * 0.25f, pressure = pressure)
    }

    private fun assertSameGrain(a: GraphiteGrain.Grain, b: GraphiteGrain.Grain) {
        assertEquals("fleck count", a.count, b.count)
        for (i in 0 until a.count) {
            assertEquals("x[$i]", a.xy[i * 2], b.xy[i * 2], 0f)
            assertEquals("y[$i]", a.xy[i * 2 + 1], b.xy[i * 2 + 1], 0f)
            assertEquals("level[$i]", a.level[i], b.level[i])
            assertEquals("pale[$i]", a.paleOf(i), b.paleOf(i), 0f)
        }
    }

    @Test
    fun `same stroke re-rendered is fleck-for-fleck identical`() {
        val pts = line(40)
        assertSameGrain(
            GraphiteGrain.of(pts, 6f, "a-stroke-id".hashCode()),
            GraphiteGrain.of(pts, 6f, "a-stroke-id".hashCode()),
        )
    }

    @Test
    fun `a copy of the point list renders the same as the original`() {
        // A host that reloads a page rebuilds its points from storage — different objects,
        // same numbers. The grain must not notice.
        val pts = line(40)
        val reloaded = pts.map { StrokePoint(it.x, it.y, it.pressure, it.tilt, it.timeMillis) }
        assertSameGrain(
            GraphiteGrain.of(pts, 6f, 12345),
            GraphiteGrain.of(reloaded, 6f, 12345),
        )
    }

    @Test
    fun `a different stroke gets different grain`() {
        val pts = line(40)
        val a = GraphiteGrain.of(pts, 6f, "one".hashCode())
        val b = GraphiteGrain.of(pts, 6f, "two".hashCode())
        var same = 0
        val n = minOf(a.count, b.count)
        for (i in 0 until n) if (a.xy[i * 2] == b.xy[i * 2] && a.xy[i * 2 + 1] == b.xy[i * 2 + 1]) same++
        assertTrue("two strokes should not share a texture (matched $same of $n)", same < n / 4)
    }

    @Test
    fun `the grain already laid down does not move as the stroke grows`() {
        // The live preview and the bake are the same stroke, so the flecks behind the pen must not
        // shift when the next sample arrives. Stations are placed at fixed arc length from the
        // first point, which is what makes the prefix stable.
        //
        // The guarantee is about ink *already laid down*, and stops at the pen. The dome that caps
        // the lifting end is not laid down — it is the tip, and it travels with the pen exactly as
        // the real one does, so the comparison ends a lead's width short of it.
        val seed = "growing".hashCode()
        val short = GraphiteGrain.of(line(10), 6f, seed)
        val long = GraphiteGrain.of(line(40), 6f, seed)
        assertTrue("the longer stroke should carry more graphite", long.count > short.count)
        val tipX = line(10).last().x - 6f
        var compared = 0
        for (i in 0 until short.count) {
            if (short.xy[i * 2] > tipX) continue
            assertEquals("x[$i]", short.xy[i * 2], long.xy[i * 2], 0f)
            assertEquals("y[$i]", short.xy[i * 2 + 1], long.xy[i * 2 + 1], 0f)
            assertEquals("level[$i]", short.level[i], long.level[i])
            compared++
        }
        assertTrue("nothing was actually compared", compared > short.count / 2)
    }

    @Test
    fun `pressing harder fills in more of the tooth`() {
        val seed = 99
        val light = GraphiteGrain.of(line(60, pressure = 0.15f), 6f, seed)
        val firm = GraphiteGrain.of(line(60, pressure = 0.9f), 6f, seed)
        assertTrue(
            "firm ${firm.count} should deposit more than light ${light.count}",
            firm.count > light.count * 2,
        )
    }

    @Test
    fun `pressing harder also darkens the flecks that land`() {
        val seed = 99
        fun meanLevel(p: Float): Float {
            val g = GraphiteGrain.of(line(60, pressure = p), 6f, seed)
            var sum = 0
            for (i in 0 until g.count) sum += g.level[i]
            return sum.toFloat() / g.count
        }
        assertTrue("light should sit paler than firm", meanLevel(0.15f) < meanLevel(0.9f))
    }

    @Test
    fun `a broad lead meets more tooth than a fine one`() {
        val pts = line(40)
        val fine = GraphiteGrain.of(pts, 2f, 7)
        val broad = GraphiteGrain.of(pts, 12f, 7)
        assertTrue(
            "broad ${broad.count} should out-deposit fine ${fine.count}",
            broad.count > fine.count * 2,
        )
    }

    @Test
    fun `every fleck lands on the mark`() {
        // Nothing may stray outside the lead's own width plus the jitter that scatters it off the
        // lattice — a pencil does not spray. Lengthwise the mark reaches half a width past each
        // end, and no further: that is the round tip touching down and lifting, and it is bounded
        // by the same disc it is drawn from.
        val width = 8f
        val pts = listOf(
            StrokePoint(100f, 100f, 0.8f),
            StrokePoint(300f, 100f, 0.8f),
        )
        val g = GraphiteGrain.of(pts, width, 4242)
        val slack = GraphiteGrain.TOOTH_PITCH_PX + GraphiteGrain.FLECK_MAX_PX
        val reach = width / 2f + slack
        for (i in 0 until g.count) {
            val x = g.xy[i * 2]
            val y = g.xy[i * 2 + 1]
            assertTrue("x=$x off the near end", x >= 100f - reach)
            assertTrue("x=$x off the far end", x <= 300f + reach)
            assertTrue("y=$y off the mark", abs(y - 100f) <= reach)
        }
    }

    @Test
    fun `a broad stroke does not begin with a hook`() {
        // Seeded from the first pair of samples — the noisiest direction measurement in a stroke —
        // the touch-down dome is thrown backwards along a wrong heading and the tangent filter
        // swings as it converges, curling a comma out of the start. Both errors scale with the
        // half-width, so this is checked on a broad mark: nothing at the start may sit off to the
        // side of the path the pen actually took.
        val jitter = floatArrayOf(0.4f, -0.35f, 0.3f, -0.45f, 0.25f, -0.3f, 0.35f, -0.25f)
        val pts = (0..200).map {
            StrokePoint(
                x = 200f + it * 3f + jitter[it % jitter.size],
                y = 500f + jitter[(it * 3) % jitter.size],
                pressure = 0.8f,
                tilt = deg(75f),
            )
        }
        val g = GraphiteGrain.of(pts, 14f, 4141)
        // Everything laid near the start must stay within the mark's own half-width of the path,
        // which runs flat along y = 500. A hook is ink that swings well outside that.
        val half = 14f / 2f * GraphiteGrain.widthFactor(deg(75f))
        val allowed = half + GraphiteGrain.TOOTH_PITCH_PX + GraphiteGrain.FLECK_MAX_PX + 1f
        var worst = 0f
        for (i in 0 until g.count) {
            if (g.xy[i * 2] > 200f + half) continue   // only the start
            val off = abs(g.xy[i * 2 + 1] - 500f)
            if (off > worst) worst = off
        }
        assertTrue("the start swung $worst off the path, allowed $allowed", worst <= allowed)
    }

    @Test
    fun `the cap does not draw a bead of ink round its own outline`() {
        // A cap's strips narrow as it closes, and laneCount rounds a lane count up so a hairline
        // still gets grain. Uncorrected, that over-deposits every narrow strip — and since their
        // outermost lanes sit on the cap's edge by construction, the excess accumulates along the
        // outline as a dark arc. The cap must lay graphite at the body's rate, not above it.
        val width = 14f
        val pts = listOf(
            StrokePoint(200f, 400f, 0.85f, deg(70f)),
            StrokePoint(600f, 400f, 0.85f, deg(70f)),
        )
        val g = GraphiteGrain.of(pts, width, 808)
        // Flecks per unit of paper, in the cap versus in the body.
        fun density(from: Float, to: Float): Float {
            var n = 0
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val x = g.xy[i * 2]
                if (x < from || x >= to) continue
                n++
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            if (n == 0) return 0f
            return n / ((to - from) * (hi - lo))
        }
        val body = density(350f, 450f)
        val cap = density(601f, 601f + width)
        assertTrue("nothing in the cap", cap > 0f)
        assertTrue("the cap ($cap) out-deposits the body ($body)", cap < body * 1.35f)
    }

    @Test
    fun `a stroke laid over from the first sample does not begin as a wedge`() {
        // A digitizer's tilt at touch-down is its least reliable reading. Seeding the lean filter
        // there makes a stroke the artist began with the lead already over start narrow and dark
        // and flare open over the next few millimetres — an arrowhead with a nub on the point.
        // One bad opening sample must not reshape the start of the mark.
        fun startWidth(openingTilt: Float): Float {
            val pts = (0..240).map {
                StrokePoint(
                    x = 100f + it * 2.5f,
                    y = 300f,
                    pressure = 0.8f,
                    tilt = deg(if (it == 0) openingTilt else 72f),
                )
            }
            val g = GraphiteGrain.of(pts, 12f, 313)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val x = g.xy[i * 2]
                if (x < 110f || x > 150f) continue
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return hi - lo
        }
        val honest = startWidth(72f)
        val badOpeningSample = startWidth(6f)
        assertTrue(
            "one low opening sample narrowed the start from $honest to $badOpeningSample",
            badOpeningSample > honest * 0.8f,
        )
    }

    @Test
    fun `passing through upright narrows a mark without darkening it`() {
        // Tilt drives width and darkness in opposite directions. Read from the same instant they
        // compound, and a moment of near-upright inside a laid-over stroke comes out ten times
        // narrower AND nearly twice as dark — a hard black nub. Darkness follows the lean the hand
        // has settled into, so the mark may narrow there but must not flash dark.
        val pts = (0..300).map {
            // laid over throughout, save for a brief pass through vertical
            val upright = it in 60..80
            StrokePoint(
                x = 100f + it * 3f,
                y = 400f,
                pressure = 0.8f,
                tilt = deg(if (upright) 10f else 72f),
            )
        }
        val g = GraphiteGrain.of(pts, 12f, 2727)
        fun density(from: Float, to: Float): Float {
            var n = 0
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val x = g.xy[i * 2]
                if (x < from || x >= to) continue
                n++
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return if (n == 0) 0f else n / ((to - from) * (hi - lo))
        }
        val throughUpright = density(300f, 340f)
        val laidOver = density(700f, 740f)
        assertTrue("nothing was measured", throughUpright > 0f && laidOver > 0f)
        assertTrue(
            "the mark flashed dark passing through upright ($throughUpright vs $laidOver)",
            throughUpright < laidOver * 1.25f,
        )
    }

    @Test
    fun `a stroke held flat is still paler than one held upright`() {
        // The steady-state effect must survive: this is what side-of-lead shading looks like, and
        // it is the thing the slower darkness filter must NOT have thrown away.
        fun density(tiltDeg: Float): Float {
            val pts = (0..300).map {
                StrokePoint(100f + it * 3f, 400f, 0.8f, deg(tiltDeg))
            }
            val g = GraphiteGrain.of(pts, 12f, 4545)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return g.count / ((hi - lo) * 900f)
        }
        assertTrue("flat should stay paler than upright", density(75f) < density(5f))
    }

    private fun deepestPileUp(settle: Float): Int {
        // A pen touching down: it lands, the hand settles through a small excursion, and only then
        // does the stroke set off. [settle] is the size of that excursion in px.
        val pts = ArrayList<StrokePoint>()
        for (i in 0 until 14) {
            val t = i / 13f
            pts.add(
                StrokePoint(
                    x = 300f + settle * kotlin.math.sin(t * 3.1f),
                    y = 400f + settle * 0.55f * kotlin.math.cos(t * 3.1f),
                    pressure = 0.8f,
                    tilt = deg(72f),
                )
            )
        }
        for (i in 1 until 200) pts.add(StrokePoint(300f + i * 2f, 400f, 0.8f, deg(72f)))
        val g = GraphiteGrain.of(pts, 14f, 5150)
        val bins = HashMap<Int, Int>()
        var worst = 0
        for (i in 0 until g.count) {
            val x = g.xy[i * 2]
            if (x < 260f || x > 345f) continue
            val key = x.toInt() * 4096 + g.xy[i * 2 + 1].toInt()
            val n = (bins[key] ?: 0) + 1
            bins[key] = n
            if (n > worst) worst = n
        }
        return worst
    }

    @Test
    fun `a landing wobble does not fold a broad mark over itself`() {
        // On a fine lead the pen's arrival is invisible. On a lead laid over — ten times broader —
        // the mark folds across itself there and graphite laid twice composites to solid black: a
        // knot at the start of every broad stroke. Neither the grain nor the caps can cure it, and
        // it cannot be filtered away (a filter can lag or damp a sustained excursion, not both), so
        // the arrival is dropped instead. What the hand did while landing is not a mark.
        val clean = deepestPileUp(0f)
        val wobbled = deepestPileUp(9f)
        assertTrue(
            "a landing wobble piled $wobbled flecks onto one pixel against $clean without it",
            wobbled <= clean + 1,
        )
    }

    @Test
    fun `a clean touch-down is not trimmed`() {
        // The trim looks for the pen travelling *against* where the stroke turned out to go. A
        // stroke that sets off cleanly never does, so it must keep every millimetre it was drawn
        // with — the arrival is dropped, not the first stretch of the mark.
        val pts = (0..200).map { StrokePoint(300f + it * 2f, 400f, 0.8f, deg(72f)) }
        val g = GraphiteGrain.of(pts, 14f, 5150)
        var earliest = Float.MAX_VALUE
        for (i in 0 until g.count) if (g.xy[i * 2] < earliest) earliest = g.xy[i * 2]
        val cap = 14f / 2f * GraphiteGrain.widthFactor(deg(72f))
        assertTrue("the start was eaten: ink begins at $earliest, not 300", earliest <= 300f + 2f)
        assertTrue("ink should reach back over the cap", earliest >= 300f - cap - 3f)
    }

    @Test
    fun `a stroke ends in a dome, not a chisel`() {
        // A lead meets the paper as a disc, so where it touches down and lifts the ink ends in a
        // half-round. Stopping at the last cross-section leaves a straight cut clean across the
        // mark, corners and all — which reads as a chisel and not a pencil.
        val width = 16f
        val pts = listOf(
            StrokePoint(200f, 400f, 0.85f),
            StrokePoint(500f, 400f, 0.85f),
        )
        val g = GraphiteGrain.of(pts, width, 606)
        // How far the ink reaches past the finish, measured at the centreline and at the flank.
        fun reachBeyond(nearCentre: Boolean): Float {
            var far = 0f
            for (i in 0 until g.count) {
                val y = g.xy[i * 2 + 1]
                val offAxis = abs(y - 400f)
                val want = if (nearCentre) offAxis < 2f else offAxis > width / 2f - 2.5f
                if (!want) continue
                val x = g.xy[i * 2]
                if (x > far) far = x
            }
            return far - 500f
        }
        val centre = reachBeyond(true)
        val flank = reachBeyond(false)
        assertTrue("the centreline should run past the last cross-section", centre > width / 4f)
        assertTrue(
            "the flank should stop short of the centreline — that is what makes it round " +
                "(centre $centre, flank $flank)",
            flank < centre - 1.5f,
        )
    }

    @Test
    fun `no points, no graphite`() {
        assertEquals(0, GraphiteGrain.of(emptyList(), 6f, 1).count)
    }

    @Test
    fun `a tap leaves a disc of grit`() {
        val g = GraphiteGrain.of(listOf(StrokePoint(50f, 50f, 0.9f)), 10f, 3)
        assertTrue("a tap should leave something", g.count > 0)
        val slack = 5f + GraphiteGrain.TOOTH_PITCH_PX + GraphiteGrain.FLECK_MAX_PX
        for (i in 0 until g.count) {
            assertTrue(abs(g.xy[i * 2] - 50f) <= slack)
            assertTrue(abs(g.xy[i * 2 + 1] - 50f) <= slack)
        }
    }

    @Test
    fun `a path shorter than one tooth still leaves a mark`() {
        val g = GraphiteGrain.of(
            listOf(StrokePoint(5f, 5f, 0.7f), StrokePoint(5.4f, 5f, 0.7f)),
            6f,
            8,
        )
        assertTrue("a nudge of the pen is still graphite", g.count > 0)
    }

    @Test
    fun `repeated points are not a divide by zero`() {
        val g = GraphiteGrain.of(List(20) { StrokePoint(11f, 12f, 0.5f) }, 6f, 8)
        assertTrue(g.count > 0)
    }

    @Test
    fun `darkness levels run from the floor to full black`() {
        assertEquals(1f, GraphiteGrain.levelAlpha(GraphiteGrain.LEVELS - 1), 1e-6f)
        assertTrue(GraphiteGrain.levelAlpha(0) < 1f)
        assertTrue(GraphiteGrain.levelAlpha(0) > 0f)
        for (l in 1 until GraphiteGrain.LEVELS) {
            assertTrue(GraphiteGrain.levelAlpha(l) > GraphiteGrain.levelAlpha(l - 1))
        }
        // Out-of-range indices clamp rather than throw — the renderer must never crash a page.
        assertEquals(GraphiteGrain.levelAlpha(0), GraphiteGrain.levelAlpha(-5), 0f)
        assertEquals(GraphiteGrain.levelAlpha(GraphiteGrain.LEVELS - 1), GraphiteGrain.levelAlpha(99), 0f)
    }

    @Test
    fun `pale flecks stand apart and dark ones flood together`() {
        // The whole cure for the "pipe cleaner": a fleck wider than the lattice that spaces it
        // touches its neighbours and the grain becomes chains of little worms instead of specks.
        // So the palest fleck is about one pitch — separate specks, like the panel's own dither —
        // and the darkest is over two, so a hard-pressed line floods solid.
        assertTrue(
            "the palest fleck must not chain",
            GraphiteGrain.fleckPx(0) <= GraphiteGrain.TOOTH_PITCH_PX * 1.05f,
        )
        assertTrue(
            "the darkest fleck must flood",
            GraphiteGrain.fleckPx(GraphiteGrain.LEVELS - 1) > GraphiteGrain.TOOTH_PITCH_PX * 1.8f,
        )
        for (l in 1 until GraphiteGrain.LEVELS) {
            assertTrue(GraphiteGrain.fleckPx(l) > GraphiteGrain.fleckPx(l - 1))
        }
        assertEquals(GraphiteGrain.fleckPx(0), GraphiteGrain.fleckPx(-3), 0f)
        assertEquals(
            GraphiteGrain.fleckPx(GraphiteGrain.LEVELS - 1), GraphiteGrain.fleckPx(99), 0f
        )
    }

    @Test
    fun `a fleck is never wider than the lead that lays it`() {
        // A 1.2 px hairline whose darkest flecks are 1.6 px bakes at more than twice the width it
        // previewed as. The cap only bites below the largest fleck; a lead of any ordinary size
        // gets exactly the flecks it always did.
        val top = GraphiteGrain.LEVELS - 1
        assertEquals(1.2f, GraphiteGrain.fleckPx(top, 1.2f), 0f)
        assertEquals(GraphiteGrain.fleckPx(top), GraphiteGrain.fleckPx(top, 6f), 0f)
        assertEquals(GraphiteGrain.fleckPx(0), GraphiteGrain.fleckPx(0, 6f), 0f)
        // Below the palest fleck a lead still bakes as graphite rather than as dust.
        assertEquals(GraphiteGrain.FLECK_MIN_PX, GraphiteGrain.fleckPx(top, 0.4f), 0f)
        // And the hairline's darkest fleck still overlaps the tooth pitch, so a hard-pressed
        // hairline floods solid instead of staying a dotted line.
        assertTrue(GraphiteGrain.fleckPx(top, 1.2f) > GraphiteGrain.TOOTH_PITCH_PX)
    }

    // ── Tilt widens the mark ─────────────────────────────────────────────────

    private fun deg(d: Float): Float = Math.toRadians(d.toDouble()).toFloat()

    @Test
    fun `the width curve matches what the panel's own firmware does`() {
        // Fitted on a NoteAir5C against the artist's eye, at the angles its digitizer reported as
        // 9, 44 and 75 degrees. The bake has to agree with the live ink or the mark changes size
        // when the pen lifts.
        assertEquals(1.0f, GraphiteGrain.widthFactor(deg(9f)), 0.01f)
        assertEquals(4.9f, GraphiteGrain.widthFactor(deg(44f)), 0.4f)
        assertEquals(10.9f, GraphiteGrain.widthFactor(deg(75f)), 0.6f)
    }

    @Test
    fun `the flank of the lead deposits lighter than its point`() {
        // Shading with the side of a pencil comes out grey however hard you lean, because the same
        // graphite is spread over a broader band.
        assertEquals(1f, GraphiteGrain.coverageFactor(0f), 0f)
        assertEquals(1f, GraphiteGrain.coverageFactor(deg(9f)), 0f)
        assertTrue(GraphiteGrain.coverageFactor(deg(44f)) < 1f)
        assertTrue(GraphiteGrain.coverageFactor(deg(75f)) < GraphiteGrain.coverageFactor(deg(44f)))
        // Never so light that a laid-over stroke stops being a mark.
        assertTrue(GraphiteGrain.coverageFactor(deg(90f)) > 0.4f)
    }

    @Test
    fun `a laid-over stroke is paler per unit of paper than an upright one`() {
        fun density(tiltDeg: Float): Float {
            val pts = listOf(
                StrokePoint(100f, 400f, 0.7f, deg(tiltDeg)),
                StrokePoint(500f, 400f, 0.7f, deg(tiltDeg)),
            )
            val g = GraphiteGrain.of(pts, 6f, 31)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return g.count / ((hi - lo) * 400f)
        }
        assertTrue("flat should be paler per unit area than upright", density(75f) < density(5f))
    }

    @Test
    fun `an upright pencil draws the width it was set to`() {
        // Zero is what every engine reports when it cannot honestly supply an angle, so this is
        // also the guarantee that an unmeasured device still gets a pencil rather than a hairline.
        assertEquals(1f, GraphiteGrain.widthFactor(0f), 0f)
        assertEquals(1f, GraphiteGrain.widthFactor(deg(5f)), 0f)
    }

    @Test
    fun `laying the pen over never narrows the mark`() {
        var previous = 0f
        var d = 0f
        while (d <= 90f) {
            val f = GraphiteGrain.widthFactor(deg(d))
            assertTrue("width went backwards at $d deg", f >= previous - 1e-4f)
            previous = f
            d += 1f
        }
    }

    @Test
    fun `a lean past flat is clamped rather than extrapolated`() {
        val flat = GraphiteGrain.widthFactor(deg(90f))
        assertEquals(flat, GraphiteGrain.widthFactor(deg(120f)), 0f)
        assertEquals(flat, GraphiteGrain.widthFactor(deg(400f)), 0f)
    }

    @Test
    fun `a stroke laid over deposits over a broader band than an upright one`() {
        fun band(tiltDeg: Float): Float {
            val pts = listOf(
                StrokePoint(100f, 200f, 0.7f, deg(tiltDeg)),
                StrokePoint(400f, 200f, 0.7f, deg(tiltDeg)),
            )
            val g = GraphiteGrain.of(pts, 6f, 77)
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return hi - lo
        }
        val upright = band(5f)
        val flat = band(75f)
        assertTrue("flat $flat should be far broader than upright $upright", flat > upright * 3f)
    }

    @Test
    fun `the pen rolling over mid-stroke broadens the mark as it goes`() {
        // A shading stroke is a hand laying the pencil down as it travels. Tilt is read per
        // station for exactly this; taken once at pen-down the mark would come out a uniform bar.
        val pts = listOf(
            StrokePoint(100f, 300f, 0.7f, deg(5f)),
            StrokePoint(500f, 300f, 0.7f, deg(80f)),
        )
        val g = GraphiteGrain.of(pts, 6f, 91)
        fun spreadNear(x: Float): Float {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                if (abs(g.xy[i * 2] - x) > 30f) continue
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return hi - lo
        }
        assertTrue("the far end should be broader than the near end", spreadNear(460f) > spreadNear(140f) * 2f)
    }

    @Test
    fun `a noisy tilt reading does not fringe the mark's edges`() {
        // A digitizer's tilt jitters; a hand cannot roll a pencil several degrees in a fraction of
        // a millimetre. Fed in raw it becomes geometry, and the stroke grows a fringe of hairs down
        // both sides — the "pipe cleaner". The lean is averaged along the path before it sets a
        // width, so a jittering reading and a clean one must produce edges of the same steadiness.
        fun edgeRipple(tilts: List<Float>): Float {
            val pts = tilts.mapIndexed { i, t ->
                StrokePoint(40f + i * 1.5f, 300f, 0.7f, deg(t))
            }
            val g = GraphiteGrain.of(pts, 8f, 99)
            // Top edge of the mark, sampled per column: how much does it wander?
            val top = HashMap<Int, Float>()
            for (i in 0 until g.count) {
                val x = g.xy[i * 2].toInt()
                val y = g.xy[i * 2 + 1]
                if (y < (top[x] ?: Float.MAX_VALUE)) top[x] = y
            }
            val xs = top.keys.sorted().drop(8).dropLast(8)
            if (xs.size < 20) return 0f
            var sum = 0f
            for (i in 1 until xs.size) sum += abs(top[xs[i]]!! - top[xs[i - 1]]!!)
            return sum / (xs.size - 1)
        }
        val steady = edgeRipple(List(260) { 60f })
        val jittery = edgeRipple(List(260) { i -> 60f + (if (i % 2 == 0) 5f else -5f) })
        assertTrue(
            "a jittering tilt made the edge wander $jittery vs $steady on a steady one",
            jittery < steady * 1.6f + 0.6f,
        )
    }

    @Test
    fun `a deliberate roll still broadens the mark`() {
        // Smoothing must not flatten the gesture it exists to render: laying the pen over across a
        // stroke is a real change and has to survive.
        val pts = (0..300).map {
            StrokePoint(40f + it * 2f, 300f, 0.7f, deg(5f + it * 0.25f))
        }
        val g = GraphiteGrain.of(pts, 8f, 5)
        fun spreadNear(x: Float): Float {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in 0 until g.count) {
                if (abs(g.xy[i * 2] - x) > 25f) continue
                val y = g.xy[i * 2 + 1]
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            return hi - lo
        }
        assertTrue("the roll must still show", spreadNear(580f) > spreadNear(120f) * 2f)
    }

    @Test
    fun `tilt does not disturb the grain's determinism`() {
        val pts = (0 until 40).map {
            StrokePoint(10f + it * 4f, 50f, 0.6f, deg(10f + it))
        }
        assertSameGrain(GraphiteGrain.of(pts, 6f, 5), GraphiteGrain.of(pts, 6f, 5))
    }

    @Test
    fun `an absurd stroke degrades instead of stalling the frame`() {
        val huge = (0 until 4000).map { StrokePoint(it * 3f, (it % 40) * 7f, 1f) }
        val g = GraphiteGrain.of(huge, 60f, 5)
        // Bounded well below anything that would stall a frame, and never zero — the cap must
        // degrade a monstrous stroke, not erase it.
        assertTrue("capped, got ${g.count}", g.count in 1..1_050_000)
        assertNotEquals(0, g.count)
    }
}
