package com.symmetricalpalmtree.gpaper.demo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Phase 0 shell. Replaced with a real paper surface + controls in Phase 2.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "g-paper demo shell"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
        })
    }
}
