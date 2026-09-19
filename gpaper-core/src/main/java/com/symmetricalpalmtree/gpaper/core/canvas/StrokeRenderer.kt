package com.symmetricalpalmtree.gpaper.core.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.symmetricalpalmtree.gpaper.core.geometry.Geometry
import com.symmetricalpalmtree.gpaper.core.geometry.GraphiteGrain
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle

/**
 * The committed (baked) appearance of every [StrokeStyle] — hand-rolled, portable Canvas
 * code with no SDK dependencies. This is the single source of truth for how a stroke looks
 * once baked: every engine (generic, Onyx, Ratta) renders committed strokes through here,
 * which is what makes stroke data portable across devices (see [StrokeStyle]).
 *
 * [StrokeStyle.PEN], [StrokeStyle.MARKER], [StrokeStyle.DASH], [StrokeStyle.CROSS] and
 * [StrokeStyle.FOUNTAIN] landed in Phase 2; [StrokeStyle.PENCIL] in Phase 10, where the
 * graphite it lays down is worked out by [GraphiteGrain] and drawn here.
 * [StrokeStyle.BRUSH] and [StrokeStyle.CALLIGRAPHY] still render as [StrokeStyle.PEN]
 * until their renderers land (documented incremental-rendering caveat).
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
     *
     * [pencilInk] is how this engine lays graphite (see [PencilInk]) — `null`, the normal
     * case, means the stroke's own colour, alpha-graded, at full density. It is read only by
     * [StrokeStyle.PENCIL].
     *
     * [seed] identifies this stroke to the styles whose appearance is textured rather than
     * geometric — today only [StrokeStyle.PENCIL]. It must be **stable for the life of the
     * stroke**: the same value while it is being drawn, when it is baked, after the host
     * reloads the page a week later, and through [
     * com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer]. Callers holding a
     * [com.symmetricalpalmtree.gpaper.core.model.Stroke] pass `id.hashCode()`; the live
     * preview passes the id its stroke is going to be committed with. Styles that ignore
     * it are free to be handed anything.
     */
    fun draw(
        canvas: Canvas,
        points: List<StrokePoint>,
        color: Int,
        width: Float,
        style: StrokeStyle,
        paint: Paint,
        seed: Int = 0,
        pencilInk: PencilInk? = null,
    ) {
        if (points.isEmpty()) return
        resetPaint(paint, color, width)
        when (style) {
            StrokeStyle.PEN,
            StrokeStyle.BRUSH,
            StrokeStyle.CALLIGRAPHY,
            -> drawPen(canvas, points, paint)

            StrokeStyle.PENCIL -> drawPencil(
                canvas, points, width, seed, paint,
                pencilInk ?: PencilInk(color, opaque = false, density = 1f),
            )
            StrokeStyle.MARKER -> drawMarker(canvas, points, color, width, paint)
            StrokeStyle.DASH -> drawDash(canvas, points, width, paint)
            StrokeStyle.CROSS -> drawCross(canvas, points, width, paint)
            StrokeStyle.FOUNTAIN -> drawFountain(canvas, points, width, paint)
        }
    }

    /**
     * Graphite. [GraphiteGrain] decides which specks of the paper's tooth caught the lead
     * and how dark each one is; all that is left here is to put them down.
     *
     * They go down as points rather than as circles: one `drawPoints` call carries every
     * fleck of a given darkness, so a whole stroke costs [GraphiteGrain.LEVELS] draw calls
     * however many thousand flecks it contains — which is the difference between a page of
     * pencil that re-renders in a frame and one that does not. (The Wacom Paintsprout app
     * learned the same lesson from the other side: its grain is meshes with per-vertex
     * colour because a `BlurMaskFilter` on a software canvas measured twice its single
     * largest per-frame cost.)
     *
     * Flecks composite normally, so a stroke crossing another darkens where they meet, the
     * way layered graphite does — nothing here has to arrange that.
     */
    private fun drawPencil(
        canvas: Canvas,
        points: List<StrokePoint>,
        width: Float,
        seed: Int,
        paint: Paint,
        ink: PencilInk,
    ) {
        val grain = GraphiteGrain.of(points, width, seed, density = ink.density)
        drawPencilFlecks(canvas, grain, 0, ink, width, paint)
    }

    /**
     * Put down the flecks of [grain] from index [from] onward — the bake's own rasteriser,
     * reachable on its own so a live preview lays **the same pixels the bake will**.
     *
     * The bake calls it with `from = 0` for a whole stroke; Phase 28's Supernote panel
     * preview calls it per batch with the count it has already laid, because
     * [GraphiteGrain.of] with `prefix = true` guarantees those earlier flecks are exactly
     * the ones already on the paper. One rasteriser, never two: a second one is a second
     * answer to "what does this mark look like", and the pen-lift handoff is the moment
     * the two would be compared.
     */
    fun drawPencilFlecks(
        canvas: Canvas,
        grain: GraphiteGrain.Grain,
        from: Int,
        ink: PencilInk,
        width: Float,
        paint: Paint,
    ) {
        if (from >= grain.count) return
        val color = ink.color
        val opaque = ink.opaque
        resetPaint(paint, color, width)
        paint.strokeCap = Paint.Cap.ROUND
        // Opaque flecks are also aliased: an anti-aliased edge is a ring of light greys, and
        // on the panel that ring trails the nib exactly as a grey fleck did.
        paint.isAntiAlias = !opaque
        val packed = FloatArray((grain.count - from) * 2)
        for (level in 0 until GraphiteGrain.LEVELS) {
            var n = 0
            for (i in from until grain.count) {
                if (grain.level[i] != level) continue
                packed[n++] = grain.xy[i * 2]
                packed[n++] = grain.xy[i * 2 + 1]
            }
            if (n == 0) continue
            // Darker flecks are bigger as well as darker — the pass per darkness is already
            // grouped, so this costs nothing and is what stops mid-tones chaining into bristle.
            // Capped at the lead's width, so a hairline lead bakes as the hairline it previewed as.
            paint.strokeWidth = GraphiteGrain.fleckPx(level, width)
            // Opaque flecks (Phase 28, Supernote): darkness from density and size alone.
            // A 16-grey e-ink panel reaches black on its first frame and a grey only by
            // passing through black, so a grey fleck trails the nib while a black one lands —
            // tone must come from how many flecks catch, never from what shade each is. On
            // that engine the shade the artist picked is [PencilInk.density]; here every
            // fleck is the one ink.
            paint.color = if (opaque) color else withAlphaFactor(color, GraphiteGrain.levelAlpha(level))
            canvas.drawPoints(packed, 0, n, paint)
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
