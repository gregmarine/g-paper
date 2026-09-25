plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Launcher demo — a dependency-free HOME app for the Supernote that owns the right side
// bar's swipe-down: the firmware side menu is held shut through the launcher's exported
// GestureService binder, the bar's key stream is read directly, swipe up is told apart by
// the firmware's own refresh broadcast, and swipe down opens this app's menu instead.
// Set as home with `pm set-home-activity`; restore with the same command pointing at
// com.ratta.supernote.background/.MainActivity.
android {
    namespace = "com.symmetricalpalmtree.gpaper.launcherdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.launcherdemo"
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
