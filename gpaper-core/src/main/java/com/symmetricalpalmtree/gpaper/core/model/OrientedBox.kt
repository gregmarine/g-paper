package com.symmetricalpalmtree.gpaper.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A rotated rectangle in paper coordinates — the geometry the transform mode (0.1.27)
 * edits and reports. Pure Kotlin, zero Android deps.
 *
 * @property cx Centre x, px.
 * @property cy Centre y, px.
 * @property w Un-rotated width, px (> 0).
 * @property h Un-rotated height, px (> 0).
 * @property rotationDeg Rotation about the centre, degrees, clockwise on screen (y
 *   down), normalised to `[0, 360)`. Matches `Canvas.rotate`.
 */
data class OrientedBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float,
) {
    private val rad: Double get() = Math.toRadians(rotationDeg.toDouble())

    /** Page point → the box's local frame (centre at the origin, un-rotated axes). */
    fun toLocal(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - cx
        val dy = y - cy
        val c = cos(rad)
        val s = sin(rad)
        return Pair((dx * c + dy * s).toFloat(), (-dx * s + dy * c).toFloat())
    }

    /** Local point (centre at the origin, un-rotated axes) → page. */
    fun toPage(lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(rad)
        val s = sin(rad)
        return Pair((cx + lx * c - ly * s).toFloat(), (cy + lx * s + ly * c).toFloat())
    }

    /** The four corners in page space: top-left, top-right, bottom-right, bottom-left
     *  (of the un-rotated box, carried round with it). */
    fun corners(): List<Pair<Float, Float>> {
        val hw = w / 2f
        val hh = h / 2f
        return listOf(toPage(-hw, -hh), toPage(hw, -hh), toPage(hw, hh), toPage(-hw, hh))
    }

    /** Axis-aligned bounds of the rotated box. */
    fun aabb(): Bounds {
        val pts = corners()
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for ((x, y) in pts) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        return Bounds(l, t, r, b)
    }

    /** Whether the page point lies inside the rotated box, inflated by [pad] px on
     *  every side (in the local frame). */
    fun contains(x: Float, y: Float, pad: Float = 0f): Boolean {
        val (lx, ly) = toLocal(x, y)
        return abs(lx) <= w / 2f + pad && abs(ly) <= h / 2f + pad
    }

    companion object {
        /** Bring an angle into `[0, 360)`. */
        fun normalizeDeg(deg: Float): Float {
            var d = deg % 360f
            if (d < 0f) d += 360f
            if (d >= 360f) d -= 360f
            return d
        }
    }
}
