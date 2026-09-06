package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transform mode's arithmetic (0.1.27): a contact lands on the handle, knob or body
 * it is over — on a rotated box too; a resize anchors the opposite handle and never goes
 * under the minimum; the aspect lock keeps the ratio; rotation follows the knob and snaps
 * near the cardinals.
 */
class TransformGeometryTest {

    private val handleR = 22f
    private val knobOff = 36f
    private val knobR = 22f
    private val minSize = 24f

    private val upright = OrientedBox(cx = 200f, cy = 300f, w = 100f, h = 60f, rotationDeg = 0f)
    private val tilted = upright.copy(rotationDeg = 37f)

    private fun classify(box: OrientedBox, x: Float, y: Float) =
        TransformGeometry.classify(box, x, y, handleR, knobOff, knobR)

    private fun assertBox(expected: OrientedBox, actual: OrientedBox, eps: Float = 0.01f) {
        assertEquals("cx", expected.cx, actual.cx, eps)
        assertEquals("cy", expected.cy, actual.cy, eps)
        assertEquals("w", expected.w, actual.w, eps)
        assertEquals("h", expected.h, actual.h, eps)
        assertEquals("rot", expected.rotationDeg, actual.rotationDeg, eps)
    }

    // ── Local frame ──────────────────────────────────────────────────────────

    @Test
    fun `local and page frames round-trip on a rotated box`() {
        val (px, py) = tilted.toPage(30f, -12f)
        val (lx, ly) = tilted.toLocal(px, py)
        assertEquals(30f, lx, 0.001f)
        assertEquals(-12f, ly, 0.001f)
    }

    @Test
    fun `aabb of an upright box is the box and of a rotated one is larger`() {
        val a = upright.aabb()
        assertEquals(150f, a.left, 0.001f)
        assertEquals(270f, a.top, 0.001f)
        assertEquals(250f, a.right, 0.001f)
        assertEquals(330f, a.bottom, 0.001f)
        val t = tilted.aabb()
        assert(t.width > 100f && t.height > 60f)
    }

    // ── Classification ───────────────────────────────────────────────────────

    @Test
    fun `every handle is found where it sits on an upright box`() {
        assertEquals(TransformGrab.N, classify(upright, 200f, 270f))
        assertEquals(TransformGrab.NE, classify(upright, 250f, 270f))
        assertEquals(TransformGrab.E, classify(upright, 250f, 300f))
        assertEquals(TransformGrab.SE, classify(upright, 250f, 330f))
        assertEquals(TransformGrab.S, classify(upright, 200f, 330f))
        assertEquals(TransformGrab.SW, classify(upright, 150f, 330f))
        assertEquals(TransformGrab.W, classify(upright, 150f, 300f))
        assertEquals(TransformGrab.NW, classify(upright, 150f, 270f))
    }

    @Test
    fun `every handle is found where it sits on a rotated box`() {
        for (g in TransformGrab.HANDLES) {
            val (x, y) = TransformGeometry.grabPoint(tilted, g)
            assertEquals(g, classify(tilted, x + 5f, y - 3f))
        }
    }

    @Test
    fun `the knob sits above the top edge along the box's own up axis`() {
        assertEquals(TransformGrab.ROTATE, classify(upright, 200f, 300f - 30f - 36f))
        val (kx, ky) = TransformGeometry.grabPoint(tilted, TransformGrab.ROTATE, knobOff)
        assertEquals(TransformGrab.ROTATE, classify(tilted, kx, ky))
        // On the rotated box the upright knob position is bare paper.
        assertEquals(TransformGrab.NONE, classify(tilted, 200f, 300f - 30f - 36f - 20f))
    }

    @Test
    fun `the body is inside and outside is none`() {
        assertEquals(TransformGrab.BODY, classify(upright, 200f, 300f))
        assertEquals(TransformGrab.BODY, classify(upright, 215f, 290f))
        assertEquals(TransformGrab.NONE, classify(upright, 300f, 300f))
        assertEquals(TransformGrab.NONE, classify(upright, 200f, 200f))
        // A corner of the upright box is outside the tilted one; its interior is not.
        assertEquals(TransformGrab.BODY, classify(tilted, 200f, 300f))
    }

    @Test
    fun `a handle beats the body it overlaps`() {
        // 10 px inside the east edge is within the handle radius: the handle wins.
        assertEquals(TransformGrab.E, classify(upright, 240f, 300f))
    }

    // ── Resize ───────────────────────────────────────────────────────────────

    @Test
    fun `an edge handle anchors the far edge`() {
        val r = TransformGeometry.resize(upright, TransformGrab.E, 290f, 999f, false, minSize)
        assertBox(OrientedBox(cx = 220f, cy = 300f, w = 140f, h = 60f, rotationDeg = 0f), r)
        val l = TransformGeometry.resize(upright, TransformGrab.W, 170f, 0f, false, minSize)
        assertBox(OrientedBox(cx = 210f, cy = 300f, w = 80f, h = 60f, rotationDeg = 0f), l)
        val n = TransformGeometry.resize(upright, TransformGrab.N, 0f, 250f, false, minSize)
        assertBox(OrientedBox(cx = 200f, cy = 290f, w = 100f, h = 80f, rotationDeg = 0f), n)
    }

    @Test
    fun `a corner handle anchors the far corner`() {
        val r = TransformGeometry.resize(upright, TransformGrab.SE, 270f, 350f, false, minSize)
        // NW corner stays at (150, 270); new extent 120 × 80.
        assertBox(OrientedBox(cx = 210f, cy = 310f, w = 120f, h = 80f, rotationDeg = 0f), r)
        val nw = TransformGeometry.resize(upright, TransformGrab.NW, 140f, 260f, false, minSize)
        assertBox(OrientedBox(cx = 195f, cy = 295f, w = 110f, h = 70f, rotationDeg = 0f), nw)
    }

    @Test
    fun `a resize never goes under the minimum and crossing the anchor clamps`() {
        val r = TransformGeometry.resize(upright, TransformGrab.E, 100f, 300f, false, minSize)
        assertEquals(minSize, r.w, 0.001f)
        assertEquals(150f + minSize / 2f, r.cx, 0.001f) // west edge still at 150
        val c = TransformGeometry.resize(upright, TransformGrab.SE, 0f, 0f, false, minSize)
        assertEquals(minSize, c.w, 0.001f)
        assertEquals(minSize, c.h, 0.001f)
    }

    @Test
    fun `a rotated resize keeps the anchor at the same page point`() {
        val anchorBefore = TransformGeometry.grabPoint(tilted, TransformGrab.W)
        val (tx, ty) = tilted.toPage(90f, 10f) // drag E outward, a bit off-axis
        val r = TransformGeometry.resize(tilted, TransformGrab.E, tx, ty, false, minSize)
        val anchorAfter = TransformGeometry.grabPoint(r, TransformGrab.W)
        assertEquals(anchorBefore.first, anchorAfter.first, 0.01f)
        assertEquals(anchorBefore.second, anchorAfter.second, 0.01f)
        assertEquals(140f, r.w, 0.01f)
        assertEquals(60f, r.h, 0.01f)
        assertEquals(37f, r.rotationDeg, 0.001f)
    }

    @Test
    fun `the aspect lock keeps the ratio on a corner and follows the dominant axis`() {
        // 100×60: dragging SE by +100 in x and +10 in y → x dominates, scale 2.
        val r = TransformGeometry.resize(upright, TransformGrab.SE, 350f, 340f, true, minSize)
        assertEquals(200f, r.w, 0.01f)
        assertEquals(120f, r.h, 0.01f)
        // NW corner anchored at (150, 270).
        assertEquals(250f, r.cx, 0.01f)
        assertEquals(330f, r.cy, 0.01f)
    }

    @Test
    fun `the aspect lock on an edge derives the other side about the centre`() {
        val r = TransformGeometry.resize(upright, TransformGrab.E, 350f, 300f, true, minSize)
        assertEquals(200f, r.w, 0.01f)
        assertEquals(120f, r.h, 0.01f)
        assertEquals(250f, r.cx, 0.01f) // west edge still at 150
        assertEquals(300f, r.cy, 0.01f) // height grew about the centre
    }

    @Test
    fun `the aspect lock respects the minimum on the derived side`() {
        // Shrinking the width to 24 would make the height 14.4: both are pushed up.
        val r = TransformGeometry.resize(upright, TransformGrab.E, 0f, 300f, true, minSize)
        assertEquals(minSize, r.h, 0.01f)
        assertEquals(40f, r.w, 0.01f)
    }

    @Test
    fun `a body grab is a move`() {
        val m = TransformGeometry.move(tilted, 15f, -7f)
        assertBox(tilted.copy(cx = 215f, cy = 293f), m)
        // A non-handle grab through resize is a no-op.
        assertBox(tilted, TransformGeometry.resize(tilted, TransformGrab.BODY, 0f, 0f, false, minSize))
    }

    // ── Rotate ───────────────────────────────────────────────────────────────

    @Test
    fun `the rotation follows the knob's bearing`() {
        // Knob dragged to the right of the centre: up axis points east → 90° clockwise.
        assertEquals(90f, TransformGeometry.rotate(upright, 300f, 300f).rotationDeg, 0.001f)
        assertEquals(180f, TransformGeometry.rotate(upright, 200f, 400f).rotationDeg, 0.001f)
        assertEquals(270f, TransformGeometry.rotate(upright, 100f, 300f).rotationDeg, 0.001f)
        assertEquals(0f, TransformGeometry.rotate(upright, 200f, 100f).rotationDeg, 0.001f)
        // The knob's own rest position keeps a tilted box where it is.
        val (kx, ky) = TransformGeometry.grabPoint(tilted, TransformGrab.ROTATE, knobOff)
        assertEquals(37f, TransformGeometry.rotate(tilted, kx, ky).rotationDeg, 0.01f)
    }

    @Test
    fun `rotation snaps within five degrees of a cardinal and not beyond`() {
        assertEquals(0f, TransformGeometry.snap(3f), 0f)
        assertEquals(0f, TransformGeometry.snap(357f), 0f)
        assertEquals(90f, TransformGeometry.snap(94.9f), 0f)
        assertEquals(270f, TransformGeometry.snap(265.5f), 0f)
        assertEquals(7f, TransformGeometry.snap(7f), 0f)
        assertEquals(37f, TransformGeometry.snap(37f), 0f)
        assertEquals(184.9f, TransformGeometry.snap(184.9f, 4f), 0f)
    }

    @Test
    fun `a pointer at the centre keeps the rotation and size stays through a rotate`() {
        assertBox(tilted, TransformGeometry.rotate(tilted, 200f, 300f))
        val r = TransformGeometry.rotate(tilted, 260f, 340f)
        assertEquals(tilted.w, r.w, 0f)
        assertEquals(tilted.h, r.h, 0f)
        assertEquals(tilted.cx, r.cx, 0f)
    }

    @Test
    fun `angles normalise into the half-open circle`() {
        assertEquals(0f, OrientedBox.normalizeDeg(360f), 0f)
        assertEquals(350f, OrientedBox.normalizeDeg(-10f), 0f)
        assertEquals(10f, OrientedBox.normalizeDeg(730f), 0f)
    }
}
