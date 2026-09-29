package com.symmetricalpalmtree.gpaper.probeseam.app

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine

class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
    }
}
