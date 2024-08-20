plugins {
    `kotlin-dsl`
    alias(libs.plugins.publisher)
}

group = "io.deepmedia.tools"
version = "0.3.0"

gradlePlugin {
    plugins {
        create("grease") {
            id = "io.deepmedia.tools.grease"
            implementationClass = "io.deepmedia.tools.grease.GreasePlugin"
        }
    }
}

dependencies {
    implementation(libs.asm.commons)
    implementation(libs.gradle.shadow)
    implementation(libs.bundles.gradle.android)
}

deployer {
    content {
        gradlePluginComponents {
            kotlinSources()
            emptyDocs()
        }
    }

    projectInfo {
        description = "Fat AARs for Android."
        url = "https://github.com/deepmedia/Grease"
        scm.fromGithub("deepmedia", "Grease")
        developer("natario1", "mattia@deepmedia.io", "DeepMedia", "https://deepmedia.io")
    }
}