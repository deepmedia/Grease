plugins {
    alias(libs.plugins.android.library)
    id("io.deepmedia.tools.grease")
}

grease {
    relocationPrefix = "temp"
}

android {
    namespace = "io.deepmedia.tools.grease.sample.library"
    ndkVersion = "23.1.7779620"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        // Configure manifest placeholders to test that they are correctly replaced
        // in our manifest processing step.
        manifestPlaceholders["placeholder"] = "replacement"

        // Configure native library libgrease to test that it's correctly packed
        // in the output together with those of our dependencies.
        ndk {
            abiFilters.addAll(setOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a"))
        }

        // Configure proguard files.
        proguardFiles(
            getDefaultProguardFile(com.android.build.gradle.ProguardFiles.ProguardFile.OPTIMIZE.fileName),
            "proguard-rules.pro"
        )
        consumerProguardFile("consumer-rules.pro")

        // Configure some flavors for testing configurations.
        flavorDimensions.addAll(listOf("color", "shape"))
        productFlavors {
            create("blue") { dimension = "color" }
            create("green") { dimension = "color" }
            create("circle") { dimension = "shape" }
            create("triangle") { dimension = "shape" }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/CMakeLists.txt")
        }
    }
}

dependencies {
    grease("androidx.core:core:1.0.0")

    // include deps to pom when publishing
    api("com.google.android.material:material:1.0.0")
    // Includes resource and some manifest changes
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.4")

    // Includes native libraries
    grease("org.tensorflow:tensorflow-lite:2.3.0")
    // Manifest changes, layout resources
    grease("com.otaliastudios:cameraview:2.7.2")

    // Doesn't work. TODO: we need to configure grease configurations so that in case of multiple matching
    // variants, they prefer one where com.android.build.api.attributes.BuildTypeAttr is set to release
    // grease(project(":sample-dependency-pure"))
}