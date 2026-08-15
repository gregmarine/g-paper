package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.sqrt

/**
 * Pure-JVM geometry primitives shared by the engines' eraser and lasso hit tests.
 * No Android dependencies; all coordinates are paper-space px.
 */
object Geometry {

    /** Euclidean distance from point ([px], [py]) to the segment ([ax],[ay])–([bx],[by]). */
    fun distancePointToSegment(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        // Degenerate segment: distance to the single point.
        if (lenSq == 0f) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }
        val t = (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0f, 1f)
        val cx = ax + t * abx
        val cy = ay + t * aby
        val dx = px - cx
        val dy = py - cy
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Even-odd ray-casting test: is ([px], [py]) inside the polygon [polygon]?
     * The polygon is treated as closed (last point connects back to the first).
     * Works for concave and self-intersecting outlines — exactly what a freehand
     * lasso produces. Returns false for polygons with fewer than 3 points.
     */
    fun pointInPolygon(px: Float, py: Float, polygon: List<StrokePoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val crosses = (pi.y > py) != (pj.y > py) &&
                px < (pj.x - pi.x) * (py - pi.y) / (pj.y - pi.y) + pi.x
            if (crosses) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * True when any segment of the polyline [points] passes within [radius] of
     * ([cx], [cy]) — the eraser narrow-phase test. A single-point polyline degrades
     * to a point-distance check; an empty list is never hit.
     */
    fun polylineIntersectsCircle(
        points: List<StrokePoint>,
        cx: Float, cy: Float,
        radius: Float,
    ): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) {
            return distancePointToSegment(cx, cy, points[0].x, points[0].y, points[0].x, points[0].y) <= radius
        }
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (distancePointToSegment(cx, cy, a.x, a.y, b.x, b.y) <= radius) return true
        }
        return false
    }
}
