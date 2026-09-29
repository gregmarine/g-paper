plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Seam probe, hub half (2026-09-28) — an app that owns one SQLCipher database and serves
// it to other apps over a binder guarded by a signature permission and a caller
// certificate check. Measures whether a notebook could live across such a seam.
android {
    namespace = "com.symmetricalpalmtree.gpaper.probeseam.hub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.probeseam.hub"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")
}
