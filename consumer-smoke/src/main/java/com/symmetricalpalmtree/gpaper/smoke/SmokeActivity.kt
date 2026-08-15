package com.symmetricalpalmtree.gpaper.smoke

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * Minimal real consumer: creates a paper surface through the public factory, wires a
 * listener, and draws full-screen. Touches enough of the host-facing API (factory,
 * tool, listener, stroke model, lifecycle) that a broken published artifact —
 * missing class, bad POM dependency, mangled metadata — fails this build or launch.
 */
class SmokeActivity : Activity() {

    private lateinit var paper: PaperView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paper = GPaper.create(this)
        paper.tool = Tool.PEN
        val status = TextView(this).apply {
            text = "engine: ${paper.engineId} — draw with the pen"
            setPadding(16, 16, 16, 16)
        }
        paper.setPaperListener(object : PaperListener {
            override fun onStrokeCommitted(stroke: Stroke) {
                status.text = "engine: ${paper.engineId} — ${paper.getStrokes().size} strokes"
            }
        })

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(paper.asView(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        paper.resumeDrawing()
    }

    override fun onDestroy() {
        paper.release()
        super.onDestroy()
    }
}
