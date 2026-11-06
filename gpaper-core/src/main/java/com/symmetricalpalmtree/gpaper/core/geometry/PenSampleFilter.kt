package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import kotlin.math.abs
import kotlin.math.hypot

/** Least distance, in view px (the engine's capture coordinates), a pen sample must move to be recorded. */
const val PEN_SAMPLE_MIN_DISTANCE_PX: Float = 1f

/** Least pressure change (absolute, 0..1 scale) that records a pen sample that did not move. */
const val PEN_SAMPLE_MIN_PRESSURE_DELTA: Float = 0.03f

/**
 * Whether [next] differs enough from [last], the last *recorded* sample, to be worth recording.
 *
 * The digitizer reports about 480 Hz, so a pen held still or crawling reports hundreds of
 * samples that did not move: they add bytes to every stroke and nothing visible. A sample is
 * recorded when it moved at least [PEN_SAMPLE_MIN_DISTANCE_PX] or its pressure changed by at
 * least [PEN_SAMPLE_MIN_PRESSURE_DELTA]. Tilt, azimuth and time do not count.
 *
 * Callers must also always record the first sample of a contact and its last one (pen-up).
 */
fun isPenSampleWorthRecording(last: StrokePoint, next: StrokePoint): Boolean =
    hypot(next.x - last.x, next.y - last.y) >= PEN_SAMPLE_MIN_DISTANCE_PX ||
        abs(next.pressure - last.pressure) >= PEN_SAMPLE_MIN_PRESSURE_DELTA

/**
 * [points] reduced to the samples worth recording (see [isPenSampleWorthRecording]): the first
 * and the last are always kept, the rest only when they differ enough from the last kept one.
 * Kept samples are the originals, untouched.
 */
fun filterPenSamples(points: List<StrokePoint>): List<StrokePoint> {
    if (points.size < 2) return points
    val out = ArrayList<StrokePoint>(points.size)
    out.add(points[0])
    for (i in 1 until points.size - 1) {
        if (isPenSampleWorthRecording(out.last(), points[i])) out.add(points[i])
    }
    out.add(points.last())
    return out
}
