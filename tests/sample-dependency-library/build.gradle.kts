plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.deepmedia.tools.grease.sample.dependency.library"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    // Empty
}