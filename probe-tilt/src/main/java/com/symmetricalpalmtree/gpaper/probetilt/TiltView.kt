package com.symmetricalpalmtree.gpaper.probetilt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** One digitizer sample, already converted to the units the CSV states. */
class Sample(
    val eventTimeMs: Long,
    val action: String,
    val tool: String,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val tiltDeg: Double,
    val orientDeg: Double,
    val distance: Float,
    val hover: Int,
)

/**
 * The whole page below the top bar. It records — the ink it draws is feedback for the
 * hand, nothing else reads it. Every sample of every event is handed to [sink] as one
 * batch, so the writer flushes once per event rather than once per sample.
 */
class TiltView(context: Context, private val sink: (List<Sample>, InputDevice?) -> Unit) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val marks = ArrayList<Pair<Path, Float>>()
    private var cur: Path? = null
    private var curW = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun clearInk() {
        marks.clear()
        cur = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        handle(event, hover = false)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        handle(event, hover = true)
        return true
    }

    private fun handle(event: MotionEvent, hover: Boolean) {
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        val toolName = if (tool == MotionEvent.TOOL_TYPE_ERASER) "ERASER" else "STYLUS"
        val action = MotionEvent.actionToString(event.actionMasked)
        val isHover = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT -> 1
            else -> 0
        }
        val out = ArrayList<Sample>(event.historySize + 1)
        // Oldest first: the history, then the current sample.
        for (h in 0 until event.historySize) {
            out += Sample(
                eventTimeMs = event.getHistoricalEventTime(h),
                action = action,
                tool = toolName,
                x = event.getHistoricalX(0, h),
                y = event.getHistoricalY(0, h),
                pressure = event.getHistoricalPressure(0, h),
                size = event.getHistoricalSize(0, h),
                tiltDeg = Math.toDegrees(event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, h).toDouble()),
                orientDeg = Math.toDegrees(event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, 0, h).toDouble()),
                distance = event.getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, 0, h),
                hover = isHover,
            )
        }
        out += Sample(
            eventTimeMs = event.eventTime,
            action = action,
            tool = toolName,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            size = event.size,
            tiltDeg = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT).toDouble()),
            orientDeg = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_ORIENTATION).toDouble()),
            distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE),
            hover = isHover,
        )
        if (!hover && isHover == 0) drawInk(event.actionMasked, out)
        sink(out, event.device)
        invalidate()
    }

    /** Crude feedback only: the line widens past 50°, so the hand can see the lean take. */
    private fun drawInk(action: Int, samples: List<Sample>) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val s = samples.last()
                curW = widthFor(s.tiltDeg)
                cur = Path().apply { moveTo(s.x, s.y) }
                marks += cur!! to curW
                lastX = s.x
                lastY = s.y
            }
            MotionEvent.ACTION_MOVE -> {
                for (s in samples) {
                    val w = widthFor(s.tiltDeg)
                    if (cur == null || abs(w - curW) > 1f) {
                        curW = w
                        cur = Path().apply { moveTo(lastX, lastY) }
                        marks += cur!! to curW
                    }
                    cur?.lineTo(s.x, s.y)
                    lastX = s.x
                    lastY = s.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cur = null
        }
    }

    private fun widthFor(tiltDeg: Double): Float {
        val t = ((tiltDeg - 50.0) / 40.0).coerceIn(0.0, 1.0)
        return (2.0 + 40.0 * t).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        for ((path, w) in marks) {
            paint.strokeWidth = w
            canvas.drawPath(path, paint)
        }
    }
}
