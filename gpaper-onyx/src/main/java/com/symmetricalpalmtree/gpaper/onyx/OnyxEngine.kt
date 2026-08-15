package com.symmetricalpalmtree.gpaper.onyx

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.engine.PaperEngineProvider
import java.util.Locale
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** True on BOOX hardware (`Build.MANUFACTURER` contains "onyx"). */
internal fun isOnyxDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")

/**
 * Host entry point for the BOOX engine. One call from `Application.onCreate`:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         OnyxEngine.register(this)
 *     }
 * }
 * ```
 *
 * [register] does three things (the two device-side ones only on BOOX hardware):
 *
 * 1. **HiddenApiBypass install.** The Onyx SDK bootstraps itself through hidden system
 *    APIs (`VMRuntime`, `RawInputManager`); Android 14+ blocks that, so the bypass must
 *    be installed before any SDK code runs. Wrapped here — hosts need no direct
 *    dependency and no extra call.
 * 2. **Leaked EPD-state healing.** A live pen session registers an app-scope
 *    handwriting-waveform pin and a shortened auto-refresh list with the EPD service,
 *    keyed by *name*, not process. A process killed mid-session (crash, system kill,
 *    `adb install -r` over a live session) leaves them registered with nobody to clear
 *    them — the whole panel ghosts in every app until reboot. Clearing at every process
 *    start heals that; it no-ops when nothing leaked. This is why registration takes the
 *    [Application] and must run from `Application.onCreate`, not later.
 * 3. **Engine registration** with [GPaper] at [GPaper.PRIORITY_DEVICE]. The engine's
 *    availability probe matches BOOX hardware, so `GPaper.create(context)` picks it
 *    there and falls through to other engines elsewhere. Registering on a non-BOOX
 *    device is harmless.
 *
 * Build requirements for apps shipping this module: the BOOX maven repo
 * (`http://repo.boox.com/repository/maven-public/`, insecure protocol allowed) and
 * `android.enableJetifier=true`. Generic-only consumers need neither.
 */
object OnyxEngine {

    /** [PaperEngineProvider.id] of this engine — also usable as an explicit override. */
    const val ENGINE_ID = "onyx"

    private const val TAG = "GPaperOnyx"

    /**
     * Install the BOOX prerequisites and register the engine. Call once from
     * `Application.onCreate`. Safe on any device; safe to call more than once.
     */
    fun register(application: Application) {
        if (isOnyxDevice()) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                Log.w(TAG, "HiddenApiBypass install failed — the Onyx SDK may not bootstrap", t)
            }
            try {
                com.onyx.android.sdk.api.device.epd.EpdController.clearAppScopeUpdate()
                com.onyx.android.sdk.api.device.epd.EpdController.resetUpdListSize()
            } catch (t: Throwable) {
                Log.w(TAG, "EPD leak heal failed", t)
            }
        }
        GPaper.registerEngine(OnyxPaperEngineProvider())
    }
}

/** The BOOX engine's [PaperEngineProvider] — registered via [OnyxEngine.register]. */
internal class OnyxPaperEngineProvider : PaperEngineProvider {

    override val id: String = OnyxEngine.ENGINE_ID

    override val priority: Int = GPaper.PRIORITY_DEVICE

    override fun isAvailable(context: Context): Boolean = isOnyxDevice()

    override fun create(context: Context): PaperView = OnyxPaperView(context)
}
