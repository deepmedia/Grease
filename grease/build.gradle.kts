@file:Suppress("UnstableApiUsage")

plugins {
    `jvm-test-suite`
    `kotlin-dsl`
    alias(libs.plugins.publisher)
}

group = "io.deepmedia.tools"
version = "0.3.3"

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnit()
            testType.set(TestSuiteType.FUNCTIONAL_TEST)

            dependencies {
                implementation(gradleTestKit())
                implementation(project.dependencies.kotlin("test") as String)
                implementation(project.dependencies.kotlin("test-junit") as String)
            }
        }
    }
}

gradlePlugin {
    plugins {
        create("grease") {
            id = "io.deepmedia.tools.grease"
            implementationClass = "io.deepmedia.tools.grease.GreasePlugin"
        }
    }

    testSourceSets(sourceSets["functionalTest"])
}

dependencies {
    implementation(libs.asm.commons)
    implementation(libs.gradle.shadow)
    implementation(libs.bundles.gradle.android)
    implementation(libs.kotlinx.metadata.jvm)
    implementation(libs.apache.ant)
}

deployer {
    content {
        gradlePluginComponents {
            kotlinSources()
            emptyDocs()
        }
    }

    projectInfo {
        description = "Fat AARs for Android, to distribute multiple library modules in a single file with no dependencies, with relocation support."
        url = "https://github.com/deepmedia/Grease"
        scm.fromGithub("deepmedia", "Grease")
        developer("Mattia Iavarone", "mattia@deepmedia.io", "DeepMedia", "https://deepmedia.io")
        license(apache2)
    }

    signing {
        key = secret("SIGNING_KEY")
        password = secret("SIGNING_PASSWORD")
    }

    // use "deployLocal" to deploy to local maven repository
    localSpec {
        directory.set(rootProject.layout.buildDirectory.get().dir("inspect"))
        signing {
            key = absent()
            password = absent()
        }
    }

    // use "deployNexus" to deploy to OSSRH / maven central
    nexusSpec {
        auth.user = secret("SONATYPE_USER")
        auth.password = secret("SONATYPE_PASSWORD")
        syncToMavenCentral = true
    }

    // use "deployNexusSnapshot" to deploy to sonatype snapshots repo
    nexusSpec("snapshot") {
        auth.user = secret("SONATYPE_USER")
        auth.password = secret("SONATYPE_PASSWORD")
        repositoryUrl = ossrhSnapshots1
        release.version = "latest-SNAPSHOT"
    }

    // use "deployGithub" to deploy to github packages
    githubSpec {
        repository = "Grease"
        owner = "deepmedia"
        auth {
            user = secret("GHUB_USER")
            token = secret("GHUB_PERSONAL_ACCESS_TOKEN")
        }
    }
}