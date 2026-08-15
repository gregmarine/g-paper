plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Also the coordinates sibling-module POMs reference this project by.
group = property("GPAPER_GROUP") as String
version = property("GPAPER_VERSION") as String

android {
    namespace = "com.symmetricalpalmtree.gpaper.core"
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
    testImplementation("junit:junit:4.13.2")
}

// mavenLocal-only publishing (Phase 6 decision): `./gradlew publishToMavenLocal`.
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
}
