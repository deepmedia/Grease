package io.deepmedia.tools.grease

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

internal val Project.greaseBuildDir: Provider<Directory>
    get() = layout.buildDirectory.dir("grease")
