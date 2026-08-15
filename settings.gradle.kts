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
        google()
        mavenCentral()
        // The BOOX SDK repo (insecure http) is added in Phase 3, scoped alongside
        // gpaper-onyx's dependencies — generic-only consumers must never need it.
    }
}

rootProject.name = "g-paper"
include(":gpaper-core", ":gpaper-onyx", ":gpaper-ratta", ":demo")
