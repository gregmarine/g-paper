// Single-module consumer app (the root project IS the app — no submodule ceremony).
// Mirrors what the integration guide asks of a real host: mavenLocal artifacts, and —
// because it ships gpaper-onyx — the BOOX repo, jetifier, the abi filter/pickFirst
// packaging, and the manifest label override.
plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "com.symmetricalpalmtree.gpaper.smoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.smoke"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 64-bit ARM only — also drops the Onyx SDK's non-16KB-aligned x86_64 libs.
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            // The Onyx SDK ships libc++_shared.so copies that collide across its AARs.
            pickFirsts += setOf(
                "lib/arm64-v8a/libc++_shared.so",
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val gpaperVersion = "0.1.3"
    implementation("com.symmetricalpalmtree.gpaper:gpaper-core:$gpaperVersion")
    implementation("com.symmetricalpalmtree.gpaper:gpaper-onyx:$gpaperVersion")
    implementation("com.symmetricalpalmtree.gpaper:gpaper-ratta:$gpaperVersion")
}
