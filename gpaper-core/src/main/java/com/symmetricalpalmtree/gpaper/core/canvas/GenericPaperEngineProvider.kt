package com.symmetricalpalmtree.gpaper.core.canvas

import android.content.Context
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.engine.PaperEngineProvider

/**
 * The built-in generic Canvas engine's provider. Never registered by hosts: [GPaper]
 * installs it lazily through its internal factory hook, so a core-only app needs no
 * registration call and device engines simply out-prioritize it.
 */
internal class GenericPaperEngineProvider : PaperEngineProvider {

    override val id: String = GPaper.ENGINE_GENERIC

    override val priority: Int = GPaper.PRIORITY_GENERIC

    /** The always-available fallback: plain Canvas rendering runs anywhere. */
    override fun isAvailable(context: Context): Boolean = true

    override fun create(context: Context): PaperView = CanvasPaperView(context)
}
