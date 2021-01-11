plugins {
    id("com.android.library")
    id("io.deepmedia.tools.grease")
}

android {
    setCompileSdkVersion(29)
    ndkVersion = "20.1.5948944"
    defaultConfig {
        setMinSdkVersion(21)
        setTargetSdkVersion(29)
        versionCode = 1
        versionName = "1.0"

        // Configure manifest placeholders to test that they are correctly replaced
        // in our manifest processing step.
        manifestPlaceholders["placeholder"] = "replacement"

        // Configure native library libgrease to test that it's correctly packed
        // in the output together with those of our dependencies.
        ndk {
            abiFilters.addAll(setOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a"))
        }

        // Configure proguard files.
        proguardFiles(getDefaultProguardFile(com.android.build.gradle.ProguardFiles.ProguardFile.OPTIMIZE.fileName), "proguard-rules.pro")
        consumerProguardFile("consumer-rules.pro")

        // Configure some flavors for testing configurations.
        flavorDimensions("color", "shape")
        productFlavors.create("blue") { dimension = "color" }
        productFlavors.create("green") { dimension = "color" }
        productFlavors.create("circle") { dimension = "shape" }
        productFlavors.create("triangle") { dimension = "shape" }
    }

    buildTypes["debug"].isMinifyEnabled = false
    buildTypes["release"].isMinifyEnabled = true

    externalNativeBuild {
        cmake {
            path = file("src/main/CMakeLists.txt")
        }
    }
}

configurations.configureEach {
    if (name == "greaseGreenCircleDebug") isTransitive = false
}

dependencies {
    // Includes resource and some manifest changes
    grease("androidx.core:core:1.3.2")
    // Includes native libraries
    greaseRelease("org.tensorflow:tensorflow-lite:2.3.0")
    // Manifest changes, layout resources
    afterEvaluate {
        add("greaseGreenCircleDebug","com.otaliastudios:cameraview:2.6.3")
    }
}