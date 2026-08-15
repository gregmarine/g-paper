plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = property("GPAPER_GROUP") as String
version = property("GPAPER_VERSION") as String

android {
    namespace = "com.symmetricalpalmtree.gpaper.onyx"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        // Shipped to consumers: EventBus @Subscribe methods are found reflectively, so
        // a minifying host would otherwise strip the pen-proximity subscriber and
        // TouchHelper.register would throw (the palm gate then loses its exit signal).
        consumerProguardFiles("consumer-rules.pro")
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

    // Onyx BOOX SDK — same versions as the Notesprout reference (device-proven on the
    // five-device Tier-1 fleet). Served from repo.boox.com (see settings.gradle.kts).
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")

    // The BOOX SDK bootstraps itself through hidden system APIs (VMRuntime,
    // RawInputManager); Android 14+ blocks that without this bypass. OnyxEngine
    // installs it — hosts never touch this dependency directly.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}

// mavenLocal-only publishing (Phase 6 decision). Consumers of THIS artifact must add
// the BOOX maven repo and android.enableJetifier=true to their own build (the POM's
// onyxsdk/hiddenapibypass deps resolve there); generic-only consumers need neither.
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
}
