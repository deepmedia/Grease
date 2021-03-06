import io.deepmedia.tools.publisher.common.*

plugins {
    `kotlin-dsl`
    id("io.deepmedia.tools.publisher")
}

dependencies {
    api("com.android.tools.build:gradle:4.1.1") // android gradle plugin
    api(gradleApi()) // gradle
    api(gradleKotlinDsl()) // not sure if needed
    api(localGroovy()) // groovy
}

publisher {
    project.name = "Grease"
    project.artifact = "grease"
    project.description = "Fat AARs for Android."
    project.group = "io.deepmedia.tools"
    project.url = "https://github.com/deepmedia/Grease"
    project.vcsUrl = "https://github.com/deepmedia/Grease.git"
    release.version = "0.2.0"
    release.sources = Release.SOURCES_AUTO
    release.docs = Release.DOCS_AUTO

    bintray {
        auth.user = "BINTRAY_USER"
        auth.key = "BINTRAY_KEY"
        auth.repo = "BINTRAY_REPO"
    }

    directory {
        directory = "../build/maven"
    }

    directory("shared") {
        directory = file(repositories.mavenLocal().url).absolutePath
    }
}