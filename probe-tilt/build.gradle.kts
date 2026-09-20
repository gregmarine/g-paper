plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Tilt probe — a from-scratch, dependency-free app that records what the Supernote
// (Ratta) stylus reports per sample: tilt, orientation, pressure, distance, tool type.
// It depends on nothing in g-paper on purpose: every number is the digitizer's, not
// ours. The CSV it writes is the product; the ink it draws is only feedback for the
// hand, so a threshold angle for "the lead is on its side" can be fitted offline.
android {
    namespace = "com.symmetricalpalmtree.gpaper.probetilt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.probetilt"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
