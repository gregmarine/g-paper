package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint

/**
 * Pure-JVM lasso hit-testing shared by every engine (the outline geometry is identical
 * whether the trail was painted by Canvas, the Onyx overlay, or the Ratta ink daemon).
 *
 * The lasso [polygon] is the captured freehand outline, treated as closed (last point
 * connects back to the first) with even-odd fill — concave and self-intersecting
 * outlines behave the way users expect from lasso tools.
 */
object LassoHitTest {

    /**
     * Ids of the strokes with **any point** inside the lasso — "touch" semantics: a
     * stroke partially inside is selected, matching the reference engines. Broad phase
     * rejects by bounding box; order follows [strokes]. Fewer than 3 polygon points can
     * enclose nothing.
     */
    fun hitStrokeIds(strokes: List<Stroke>, polygon: List<StrokePoint>): List<String> {
        if (polygon.size < 3) return emptyList()
        val polyBounds = Bounds.of(polygon)
        val hits = ArrayList<String>()
        for (stroke in strokes) {
            if (!polyBounds.intersects(stroke.bounds)) continue
            for (p in stroke.points) {
                if (Geometry.pointInPolygon(p.x, p.y, polygon)) {
                    hits.add(stroke.id)
                    break
                }
            }
        }
        return hits
    }

    /**
     * True when the lasso outline touches [bounds] anywhere — the host-content
     * ([com.symmetricalpalmtree.gpaper.core.render.HitTarget]) test: an outline that
     * crosses any part of the object's rect selects it, not just its center. Covers all
     * three containment shapes: rect inside polygon (corner-in-polygon), polygon inside
     * rect (vertex-in-rect), and boundary crossings (edge intersection, closing edge
     * included).
     */
    fun polygonIntersectsBounds(polygon: List<StrokePoint>, bounds: Bounds): Boolean {
        if (polygon.size < 3) return false
        // Polygon vertex inside (or on) the rect — also covers polygon-inside-rect.
        for (p in polygon) {
            if (bounds.contains(p.x, p.y)) return true
        }
        // Rect corner inside the polygon — covers rect-inside-polygon.
        if (Geometry.pointInPolygon(bounds.left, bounds.top, polygon)) return true
        if (Geometry.pointInPolygon(bounds.right, bounds.top, polygon)) return true
        if (Geometry.pointInPolygon(bounds.left, bounds.bottom, polygon)) return true
        if (Geometry.pointInPolygon(bounds.right, bounds.bottom, polygon)) return true
        // Boundary crossing without any vertex containment (outline slicing a corner).
        val edges = floatArrayOf(
            bounds.left, bounds.top, bounds.right, bounds.top,
            bounds.right, bounds.top, bounds.right, bounds.bottom,
            bounds.right, bounds.bottom, bounds.left, bounds.bottom,
            bounds.left, bounds.bottom, bounds.left, bounds.top,
        )
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[j]
            val b = polygon[i]
            var e = 0
            while (e < edges.size) {
                if (Geometry.segmentsIntersect(
                        a.x, a.y, b.x, b.y,
                        edges[e], edges[e + 1], edges[e + 2], edges[e + 3],
                    )
                ) {
                    return true
                }
                e += 4
            }
            j = i
        }
        return false
    }
}
