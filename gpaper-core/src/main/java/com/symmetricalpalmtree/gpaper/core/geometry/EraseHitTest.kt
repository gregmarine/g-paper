package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint

/**
 * Pure-JVM eraser hit-testing shared by the engines: which strokes does one sweep of the
 * eraser (a short polyline of stylus samples) hit, given the eraser radius?
 *
 * Two phases, per the Notesprout erase-performance rules:
 * 1. **Broad phase** — the eraser sweep's AABB, inflated by [radius][hitStrokeIds], against
 *    each stroke's precomputed [Stroke.bounds]. O(1) per stroke, rejects the vast majority.
 * 2. **Narrow phase** — polyline-to-polyline distance ([Geometry.polylineWithinDistance]),
 *    so a fast sweep that jumps clean across a stroke between two samples still hits it.
 *
 * Stroke *width* is deliberately ignored (the eraser radius dominates in practice and this
 * matches the reference engines). Callers throttle redraws; this function only computes.
 */
object EraseHitTest {

    /**
     * Ids of the strokes in [strokes] hit by an eraser of [radius] px swept along
     * [eraserPoints], in stroke order, each id at most once. Empty inputs hit nothing.
     */
    fun hitStrokeIds(
        strokes: List<Stroke>,
        eraserPoints: List<StrokePoint>,
        radius: Float,
    ): List<String> {
        if (strokes.isEmpty() || eraserPoints.isEmpty()) return emptyList()
        val sweepBounds = Bounds.of(eraserPoints).inflated(radius)
        val hits = ArrayList<String>()
        for (stroke in strokes) {
            if (!sweepBounds.intersects(stroke.bounds)) continue
            if (Geometry.polylineWithinDistance(stroke.points, eraserPoints, radius)) {
                hits.add(stroke.id)
            }
        }
        return hits
    }

    /**
     * Ids of the host content targets (id → hit rectangle, in target order) hit by an
     * eraser of [radius] px swept along [eraserPoints], each id at most once — the
     * whole-object twin of [hitStrokeIds] (0.1.4): touching any part of a target's
     * rectangle hits the whole object. Broad phase mirrors the stroke test; the narrow
     * phase is the sweep polyline against the rectangle inflated by [radius]
     * ([Geometry.polylineIntersectsRect] — square-corner tolerance, like the lasso's
     * content overlap test). Empty inputs hit nothing.
     */
    fun hitContentIds(
        targets: List<Pair<String, Bounds>>,
        eraserPoints: List<StrokePoint>,
        radius: Float,
    ): List<String> {
        if (targets.isEmpty() || eraserPoints.isEmpty()) return emptyList()
        val sweepBounds = Bounds.of(eraserPoints).inflated(radius)
        val hits = ArrayList<String>()
        for ((id, bounds) in targets) {
            if (id in hits) continue
            if (!sweepBounds.intersects(bounds)) continue
            if (Geometry.polylineIntersectsRect(eraserPoints, bounds.inflated(radius))) {
                hits.add(id)
            }
        }
        return hits
    }
}
