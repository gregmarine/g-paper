package com.symmetricalpalmtree.gpaper.demo

import android.app.Application
import com.symmetricalpalmtree.gpaper.onyx.OnyxEngine

/**
 * Registers the device engine modules this demo ships. Per the g-paper contract this
 * runs in `Application.onCreate` — on BOOX, [OnyxEngine.register] also installs the
 * SDK's hidden-API bypass and heals any EPD state leaked by a killed pen session.
 */
class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OnyxEngine.register(this)
        // RattaEngine.register() lands in Phase 4.
    }
}
