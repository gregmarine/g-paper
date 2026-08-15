// Standalone consumer smoke test (Phase 6) — NOT part of the root build.
// Verifies that a real host app can consume the published g-paper artifacts:
//   1. ./gradlew publishToMavenLocal            (from the repo root)
//   2. ./gradlew -p consumer-smoke assembleDebug
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The published gpaper artifacts (mavenLocal-only publishing).
        mavenLocal()
        google()
        mavenCentral()
        // Required only because this consumer ships gpaper-onyx: the POM's Onyx SDK
        // dependencies live on the BOOX repo (plain http — no https mirror exists).
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "gpaper-consumer-smoke"
