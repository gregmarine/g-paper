package com.symmetricalpalmtree.gpaper.core.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.symmetricalpalmtree.gpaper.core.geometry.Geometry
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * The committed (baked) appearance of every [StrokeStyle] — hand-rolled, portable Canvas
 * code with no SDK dependencies. This is the single source of truth for how a stroke looks
 * once baked: every engine (generic, Onyx, Ratta) renders committed strokes through here,
 * which is what makes stroke data portable across devices (see [StrokeStyle]).
 *
 * Phase 2 first slice: [StrokeStyle.PEN], [StrokeStyle.MARKER], [StrokeStyle.DASH],
 * [StrokeStyle.CROSS], and [StrokeStyle.FOUNTAIN] have real renderers; the textured
 * [StrokeStyle.PENCIL], [StrokeStyle.BRUSH], and [StrokeStyle.CALLIGRAPHY] render as
 * [StrokeStyle.PEN] until their renderers land (documented incremental-rendering caveat).
 *
 * Not thread-confined by itself, but callers pass in their own scratch [Paint], which this
 * object fully re-configures on every call — never rely on paint state across calls.
 */
internal object StrokeRenderer {

    /** MARKER: committed alpha multiplier (semi-transparent highlighter look). */
    private const val MARKER_ALPHA = 0.45f

    /** DASH: on/off dash lengths as multiples of the stroke width. */
    private const val DASH_ON_FACTOR = 3f
    private const val DASH_OFF_FACTOR = 2.5f

    /** CROSS: x-mark spacing along the path and half-arm length, as width multiples. */
    private const val CROSS_SPACING_FACTOR = 4.5f
    private const val CROSS_ARM_FACTOR = 1.6f
    private const val CROSS_LINE_WIDTH_FACTOR = 0.5f

    /** FOUNTAIN: width factor = FOUNTAIN_MIN + FOUNTAIN_RANGE × pressure. */
    private const val FOUNTAIN_MIN = 0.35f
    private const val FOUNTAIN_RANGE = 1.05f

    /**
     * Draw one stroke's [points] onto [canvas] in [style], using the caller's scratch
     * [paint] (fully re-configured here). [points] may be a single sample — a tap renders
     * as a dot/mark. Coordinates are paper-space; the caller has applied any transform.
     */
    fun draw(
        canvas: Canvas,
        points: List<StrokePoint>,
        color: Int,
        width: Float,
        style: StrokeStyle,
        paint: Paint,
    ) {
        if (points.isEmpty()) return
        resetPaint(paint, color, width)
        when (style) {
            StrokeStyle.PEN,
            StrokeStyle.PENCIL,
            StrokeStyle.BRUSH,
            StrokeStyle.CALLIGRAPHY,
            -> drawPen(canvas, points, paint)

            StrokeStyle.MARKER -> drawMarker(canvas, points, color, width, paint)
            StrokeStyle.DASH -> drawDash(canvas, points, width, paint)
            StrokeStyle.CROSS -> drawCross(canvas, points, width, paint)
            StrokeStyle.FOUNTAIN -> drawFountain(canvas, points, width, paint)
        }
    }

    /** Baseline uniform-width round-cap polyline; single point → round dot. */
    private fun drawPen(canvas: Canvas, points: List<StrokePoint>, paint: Paint) {
        if (points.size == 1) {
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        canvas.drawPath(polylinePath(points), paint)
    }

    private fun drawMarker(
        canvas: Canvas,
        points: List<StrokePoint>,
        color: Int,
        width: Float,
        paint: Paint,
    ) {
        // One drawPath = one coverage pass, so the translucency stays uniform even where
        // the path self-overlaps.
        paint.color = withAlphaFactor(color, MARKER_ALPHA)
        paint.strokeCap = Paint.Cap.BUTT
        if (points.size == 1) {
            val p = points[0]
            val half = width / 2f
            paint.style = Paint.Style.FILL
            canvas.drawRect(p.x - half, p.y - half, p.x + half, p.y + half, paint)
            return
        }
        canvas.drawPath(polylinePath(points), paint)
    }

    private fun drawDash(canvas: Canvas, points: List<StrokePoint>, width: Float, paint: Paint) {
        paint.pathEffect = DashPathEffect(
            floatArrayOf(DASH_ON_FACTOR * width, DASH_OFF_FACTOR * width), 0f
        )
        if (points.size == 1) {
            paint.pathEffect = null
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        canvas.drawPath(polylinePath(points), paint)
        paint.pathEffect = null
    }

    private fun drawCross(canvas: Canvas, points: List<StrokePoint>, width: Float, paint: Paint) {
        paint.strokeWidth = (width * CROSS_LINE_WIDTH_FACTOR).coerceAtLeast(1f)
        val arm = width * CROSS_ARM_FACTOR
        val centers = Geometry.sampleAlongPolyline(points, width * CROSS_SPACING_FACTOR)
        for (c in centers) {
            canvas.drawLine(c.x - arm, c.y - arm, c.x + arm, c.y + arm, paint)
            canvas.drawLine(c.x - arm, c.y + arm, c.x + arm, c.y - arm, paint)
        }
    }

    /**
     * Pressure-modulated width: each segment is drawn round-capped at the width its
     * endpoints' mean pressure maps to, so overlapping caps blend the joints smooth.
     */
    private fun drawFountain(canvas: Canvas, points: List<StrokePoint>, width: Float, paint: Paint) {
        if (points.size == 1) {
            paint.strokeWidth = fountainWidth(width, points[0].pressure)
            canvas.drawPoint(points[0].x, points[0].y, paint)
            return
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            paint.strokeWidth = fountainWidth(width, (a.pressure + b.pressure) / 2f)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    private fun fountainWidth(base: Float, pressure: Float): Float =
        base * (FOUNTAIN_MIN + FOUNTAIN_RANGE * pressure.coerceIn(0f, 1f))

    private fun resetPaint(paint: Paint, color: Int, width: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun polylinePath(points: List<StrokePoint>): Path {
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    private fun withAlphaFactor(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
