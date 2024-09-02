[![Build Status](https://github.com/deepmedia/Grease/actions/workflows/build.yml/badge.svg?event=push)](https://github.com/deepmedia/Grease/actions)
[![Release](https://img.shields.io/github/release/deepmedia/Grease.svg)](https://github.com/deepmedia/Grease/releases)
[![Issues](https://img.shields.io/github/issues-raw/deepmedia/Grease.svg)](https://github.com/deepmedia/Grease/issues)

# Grease

A Gradle plugin for creating fat AARs, useful for distributing multiple modules in a single file.
To install the plugin, apply it to your Android project:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
    }
}

// build.gradle.kts
plugins {
    id("com.android.library")
    id("io.deepmedia.tools.grease") version "0.3.1"
}
```

Note: it is important that Grease is applied *after* the Android library plugin.

## Usage

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

By default, declaring a specific grease dependency **will not** include transitive dependencies. 
To do so, you can use grease configurations ending in `Tree`:

```kotlin
dependencies {
    grease("my-package:artifact:1.0.0") // not transitive
    greaseTree("my-other-package:other-artifact:1.0.0") // transitive
}
```

### Relocations

We support relocations through the popular [Shadow](https://github.com/GradleUp/shadow) plugin. Inside the `grease`
extension, you can use:

- `relocate(prefix: String)` to relocate all included packages by prefixing them with the given prefix. Defaults to `"grease"`.
- `relocate(from: String, to: String, configure: Action<SimpleRelocator>)` to register a package-specific relocator,
  as described in the [shadow plugin docs](https://gradleup.com/shadow/configuration/relocation/#relocating-packages)
