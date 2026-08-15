package com.symmetricalpalmtree.gpaper.core

/** Phase of a [RawInputEvent]. */
enum class RawAction { DOWN, MOVE, UP, CANCEL, HOVER }

/** Which end of the stylus produced a [RawInputEvent]. */
enum class RawTool { STYLUS, STYLUS_ERASER }

/**
 * One stylus sample delivered to the host's [RawInputListener], independent of the
 * active [Tool]. Plain data, not a `MotionEvent`: on BOOX the ink path runs through the
 * SDK's raw-drawing pipeline and never produces `MotionEvent`s, so a common shape is
 * synthesized from whatever channel the engine actually captures.
 *
 * Field conventions match [com.symmetricalpalmtree.gpaper.core.model.StrokePoint]
 * (paper-coordinate px, pressure `0..1`, tilt radians, monotonic event time).
 */
data class RawInputEvent(
    val action: RawAction,
    val tool: RawTool,
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val timeMillis: Long = 0L,
)

/**
 * Raw stylus passthrough for hosts that want the pen stream itself (handwriting
 * recognition, custom gestures, instrumentation). Purely observational: consuming is
 * not possible and the component's own handling is unaffected.
 *
 * Delivered on the main thread, in order, at input rate — keep handlers allocation-free
 * and fast, and hand heavy work off the thread. Delivery is best-effort per engine:
 * every engine delivers DOWN/MOVE/UP for contacts it captures; HOVER and CANCEL fidelity
 * varies by hardware pipeline.
 */
fun interface RawInputListener {
    fun onRawInput(event: RawInputEvent)
}
