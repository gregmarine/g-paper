plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Slide probe — a from-scratch, dependency-free app that shows what an ordinary app can
// do with the Supernote's side bars (survey 2026-09-24): (1) the bar's key stream
// (KEYCODE_F20/F21 = 300/301 and F29/F30 = 309/310, plus DRAG 290 / 291 / SLIDE 292 /
// MENU 82) reaches the focused activity's dispatchKeyEvent after the framework has
// forwarded it to Ratta's launcher; (2) the launcher's side menu and pull-down status
// bar can be locked from an app by an unprotected broadcast, or by binding the exported
// GestureService and calling lockSlidebar / lockStatusbar on its binder.
android {
    namespace = "com.symmetricalpalmtree.gpaper.probeslide"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.probeslide"
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
