[![Build Status](https://github.com/deepmedia/Grease/workflows/Build/badge.svg?event=push)](https://github.com/deepmedia/Grease/actions)
[![Release](https://img.shields.io/github/release/deepmedia/Grease.svg)](https://github.com/deepmedia/Grease/releases)
[![Issues](https://img.shields.io/github/issues-raw/deepmedia/Grease.svg)](https://github.com/deepmedia/Grease/issues)

# Grease

A Gradle plugin for creating fat AARs, useful for distributing multiple modules in a single file.
To install the plugin, you must configure the build script classpath:

```kotlin
buildscript {
    repositories {
        jcenter()
        google()
    }
    dependencies {
        classpath("io.deepmedia.tools:grease:0.2.0")
    }
}
```

## Usage

To apply the plugin, declare it in your build script with the `io.deepmedia.tools.grease` id.
This must be done after the com.android.library plugin:

```groovy
apply plugin: 'com.android.library'
apply plugin: 'io.deepmedia.tools.grease'
```

Once applied, Grease will create Gradle configurations that you can use to select which of your
dependencies should be bundled in the final fat AAR.

```kotlin
dependencies {
    // Api dependency. This will not be bundled.
    api("androidx.core:core:1.3.1")

    // Implementation dependency. This will not be bundled.
    implementation("androidx.core:core:1.3.1")

    // Grease dependency! This will be bundled into the final AAR.
    grease("androidx.core:core:1.3.1")

    // Build-type, product flavour and variant specific configurations.
    // Can be used to enable grease only on specific library variants.
    greaseRelease("androidx.core:core:1.3.1")
    greaseDebug("androidx.core:core:1.3.1")
    greaseBlueCircleDebug("androidx.core:core:1.3.1")
    greaseGreenTriangleRelease("androidx.core:core:1.3.1")
}
```

Note that we used `androidx.core:core:1.3.1` as an example, but that is not recommended as it will
likely cause compile issue on projects that consume the Grease AAR, if they already depend on the
`androidx.core:core:1.3.1` (as most project do).

If you don't control the projects that will consume the Grease AAR, you should only bundle in
dependencies that you own, to be sure that they won't be present in the classpath of the consumer.

### Transitivity

When you add a grease dependency, by default all transitive dependencies are greased as well, so
they will become part of the fat AARs. To avoid this, you can mark the configuration as non transitive:

```kotlin
configurations["grease"].isTransitive = false
configurations["greaseRelease"].isTransitive = false
configurations["greaseDebug"].isTransitive = false

// Variant specific configurations are created lazily so you must wait for them to be
// available before modifying them.
configurations.configureEach {
    if (name == "greaseBlueCircleDebug") {
        isTransitive = false
    }
}
```