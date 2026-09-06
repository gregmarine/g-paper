package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * What a contact on the transform overlay took hold of (0.1.27). The eight handles are
 * named by the compass point of the un-rotated box they sit on and travel round with it.
 */
enum class TransformGrab(internal val sx: Int, internal val sy: Int) {
    NONE(0, 0),
    /** Inside the box: a move. */
    BODY(0, 0),
    /** The rotate knob above the top edge. */
    ROTATE(0, 0),
    N(0, -1), NE(1, -1), E(1, 0), SE(1, 1), S(0, 1), SW(-1, 1), W(-1, 0), NW(-1, -1);

    val isHandle: Boolean get() = sx != 0 || sy != 0
    val isCorner: Boolean get() = sx != 0 && sy != 0

    companion object {
        val HANDLES: List<TransformGrab> = listOf(N, NE, E, SE, S, SW, W, NW)
    }
}

/**
 * The pure half of the transform mode: where the handles are, what a point grabbed, and
 * what a drag does to the box. Every function is a total function of its arguments — the
 * view feeds it the box the gesture *started* on plus the current pen point, so a
 * gesture is stable under any sample rate and never accumulates rounding.
 *
 * Conventions: page space is y-down, rotation is clockwise degrees about the centre; a
 * handle's local position is `(sx · w/2, sy · h/2)`; the rotate knob sits [knobOffset]
 * px above the top edge's midpoint along the box's own up axis.
 */
object TransformGeometry {

    /** Rotation snaps to a cardinal when within this many degrees of it. */
    const val ROTATION_SNAP_DEG = 5f

    /** Page position of a handle (or the knob, given [knobOffset]) on [box]. */
    fun grabPoint(box: OrientedBox, grab: TransformGrab, knobOffset: Float = 0f): Pair<Float, Float> =
        when (grab) {
            TransformGrab.ROTATE -> box.toPage(0f, -box.h / 2f - knobOffset)
            TransformGrab.BODY, TransformGrab.NONE -> Pair(box.cx, box.cy)
            else -> box.toPage(grab.sx * box.w / 2f, grab.sy * box.h / 2f)
        }

    /**
     * Classify a contact point. Handles win over the knob, the knob over the body, so a
     * small box never hides its handles under its own body; among handles the nearest
     * within [handleRadius] wins.
     */
    fun classify(
        box: OrientedBox,
        x: Float,
        y: Float,
        handleRadius: Float,
        knobOffset: Float,
        knobRadius: Float,
    ): TransformGrab {
        var best = TransformGrab.NONE
        var bestD2 = handleRadius * handleRadius
        for (g in TransformGrab.HANDLES) {
            val (hx, hy) = grabPoint(box, g)
            val d2 = (x - hx) * (x - hx) + (y - hy) * (y - hy)
            if (d2 <= bestD2) {
                bestD2 = d2
                best = g
            }
        }
        if (best != TransformGrab.NONE) return best
        val (kx, ky) = grabPoint(box, TransformGrab.ROTATE, knobOffset)
        val kd2 = (x - kx) * (x - kx) + (y - ky) * (y - ky)
        if (kd2 <= knobRadius * knobRadius) return TransformGrab.ROTATE
        if (box.contains(x, y)) return TransformGrab.BODY
        return TransformGrab.NONE
    }

    /** The box shifted by a page-space delta. */
    fun move(box: OrientedBox, dx: Float, dy: Float): OrientedBox =
        box.copy(cx = box.cx + dx, cy = box.cy + dy)

    /**
     * Resize [start] by dragging [grab] to the page point ([x], [y]). The opposite
     * handle is the anchor: an edge handle keeps the far edge (and the perpendicular
     * axis centred); a corner keeps the far corner. Neither side goes under [minSize].
     * With [aspectLocked] the ratio of [start] is kept: a corner follows the dominant
     * axis, an edge derives the other side and grows it about the centre.
     */
    fun resize(
        start: OrientedBox,
        grab: TransformGrab,
        x: Float,
        y: Float,
        aspectLocked: Boolean,
        minSize: Float,
    ): OrientedBox {
        if (!grab.isHandle) return start
        val (lx, ly) = start.toLocal(x, y)
        val hw = start.w / 2f
        val hh = start.h / 2f
        // Free extents from the anchored edge to the pointer, never under the minimum.
        var newW = when (grab.sx) {
            1 -> max(lx + hw, minSize)
            -1 -> max(hw - lx, minSize)
            else -> start.w
        }
        var newH = when (grab.sy) {
            1 -> max(ly + hh, minSize)
            -1 -> max(hh - ly, minSize)
            else -> start.h
        }
        if (aspectLocked) {
            val ratio = start.w / start.h
            if (grab.isCorner) {
                val scale = max(newW / start.w, newH / start.h)
                newW = start.w * scale
                newH = start.h * scale
            } else if (grab.sx != 0) {
                newH = newW / ratio
            } else {
                newW = newH * ratio
            }
            // The minimum applies to both sides after the ratio is imposed.
            if (newW < minSize || newH < minSize) {
                val scale = max(minSize / newW, minSize / newH)
                newW *= scale
                newH *= scale
            }
        }
        // Anchored edge stays; the new centre sits half the new extent from it. An
        // un-dragged axis keeps its centre (the locked-edge case grows about it).
        val lcx = when (grab.sx) {
            1 -> -hw + newW / 2f
            -1 -> hw - newW / 2f
            else -> 0f
        }
        val lcy = when (grab.sy) {
            1 -> -hh + newH / 2f
            -1 -> hh - newH / 2f
            else -> 0f
        }
        val (cx, cy) = start.toPage(lcx, lcy)
        return start.copy(cx = cx, cy = cy, w = newW, h = newH)
    }

    /**
     * Rotate [start] so its knob points at the page point ([x], [y]): the knob is the
     * box's up axis, so the new rotation is the pointer's bearing from the centre plus
     * 90°. Snaps to 0 / 90 / 180 / 270 within [snapDeg]. A pointer at the centre keeps
     * the start rotation.
     */
    fun rotate(start: OrientedBox, x: Float, y: Float, snapDeg: Float = ROTATION_SNAP_DEG): OrientedBox {
        val dx = x - start.cx
        val dy = y - start.cy
        if (dx == 0f && dy == 0f) return start
        val bearing = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return start.copy(rotationDeg = snap(OrientedBox.normalizeDeg(bearing + 90f), snapDeg))
    }

    /** Snap an angle in `[0, 360)` to the nearest cardinal when within [snapDeg] of it. */
    fun snap(deg: Float, snapDeg: Float = ROTATION_SNAP_DEG): Float {
        for (c in floatArrayOf(0f, 90f, 180f, 270f, 360f)) {
            if (abs(deg - c) <= snapDeg) return OrientedBox.normalizeDeg(c)
        }
        return deg
    }
}
