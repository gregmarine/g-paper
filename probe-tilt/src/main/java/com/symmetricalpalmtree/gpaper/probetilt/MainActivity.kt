package com.symmetricalpalmtree.gpaper.probetilt

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A hand-driven probe of what the Supernote (Ratta) stylus reports per sample.
 *
 * One pass at a time, named on the top bar: the person writes the pass the label asks
 * for, taps Next pass, and writes the next. Every stylus sample — the history samples
 * of a MOVE included, oldest first — becomes one CSV row under
 * `getExternalFilesDir(null)/tilt-<stamp>.csv`, tagged with the pass it was made in, so
 * a threshold angle for "the lead is on its side" can be fitted offline.
 *
 * The ink on screen is feedback for the hand, never the product.
 */
class MainActivity : Activity() {
    private companion object {
        const val TAG = "TiltProbe"
        val PASSES = listOf("upright", "writing", "shading", "flat", "free")
    }

    private var passIndex = 0
    private var seq = 0L
    private var deviceLogged = false
    private var writer: BufferedWriter? = null

    private lateinit var passLabel: TextView
    private lateinit var readout: TextView
    private lateinit var view: TiltView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val csv = File(getExternalFilesDir(null), "tilt-$stamp.csv")
        writer = runCatching { csv.bufferedWriter() }
            .onFailure { Log.e(TAG, "cannot open $csv", it) }
            .getOrNull()
        line("# device=${Build.MODEL} serial-free")
        line("pass,seq,eventTimeMs,action,tool,x,y,pressure,size,tiltDeg,orientDeg,distance,hover")
        flush()
        Log.i(TAG, "writing $csv")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        passLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val next = Button(this).apply {
            text = "Next pass"
            setOnClickListener {
                passIndex = (passIndex + 1) % PASSES.size
                view.clearInk()
                showPass()
            }
        }
        top.addView(passLabel)
        top.addView(next)
        readout = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.BLACK)
            text = "waiting for the pen"
        }
        bar.addView(top)
        bar.addView(readout)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        view = TiltView(this) { samples, device -> record(samples, device) }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showPass()
    }

    private fun showPass() {
        passLabel.text = PASSES[passIndex]
    }

    private fun record(samples: List<Sample>, device: InputDevice?) {
        if (!deviceLogged && device != null) {
            deviceLogged = true
            val hasTilt = device.getMotionRange(MotionEvent.AXIS_TILT) != null
            val hasOrientation = device.getMotionRange(MotionEvent.AXIS_ORIENTATION) != null
            line("# device.sources=${device.sources} hasTilt=$hasTilt hasOrientation=$hasOrientation")
        }
        val pass = PASSES[passIndex]
        for (s in samples) {
            seq++
            val row = "$pass,$seq,${s.eventTimeMs},${s.action},${s.tool}," +
                "${f(s.x)},${f(s.y)},${f(s.pressure)},${f(s.size)}," +
                "${f(s.tiltDeg)},${f(s.orientDeg)},${f(s.distance)},${s.hover}"
            line(row)
            if (seq % 10L == 0L) Log.i(TAG, row)
        }
        flush()
        val s = samples.last()
        readout.text = "tilt ${f(s.tiltDeg)}°  orient ${f(s.orientDeg)}°  " +
            "p ${f(s.pressure)}  dist ${f(s.distance)}  tool ${s.tool}"
    }

    private fun f(v: Float) = String.format(Locale.US, "%.3f", v)
    private fun f(v: Double) = String.format(Locale.US, "%.3f", v)

    private fun line(s: String) {
        val w = writer ?: return
        runCatching { w.write(s); w.newLine() }
    }

    private fun flush() {
        runCatching { writer?.flush() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }
}
