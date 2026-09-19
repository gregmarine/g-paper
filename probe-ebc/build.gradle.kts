plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// EBC probe — a from-scratch, dependency-free app that opens Supernote's panel driver
// (/dev/ebc) from an ordinary (untrusted_app) process and drives raw ioctls by hand.
// It depends on nothing in g-paper on purpose: every result is the driver's, not ours.
android {
    namespace = "com.symmetricalpalmtree.gpaper.probe"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
