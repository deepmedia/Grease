package io.deepmedia.tools.grease

import java.util.*

internal fun String.greasify() = nameOf("grease", this)

internal fun nameOf(vararg values: String) = values
    .filter { it.isNotBlank() }
    .mapIndexed { index, string -> if (index == 0) string.decapitalize() else string.capitalize()
    }.joinToString(separator = "")

private fun String.decapitalize() = replaceFirstChar { it.lowercase(Locale.getDefault()) }
private fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }