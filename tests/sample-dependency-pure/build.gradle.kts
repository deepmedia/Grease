plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.deepmedia.tools.grease.sample.dependency.pure"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    // Empty
}