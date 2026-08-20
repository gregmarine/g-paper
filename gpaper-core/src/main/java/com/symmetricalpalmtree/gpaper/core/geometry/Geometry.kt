package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.min
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

    /**
     * Euclidean distance between segments ([ax],[ay])–([bx],[by]) and ([cx],[cy])–([dx],[dy]):
     * 0 when they intersect, otherwise the closest endpoint-to-opposite-segment distance.
     * Degenerate (zero-length) segments degrade to point checks.
     */
    fun distanceSegmentToSegment(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return min(
            min(
                distancePointToSegment(ax, ay, cx, cy, dx, dy),
                distancePointToSegment(bx, by, cx, cy, dx, dy),
            ),
            min(
                distancePointToSegment(cx, cy, ax, ay, bx, by),
                distancePointToSegment(dx, dy, ax, ay, bx, by),
            ),
        )
    }

    /**
     * True when polylines [a] and [b] come within [distance] of each other — the eraser
     * narrow-phase test with the eraser's own path as a polyline, so a fast sweep that
     * jumps clean across a stroke between two samples still hits it. Single-point lists
     * act as degenerate segments; an empty list never matches.
     */
    fun polylineWithinDistance(
        a: List<StrokePoint>,
        b: List<StrokePoint>,
        distance: Float,
    ): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val aSegments = maxOf(1, a.size - 1)
        val bSegments = maxOf(1, b.size - 1)
        for (i in 0 until aSegments) {
            val a1 = a[i]
            val a2 = a[minOf(i + 1, a.size - 1)]
            for (j in 0 until bSegments) {
                val b1 = b[j]
                val b2 = b[minOf(j + 1, b.size - 1)]
                val d = distanceSegmentToSegment(a1.x, a1.y, a2.x, a2.y, b1.x, b1.y, b2.x, b2.y)
                if (d <= distance) return true
            }
        }
        return false
    }

    /**
     * Resample the polyline [points] at every [spacing] px of arc length, starting at (and
     * including) the first point. Pressure and tilt are linearly interpolated; each sample
     * keeps the earlier endpoint's timestamp. Used to place repeated marks along a path
     * (the CROSS stroke style). Empty input or non-positive spacing → empty list.
     */
    fun sampleAlongPolyline(points: List<StrokePoint>, spacing: Float): List<StrokePoint> {
        if (points.isEmpty() || spacing <= 0f) return emptyList()
        val out = ArrayList<StrokePoint>()
        out.add(points[0])
        var traveled = 0f
        var nextAt = spacing
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segDx = b.x - a.x
            val segDy = b.y - a.y
            val segLen = sqrt(segDx * segDx + segDy * segDy)
            if (segLen == 0f) continue
            while (traveled + segLen >= nextAt) {
                val t = (nextAt - traveled) / segLen
                out.add(
                    StrokePoint(
                        x = a.x + t * segDx,
                        y = a.y + t * segDy,
                        pressure = a.pressure + t * (b.pressure - a.pressure),
                        tilt = a.tilt + t * (b.tilt - a.tilt),
                        timeMillis = a.timeMillis,
                    )
                )
                nextAt += spacing
            }
            traveled += segLen
        }
        return out
    }

    /**
     * True when the polyline [points] touches the axis-aligned [rect] — any point inside
     * (or on) the rect, or any segment crossing one of its edges. A single point acts as
     * a containment test; an empty list never matches. The eraser's content narrow-phase
     * test, run against the target's bounds pre-inflated by the eraser radius (square
     * corners — the same box-tolerance the lasso's content overlap test accepts).
     */
    fun polylineIntersectsRect(points: List<StrokePoint>, rect: Bounds): Boolean {
        if (points.isEmpty()) return false
        for (p in points) if (rect.contains(p.x, p.y)) return true
        if (points.size == 1) return false
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (segmentsIntersect(a.x, a.y, b.x, b.y, rect.left, rect.top, rect.right, rect.top)) return true
            if (segmentsIntersect(a.x, a.y, b.x, b.y, rect.right, rect.top, rect.right, rect.bottom)) return true
            if (segmentsIntersect(a.x, a.y, b.x, b.y, rect.right, rect.bottom, rect.left, rect.bottom)) return true
            if (segmentsIntersect(a.x, a.y, b.x, b.y, rect.left, rect.bottom, rect.left, rect.top)) return true
        }
        return false
    }

    /** Segment intersection (touching counts), with collinear-overlap handling. */
    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val o1 = orientation(ax, ay, bx, by, cx, cy)
        val o2 = orientation(ax, ay, bx, by, dx, dy)
        val o3 = orientation(cx, cy, dx, dy, ax, ay)
        val o4 = orientation(cx, cy, dx, dy, bx, by)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(ax, ay, cx, cy, bx, by)) return true
        if (o2 == 0 && onSegment(ax, ay, dx, dy, bx, by)) return true
        if (o3 == 0 && onSegment(cx, cy, ax, ay, dx, dy)) return true
        if (o4 == 0 && onSegment(cx, cy, bx, by, dx, dy)) return true
        return false
    }

    /** Sign of the cross product (q−p)×(r−p): 0 collinear, 1 clockwise, −1 counter-clockwise. */
    private fun orientation(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
        val cross = (qx - px) * (ry - py) - (qy - py) * (rx - px)
        return when {
            cross > 0f -> 1
            cross < 0f -> -1
            else -> 0
        }
    }

    /** For collinear p,q,r: does q lie within the AABB of segment p–r? */
    private fun onSegment(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Boolean =
        qx >= minOf(px, rx) && qx <= maxOf(px, rx) &&
            qy >= minOf(py, ry) && qy <= maxOf(py, ry)
}
