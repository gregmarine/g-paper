package com.symmetricalpalmtree.gpaper.core.geometry

import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import kotlin.math.abs
import kotlin.math.hypot

/** Least distance, in view px (the engine's capture coordinates), a pen sample must move to be recorded. */
const val PEN_SAMPLE_MIN_DISTANCE_PX: Float = 1f

/** Lower bound of the pressure step: below about this the digitizer's pressure is sensor noise. */
const val PEN_SAMPLE_MIN_PRESSURE_STEP: Float = 0.03f

/**
 * Upper bound of the pressure step, below the ~0.15 step people can deliberately control; the
 * headroom leaves room for a stroke later enlarged, whose width then shows finer steps.
 */
const val PEN_SAMPLE_MAX_PRESSURE_STEP: Float = 0.1f

/** The width change, in px, below which a pressure change is invisible: half a pixel. */
const val PEN_SAMPLE_HALF_PIXEL: Float = 0.5f

/** FOUNTAIN draws width = base * (0.35 + 1.05 * pressure); this is the 1.05, as in `StrokeRenderer`. */
private const val FOUNTAIN_PRESSURE_RANGE: Float = 1.05f

/**
 * The pressure change, 0..1, that records a pen sample that did not move, for one stroke.
 *
 * People control only about 6 pressure levels, so a pressure step finer than what changes the
 * drawn width by about half a pixel is invisible tremor and sensor noise. Only [StrokeStyle.FOUNTAIN]
 * draws its width from pressure (`base * (0.35 + 1.05 * pressure)`), so its step is
 * [PEN_SAMPLE_HALF_PIXEL] / (1.05 * [strokeWidthPx]), clamped to
 * [PEN_SAMPLE_MIN_PRESSURE_STEP]..[PEN_SAMPLE_MAX_PRESSURE_STEP]. Every other style ignores
 * pressure (PENCIL uses it only for darkness), so it gets the cap. [strokeWidthPx] is the width the
 * engine draws the live stroke with, in the same px the samples' movement is measured in.
 */
fun penSamplePressureStep(style: StrokeStyle, strokeWidthPx: Float): Float {
    if (style != StrokeStyle.FOUNTAIN || !(strokeWidthPx > 0f)) return PEN_SAMPLE_MAX_PRESSURE_STEP
    val halfPixelStep = PEN_SAMPLE_HALF_PIXEL / (FOUNTAIN_PRESSURE_RANGE * strokeWidthPx)
    return halfPixelStep.coerceIn(PEN_SAMPLE_MIN_PRESSURE_STEP, PEN_SAMPLE_MAX_PRESSURE_STEP)
}

/**
 * Whether [next] differs enough from [last], the last *recorded* sample, to be worth recording.
 *
 * The digitizer reports about 480 Hz, so a pen held still or crawling reports hundreds of
 * samples that did not move: they add bytes to every stroke and nothing visible. A sample is
 * recorded when it moved at least [PEN_SAMPLE_MIN_DISTANCE_PX] or its pressure differs from
 * [last]'s by at least [pressureStep] (see [penSamplePressureStep]). Tilt, azimuth and time do
 * not count.
 *
 * Callers must also always record the first sample of a contact and its last one (pen-up).
 */
fun isPenSampleWorthRecording(last: StrokePoint, next: StrokePoint, pressureStep: Float): Boolean =
    hypot(next.x - last.x, next.y - last.y) >= PEN_SAMPLE_MIN_DISTANCE_PX ||
        abs(next.pressure - last.pressure) >= pressureStep

/**
 * [points] reduced to the samples worth recording (see [isPenSampleWorthRecording]): the first
 * and the last are always kept, the rest only when they differ enough from the last kept one.
 * Kept samples are the originals, untouched.
 */
fun filterPenSamples(points: List<StrokePoint>, pressureStep: Float): List<StrokePoint> {
    if (points.size < 2) return points
    val out = ArrayList<StrokePoint>(points.size)
    out.add(points[0])
    for (i in 1 until points.size - 1) {
        if (isPenSampleWorthRecording(out.last(), points[i], pressureStep)) out.add(points[i])
    }
    out.add(points.last())
    return out
}
