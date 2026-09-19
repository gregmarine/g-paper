plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = property("GPAPER_GROUP") as String
version = property("GPAPER_VERSION") as String

android {
    namespace = "com.symmetricalpalmtree.gpaper.ratta"
    compileSdk = 35

    // Phase 28: the pencil's live preview goes straight into the panel driver (/dev/ebc),
    // which needs open/ioctl/mmap — syscalls with no platform API — so this module carries
    // one small C file. Building g-paper now needs the NDK; **consumers do not**: the .so
    // ships inside the AAR, arm64 only, which is the whole Supernote fleet. The module's
    // zero-dependency promise is untouched — no new maven artifact, no repo, no jetifier.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
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
    api(project(":gpaper-core"))

    testImplementation("junit:junit:4.13.2")
}

// mavenLocal-only publishing (Phase 6 decision). This artifact stays zero-dependency
// beyond gpaper-core — no extra repo, no jetifier for consumers.
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
}
