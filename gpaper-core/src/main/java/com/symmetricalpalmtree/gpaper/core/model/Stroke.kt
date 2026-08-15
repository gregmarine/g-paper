package com.symmetricalpalmtree.gpaper.core.model

/**
 * One captured stylus sample.
 *
 * Plain data with no Android dependency: the host persists these however it likes
 * (g-paper has no serialization opinions).
 *
 * @property x Horizontal position in paper coordinates (px).
 * @property y Vertical position in paper coordinates (px).
 * @property pressure Normalized stylus pressure, nominally `0..1` (`MotionEvent.getPressure`
 *   convention). `1.0` when the hardware reports none — rendering may ignore it.
 * @property tilt Stylus tilt in radians from vertical, `0..π/2` (`MotionEvent.AXIS_TILT`
 *   convention). `0.0` when the hardware reports none.
 * @property timeMillis Monotonic event time in milliseconds (`MotionEvent.getEventTime`
 *   timebase — milliseconds since boot, **not** wall-clock). `0` when unknown. Useful for
 *   velocity/replay; never interpret as a calendar date.
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val timeMillis: Long = 0L,
)

/**
 * One committed stroke: an ordered polyline of [StrokePoint]s plus its style.
 *
 * Plain Kotlin data, no Android types, no serialization opinions — the host owns
 * persistence. The [id] is the stable handle every g-paper callback uses
 * (erase, selection, move), so the host should key its own storage by it.
 * Freshly captured strokes get a random UUID from the engine; strokes loaded by the
 * host keep whatever ids the host assigned.
 *
 * @property id Stable unique id. Must be unique within the strokes currently loaded.
 * @property points Ordered samples in paper coordinates. A tap may produce a single point.
 * @property color ARGB color int (`0xAARRGGBB`), as in `android.graphics.Color`.
 * @property width Stroke width in px at pressure 1.0.
 * @property style Abstract pen type this stroke renders with (see [StrokeStyle] for the
 *   portability contract and per-engine live-ink mapping).
 */
data class Stroke(
    val id: String,
    val points: List<StrokePoint>,
    val color: Int = BLACK,
    val width: Float = DEFAULT_WIDTH,
    val style: StrokeStyle = StrokeStyle.PEN,
) {
    /**
     * Tight axis-aligned bounding box of [points], computed eagerly at construction for
     * O(1) broad-phase rejection during eraser/lasso hit tests. Excluded from [equals]/
     * [copy] mechanics by being a body property; always consistent with [points].
     */
    val bounds: Bounds = Bounds.of(points)

    /**
     * Copy of this stroke with every point (and its bounds) shifted by ([dx], [dy]).
     * Pressure, tilt, timestamps, color, and width ride along untouched. Used by
     * drag-move and by hosts implementing paste/duplicate.
     */
    fun translated(dx: Float, dy: Float, newId: String = id): Stroke =
        copy(id = newId, points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })

    companion object {
        /** Opaque black (0xFF000000). */
        const val BLACK: Int = -0x1000000

        /** Default stroke width in px. */
        const val DEFAULT_WIDTH: Float = 3f
    }
}
