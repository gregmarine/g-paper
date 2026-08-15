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

    defaultConfig {
        minSdk = 29
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
