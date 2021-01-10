package io.deepmedia.tools.grease

internal fun String.greasify() = nameOf("grease", this)

internal fun nameOf(vararg values: String) = values
    .filter { it.isNotBlank() }
    .mapIndexed { index, string ->
        if (index == 0) string.decapitalize() else string.capitalize()
    }.joinToString(separator = "")