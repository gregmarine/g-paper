plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Seam probe, feature-app half (2026-09-28) — binds the hub and times page loads, saves
// and raster transfers across the seam. The `stranger` flavour is the same app signed
// with another key: the hub must refuse it.
android {
    namespace = "com.symmetricalpalmtree.gpaper.probeseam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.probeseam.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("stranger") {
            storeFile = file("stranger.keystore")
            storePassword = "stranger"
            keyAlias = "stranger"
            keyPassword = "stranger"
        }
    }

    flavorDimensions += "signer"
    productFlavors {
        create("ours") { dimension = "signer" }
        create("stranger") {
            dimension = "signer"
            applicationIdSuffix = ".stranger"
            signingConfig = signingConfigs.getByName("stranger")
        }
    }
    buildTypes {
        // Release so the stranger flavour's own key is the one that signs; nothing is minified.
        release { isMinifyEnabled = false; signingConfig = null }
    }

    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The real engine, so a page flip here looks and costs what it would in a notebook app.
    implementation(project(":gpaper-core"))
    implementation(project(":gpaper-ratta"))
}
