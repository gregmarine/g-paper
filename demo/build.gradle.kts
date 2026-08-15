plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.symmetricalpalmtree.gpaper.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.symmetricalpalmtree.gpaper.demo"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Every target device (BOOX, Supernote, generic test tablets) is 64-bit ARM;
            // this also drops the Onyx SDK's non-16KB-aligned x86_64 native libs.
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
    implementation(project(":gpaper-core"))
    implementation(project(":gpaper-onyx"))
    implementation(project(":gpaper-ratta"))
}
