package com.symmetricalpalmtree.gpaper.smoke

import android.app.Application
import com.symmetricalpalmtree.gpaper.onyx.OnyxEngine
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

/** The one-call-per-shipped-module registration the integration story promises. */
class SmokeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OnyxEngine.register(this)
        RattaEngine.register()
    }
}
