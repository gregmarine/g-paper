package com.symmetricalpalmtree.gpaper.core.engine

import android.content.Context
import com.symmetricalpalmtree.gpaper.core.PaperView

/**
 * One selectable drawing engine. Core ships the generic Canvas engine; device modules
 * (`gpaper-onyx`, `gpaper-ratta`) each ship one provider and register it with [GPaper]
 * — core never depends on them.
 *
 * Engine choice happens once, at [PaperView] creation. There is no runtime fallback: a
 * device-engine failure after construction must be loud (log + degrade visibly), never
 * silently swapped for another engine.
 */
interface PaperEngineProvider {

    /** Stable engine id (`"generic"`, `"onyx"`, `"ratta"`), usable as an explicit override. */
    val id: String

    /**
     * Selection precedence when multiple registered engines report available — highest
     * wins. Device engines use [GPaper.PRIORITY_DEVICE]; the generic fallback is
     * [GPaper.PRIORITY_GENERIC].
     */
    val priority: Int

    /**
     * True when this engine can run on the current device (manufacturer/SDK probes).
     * Called at creation time only; must be fast and side-effect-free.
     */
    fun isAvailable(context: Context): Boolean

    /** Create a fresh [PaperView] backed by this engine. */
    fun create(context: Context): PaperView
}
