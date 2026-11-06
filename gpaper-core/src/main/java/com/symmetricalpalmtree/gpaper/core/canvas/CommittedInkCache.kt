package com.symmetricalpalmtree.gpaper.core.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A disposable view-sized cache of paper, underlay and ink; editable strokes remain the source of truth.
 * Appending ink renders only the new marks. Other edits clear their old/new bounds and replay
 * intersecting marks in page order, including translucent ink beneath the changed marks.
 * Each of the retained image and lazy repair buffer is bounded by [maxBytes].
 */
internal class CommittedInkCache(private val maxBytes: Long = 32L * 1024 * 1024) {
    private val strokes = ArrayList<Stroke>()
    private val bounds = HashMap<String, Bounds>()
    private var hidden: Set<String> = emptySet()
    private var bitmap: Bitmap? = null
    private var repairBitmap: Bitmap? = null
    private var dirty: Bounds? = null
    private var pendingFrom = 0
    private var allocationFailed = false
    private var repairAllocationFailed = false

    fun invalidateBackground() {
        bitmap?.let { damage(Bounds(0f, 0f, it.width.toFloat(), it.height.toFloat())) }
    }

    fun append(stroke: Stroke) {
        strokes.add(stroke)
        bounds[stroke.id] = inkBounds(stroke)
        if (dirty != null) damage(bounds.getValue(stroke.id))
    }

    fun replace(next: List<Stroke>) {
        // Even an identical replacement must retain ink whose bake was deferred.
        // damage folds every unpainted append into the repair before pendingFrom resets.
        if (pendingFrom < strokes.size) damage(bounds.getValue(strokes[pendingFrom].id))
        val previous = strokes.associateBy { it.id }
        val nextIds = next.mapTo(HashSet()) { it.id }
        // A changed relative order changes compositing even when the samples are identical.
        val reordered = strokes.filter { it.id in nextIds }.map { it.id } !=
            next.filter { it.id in previous }.map { it.id }
        for (stroke in strokes) {
            if (stroke.id !in nextIds || reordered) damage(bounds.getValue(stroke.id))
        }
        for (stroke in next) {
            val old = previous[stroke.id]
            if (old !== stroke && old != stroke) {
                bounds[stroke.id]?.let(::damage)
                bounds[stroke.id] = inkBounds(stroke)
                damage(bounds.getValue(stroke.id))
            } else if (reordered) {
                damage(bounds.getValue(stroke.id))
            }
        }
        bounds.keys.retainAll(nextIds)
        strokes.clear()
        strokes.addAll(next)
        pendingFrom = strokes.size
    }

    private fun damage(area: Bounds) {
        var combined = dirty?.union(area) ?: area
        // If a removal/hide follows unpainted appends, replay them once with the repair.
        for (i in pendingFrom until strokes.size) combined = combined.union(bounds.getValue(strokes[i].id))
        pendingFrom = strokes.size
        dirty = combined
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        hiddenIds: Set<String>,
        drawBackground: (Canvas) -> Unit,
        drawStroke: (Canvas, Stroke) -> Unit,
    ) {
        if (hidden != hiddenIds) {
            for (id in hidden) if (id !in hiddenIds) bounds[id]?.let(::damage)
            for (id in hiddenIds) if (id !in hidden) bounds[id]?.let(::damage)
            hidden = hiddenIds.toSet()
        }
        if (allocationFailed || width <= 0 || height <= 0 || width.toLong() * height * 4 > maxBytes) {
            bitmap = null
            repairBitmap = null
            drawBackground(canvas)
            for (stroke in strokes) if (stroke.id !in hidden) drawStroke(canvas, stroke)
            return
        }
        var image = bitmap
        if (image == null || image.width != width || image.height != height) {
            repairBitmap?.recycle()
            repairBitmap = null
            image = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                // This cache is optional. Keep editable ink available under memory pressure,
                // and avoid retrying the allocation on every pen lift until the view resets.
                allocationFailed = true
                bitmap = null
                drawBackground(canvas)
                for (stroke in strokes) if (stroke.id !in hidden) drawStroke(canvas, stroke)
                return
            }
            bitmap = image
            dirty = Bounds(0f, 0f, width.toFloat(), height.toFloat())
        }
        val target = Canvas(image)
        val area = dirty
        if (area != null) {
            val left = floor(area.left).coerceAtLeast(0f)
            val top = floor(area.top).coerceAtLeast(0f)
            val right = ceil(area.right).coerceAtMost(width.toFloat())
            val bottom = ceil(area.bottom).coerceAtMost(height.toFloat())
            val clipped = Bounds(left, top, right, bottom)
            if (left < right && top < bottom) {
                // Preserve the original raster origin and clip. Clipping flecks or translating
                // paths into a small repair bitmap changes antialiasing at the repair boundary.
                val wholePage = left == 0f && top == 0f && right == width.toFloat() && bottom == height.toFloat()
                val patch = if (wholePage || repairAllocationFailed) null else try {
                    repairBitmap?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { repairBitmap = it }
                } catch (_: OutOfMemoryError) {
                    repairAllocationFailed = true
                    null // Repair the full retained image without an additional allocation.
                }
                val repairCanvas = if (patch == null) target else Canvas(patch)
                repairCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawBackground(repairCanvas)
                for (stroke in strokes) {
                    if (stroke.id !in hidden && (patch == null || bounds.getValue(stroke.id).intersects(clipped))) drawStroke(repairCanvas, stroke)
                }
                if (patch != null) {
                    val destination = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                    target.drawBitmap(patch, destination, destination, null)
                }
            }
        } else {
            for (i in pendingFrom until strokes.size) {
                val stroke = strokes[i]
                if (stroke.id !in hidden) drawStroke(target, stroke)
            }
        }
        dirty = null
        pendingFrom = strokes.size
        canvas.drawBitmap(image, 0f, 0f, null)
    }

    /** Drop rather than recycle: a previously recorded frame may still reference this bitmap. */
    fun dropBitmap() {
        bitmap = null
        repairBitmap?.recycle()
        repairBitmap = null
        dirty = null
        pendingFrom = 0
        allocationFailed = false
        repairAllocationFailed = false
    }

    private fun inkBounds(stroke: Stroke): Bounds {
        val reach = when (stroke.style) {
            StrokeStyle.PENCIL -> GraphiteGrain.reach(stroke.width, stroke.points.maxOfOrNull { it.tilt } ?: 0f)
            StrokeStyle.CROSS -> stroke.width * 2f + 1f
            else -> stroke.width
        }
        return stroke.bounds.inflated(reach.coerceAtLeast(0f) + 2f)
    }
}
