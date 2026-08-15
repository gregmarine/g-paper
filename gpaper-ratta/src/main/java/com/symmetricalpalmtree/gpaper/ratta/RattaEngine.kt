package com.symmetricalpalmtree.gpaper.ratta

import android.content.Context
import android.os.Build
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.engine.PaperEngineProvider
import java.util.Locale

/**
 * True on Supernote hardware. `Build.MANUFACTURER` is `"Supernote"` on the verified
 * fleet — never `"ratta"`, which appears nowhere in the build props. (The Manta even
 * reports itself as a Nomad in every `ro.product.*` property; manufacturer is the only
 * stable marker.)
 */
internal fun isRattaDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("supernote")

/**
 * Host entry point for the Supernote (Ratta) engine. One call from
 * `Application.onCreate` (or any point before the first [GPaper.create]):
 *
 * ```kotlin
 * RattaEngine.register()
 * ```
 *
 * Unlike the BOOX module there are no SDK prerequisites — the engine talks to the
 * firmware's ink daemon directly over Binder, so this module adds **zero**
 * dependencies, no extra maven repo, and no jetifier to a consuming app.
 *
 * The availability probe requires both Supernote hardware and a reachable firmware ink
 * binder; `GPaper.create(context)` picks the engine there and falls through elsewhere.
 * Registering on any other device is harmless.
 */
object RattaEngine {

    /** [PaperEngineProvider.id] of this engine — also usable as an explicit override. */
    const val ENGINE_ID = "ratta"

    /** Register the engine with [GPaper]. Safe on any device; safe to call repeatedly. */
    fun register() {
        GPaper.registerEngine(RattaPaperEngineProvider())
    }
}

/** The Supernote engine's [PaperEngineProvider] — registered via [RattaEngine.register]. */
internal class RattaPaperEngineProvider : PaperEngineProvider {

    override val id: String = RattaEngine.ENGINE_ID

    override val priority: Int = GPaper.PRIORITY_DEVICE

    override fun isAvailable(context: Context): Boolean =
        isRattaDevice() && SupernoteInk.isAvailable()

    override fun create(context: Context): PaperView = RattaPaperView(context)
}
