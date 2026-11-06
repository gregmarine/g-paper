package com.symmetricalpalmtree.gpaper.core.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint

/** Geometry for a single immutable stroke, used only by committed/drag rendering. */
internal class StrokeGeometry {
    var path: Path? = null
    var crossCenters: List<StrokePoint>? = null
    var grain: GraphiteGrain.Grain? = null

    fun estimatedBytes(stroke: Stroke): Long =
        512L + stroke.points.size * 64L + // samples plus conservative native polyline capacity
            (crossCenters?.size ?: 0) * 64L +
            (grain?.let { it.xy.size.toLong() + it.level.size } ?: 0L) * 4L
}

/** Per-view LRU, bounded both by retained geometry bytes and entry count. */
internal class StrokeGeometryCache {
    private class Entry(val stroke: Stroke, val ink: PencilInk?, val geometry: StrokeGeometry, val bytes: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0L

    fun draw(canvas: Canvas, stroke: Stroke, paint: Paint, ink: PencilInk?) {
        val cached = entries[stroke.id]
        if (cached != null && cached.ink == ink && (cached.stroke === stroke || cached.stroke == stroke)) {
            draw(canvas, stroke, paint, ink, cached.geometry)
            return
        }
        entries.remove(stroke.id)?.let { bytes -= it.bytes }
        val geometry = StrokeGeometry()
        draw(canvas, stroke, paint, ink, geometry)
        val cost = geometry.estimatedBytes(stroke)
        if (cost > MAX_BYTES) return
        entries[stroke.id] = Entry(stroke, ink, geometry, cost)
        bytes += cost
        val iterator = entries.values.iterator()
        while (bytes > MAX_BYTES || entries.size > MAX_ENTRIES) {
            bytes -= iterator.next().bytes
            iterator.remove()
        }
    }

    private fun draw(canvas: Canvas, stroke: Stroke, paint: Paint, ink: PencilInk?, geometry: StrokeGeometry) {
        StrokeRenderer.draw(
            canvas, stroke.points, stroke.color, stroke.width, stroke.style, paint,
            stroke.id.hashCode(), ink, geometry,
        )
    }

    fun retain(ids: Set<String>) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in ids) {
                bytes -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    fun clear() {
        entries.clear()
        bytes = 0L
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val MAX_ENTRIES = 128
    }
}
