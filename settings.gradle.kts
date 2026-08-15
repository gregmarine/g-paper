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
        // BOOX SDK repo (Phase 3, gpaper-onyx only). Onyx publishes over plain http —
        // there is no https mirror. Consumers that skip gpaper-onyx never need this
        // repo (or jetifier); consumers that use it must add both to their own build.
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "g-paper"
include(":gpaper-core", ":gpaper-onyx", ":gpaper-ratta", ":demo")
