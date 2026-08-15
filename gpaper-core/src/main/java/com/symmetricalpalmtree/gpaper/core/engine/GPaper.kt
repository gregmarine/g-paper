package com.symmetricalpalmtree.gpaper.core.engine

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.PaperView

/**
 * Engine registry and [PaperView] factory — the single engine-selection point.
 *
 * Registration is **explicit** (no ServiceLoader, no reflection): each device module
 * exposes a one-line `register()` the host calls once at startup, e.g.
 *
 * ```kotlin
 * // Application.onCreate — only for the device modules the app ships
 * OnyxEngine.register()
 * RattaEngine.register()
 *
 * // wherever the surface is embedded
 * val paper = GPaper.create(context)          // auto-select best available
 * val paper = GPaper.create(context, "onyx")  // explicit override
 * ```
 *
 * The generic engine registers itself automatically the first time [create] or
 * [registeredEngines] runs — a core-only app needs no registration call at all.
 */
object GPaper {

    private const val TAG = "GPaper"

    /** [PaperEngineProvider.id] of the built-in generic Canvas engine. */
    const val ENGINE_GENERIC = "generic"

    /** Priority of the generic always-available fallback. */
    const val PRIORITY_GENERIC = 0

    /** Priority device engines register at (any value above the generic works). */
    const val PRIORITY_DEVICE = 100

    private val providers = LinkedHashMap<String, PaperEngineProvider>()

    /**
     * Internal hook: core's generic engine installs its provider factory here (Phase 2).
     * Kept as a lambda so this registry stays free of view/engine imports.
     */
    internal var genericProviderFactory: (() -> PaperEngineProvider)? = null

    /**
     * Register (or replace, keyed by [PaperEngineProvider.id]) an engine. Safe to call
     * from any thread; typically once from `Application.onCreate`.
     */
    @Synchronized
    fun registerEngine(provider: PaperEngineProvider) {
        providers[provider.id] = provider
    }

    /** All registered engines, highest priority first. */
    @Synchronized
    fun registeredEngines(): List<PaperEngineProvider> {
        ensureGenericRegistered()
        return providers.values.sortedByDescending { it.priority }
    }

    /**
     * Create a [PaperView].
     *
     * With [engineId] null: the highest-priority registered engine whose
     * [PaperEngineProvider.isAvailable] returns true. With [engineId] set: that exact
     * engine, **bypassing** its availability probe — an explicit override means the
     * host knows better (dev tooling, forced-generic mode).
     *
     * The chosen engine is logged at `Log.i` once per creation. Throws
     * [IllegalStateException] if no engine matches — never a silent fallback.
     */
    fun create(context: Context, engineId: String? = null): PaperView {
        val provider = selectProvider(context, engineId)
        Log.i(TAG, "Creating PaperView with engine '${provider.id}'")
        return provider.create(context)
    }

    @Synchronized
    private fun selectProvider(context: Context, engineId: String?): PaperEngineProvider {
        ensureGenericRegistered()
        if (engineId != null) {
            return providers[engineId]
                ?: throw IllegalStateException(
                    "No engine registered with id '$engineId' " +
                        "(registered: ${providers.keys}). Did the module's register() run?"
                )
        }
        return providers.values
            .sortedByDescending { it.priority }
            .firstOrNull { it.isAvailable(context) }
            ?: throw IllegalStateException(
                "No available engine (registered: ${providers.keys})."
            )
    }

    private fun ensureGenericRegistered() {
        if (!providers.containsKey(ENGINE_GENERIC)) {
            genericProviderFactory?.let { providers[ENGINE_GENERIC] = it() }
        }
    }
}
