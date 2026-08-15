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
