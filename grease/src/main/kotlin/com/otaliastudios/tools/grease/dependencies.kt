package com.otaliastudios.tools.grease

import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.add

fun DependencyHandlerScope.grease(notation: Any)
        = grease("", notation)

fun DependencyHandlerScope.grease(variant: String, notation: Any) {
    add(variant.greasify(), notation)
}

fun DependencyHandlerScope.grease(notation: String, configure: ExternalModuleDependency.() -> Unit)
        = grease("", notation, configure)

fun DependencyHandlerScope.grease(
    variant: String,
    notation: String,
    configure: ExternalModuleDependency.() -> Unit
) {
    add(variant.greasify(), notation, configure)
}
