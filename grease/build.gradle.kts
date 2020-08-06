/*
 * Copyright (c) 2020 Otalia Studios. Author: Mattia Iavarone.
 */

import com.otaliastudios.tools.publisher.common.*

plugins {
    `kotlin-dsl`
    id("com.otaliastudios.tools.publisher")
}

dependencies {
    api("com.android.tools.build:gradle:4.0.1") // android gradle plugin
    api(gradleApi()) // gradle
    api(gradleKotlinDsl()) // not sure if needed
    api(localGroovy()) // groovy
}

publisher {
    project.name = "Grease"
    project.artifact = "grease"
    project.description = "Fat and shaded AARs for Android."
    project.group = "com.otaliastudios.tools"
    project.url = "https://github.com/natario1/Grease"
    project.vcsUrl = "https://github.com/natario1/Grease.git"
    release.version = "0.1.1"
    // release.setSources(Release.SOURCES_AUTO)
    // release.setDocs(Release.DOCS_AUTO)
    directory {
        directory = "../build/maven"
    }

    directory("shared") {
        directory = file(repositories.mavenLocal().url).absolutePath
    }
}