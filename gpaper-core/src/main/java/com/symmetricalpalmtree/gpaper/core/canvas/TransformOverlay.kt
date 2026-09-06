package com.symmetricalpalmtree.gpaper.core.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.symmetricalpalmtree.gpaper.core.geometry.TransformGeometry
import com.symmetricalpalmtree.gpaper.core.geometry.TransformGrab
import com.symmetricalpalmtree.gpaper.core.model.OrientedBox
import kotlin.math.roundToInt

/**
 * The transform mode's chrome (0.1.27): the oriented dashed box, eight square handles,
 * and the rotate knob on a stem above the top edge. Drawn on the selection layer, so
 * an EPD sees the handles the way it sees the selection box. Black on white only —
 * chrome, and the same weight as the lasso box.
 *
 * Handles and the knob are drawn axis-aligned whatever the box's rotation (a square
 * that turns with the box reads as part of the shape, not as a grip). Their outlines
 * are `round(density)` px on integer edges — at a fractional density a 1 dp hairline
 * is a coin flip on e-paper.
 */
internal class TransformOverlay(density: Float) {

    /** Metrics in px, from the reference dp values. */
    val handleTouchRadius = HANDLE_TOUCH_DP * density
    val knobOffset = KNOB_OFFSET_DP * density
    val knobTouchRadius = KNOB_TOUCH_DP * density
    private val handleHalf = (HANDLE_DP * density / 2f).roundToInt().coerceAtLeast(2)
    private val knobRadius = (KNOB_DP * density / 2f).roundToInt().coerceAtLeast(3)
    private val hairline = density.roundToInt().coerceAtLeast(1).toFloat()

    private val fill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        isAntiAlias = false
    }
    private val outline = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = hairline
        isAntiAlias = false
    }
    private val stem = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = hairline
        isAntiAlias = false
    }
    private val path = Path()

    /** Draw the overlay for [box] with the caller's dashed [boxPaint] (the lasso box's). */
    fun draw(canvas: Canvas, box: OrientedBox, boxPaint: Paint) {
        // The oriented box: four corners, closed.
        val c = box.corners()
        path.rewind()
        path.moveTo(c[0].first, c[0].second)
        for (i in 1 until 4) path.lineTo(c[i].first, c[i].second)
        path.close()
        canvas.drawPath(path, boxPaint)

        // Stem from the top edge's midpoint to the knob, then the knob itself.
        val (tx, ty) = TransformGeometry.grabPoint(box, TransformGrab.N)
        val (kx, ky) = TransformGeometry.grabPoint(box, TransformGrab.ROTATE, knobOffset)
        canvas.drawLine(tx, ty, kx, ky, stem)
        canvas.drawCircle(kx, ky, knobRadius.toFloat(), fill)
        canvas.drawCircle(kx, ky, knobRadius.toFloat(), outline)

        // Handles: filled squares on integer edges, inset by half the hairline so the
        // stroke lands on whole pixels.
        val inset = hairline / 2f
        for (g in TransformGrab.HANDLES) {
            val (hx, hy) = TransformGeometry.grabPoint(box, g)
            val l = (hx.roundToInt() - handleHalf).toFloat()
            val t = (hy.roundToInt() - handleHalf).toFloat()
            val r = l + handleHalf * 2
            val b = t + handleHalf * 2
            canvas.drawRect(l, t, r, b, fill)
            canvas.drawRect(l + inset, t + inset, r - inset, b - inset, outline)
        }
    }

    companion object {
        /** Handle square side, dp. */
        const val HANDLE_DP = 10f
        /** Handle grab radius, dp — a fingertip, not a square. */
        const val HANDLE_TOUCH_DP = 22f
        /** Knob centre above the top edge, dp. */
        const val KNOB_OFFSET_DP = 36f
        /** Knob diameter, dp. */
        const val KNOB_DP = 14f
        /** Knob grab radius, dp. */
        const val KNOB_TOUCH_DP = 22f
    }
}
