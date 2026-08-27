package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.hypot

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

    /**
     * Ids of the host content targets (id → hit rectangle, in target order) crossed out by
     * a **scribble** — the erase-gesture twin of [hitContentIds], with a stricter rule.
     *
     * A scribble hits an object only when it travels at least [minPenetrationPx] *inside*
     * that object's bounds: the summed length of every scribble segment with at least one
     * endpoint in the rectangle. Deliberately not [hitContentIds]' touch-anything rule — a
     * scribble is a large gesture, so "touched the inflated rect" would take a heading
     * every time the ink beside it was scribbled out. Penetration distinguishes a
     * deliberate crossing-out from a corner-graze.
     *
     * Broad phase mirrors the other two tests (the scribble's own AABB against each
     * target's, no radius — the narrow phase is a containment test, not a distance one).
     * Each id at most once; empty inputs hit nothing.
     */
    fun scribbleContentIds(
        targets: List<Pair<String, Bounds>>,
        scribblePoints: List<StrokePoint>,
        minPenetrationPx: Float,
    ): List<String> {
        if (targets.isEmpty() || scribblePoints.size < 2) return emptyList()
        val sweepBounds = Bounds.of(scribblePoints)
        val hits = ArrayList<String>()
        for ((id, bounds) in targets) {
            if (id in hits) continue
            if (!sweepBounds.intersects(bounds)) continue
            if (penetration(scribblePoints, bounds) >= minPenetrationPx) hits.add(id)
        }
        return hits
    }

    /**
     * Summed length of the [points] polyline's segments that have at least one endpoint
     * inside [box] — how far the gesture travelled through the object. A segment straddling
     * an edge counts whole, which is what makes a single deep stab register; a chord that
     * passes clean through with both endpoints outside counts as nothing, which is the
     * reference engine's behaviour and costs nothing in practice (stylus sampling is far
     * finer than a 14 dp box).
     */
    private fun penetration(points: List<StrokePoint>, box: Bounds): Float {
        var total = 0f
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (box.contains(a.x, a.y) || box.contains(b.x, b.y)) {
                total += hypot(b.x - a.x, b.y - a.y)
            }
        }
        return total
    }
}
