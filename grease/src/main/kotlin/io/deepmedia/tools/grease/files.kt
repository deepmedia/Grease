package io.deepmedia.tools.grease

import java.io.File
import java.util.jar.JarFile


internal fun File.folder(name: String) = File(this, name).also { it.mkdirs() }

internal fun File.file(name: String) = File(this, name).also { it.parentFile.mkdirs() }

internal fun File.listFiles(extension: String): Array<File> {
    assert(isDirectory) { "File is not a directory! $absolutePath" }
    return listFiles { _, name -> name.endsWith(".$extension") } ?: arrayOf()
}

internal fun File.listDirectories(): Array<File> {
    assert(isDirectory) { "File is not a directory! $absolutePath" }
    return listFiles { file -> file.isDirectory } ?: arrayOf()
}

internal fun File.listFilesRecursive(extension: String): List<File> {
    assert(isDirectory) { "File is not a directory! $absolutePath" }
    val list = mutableListOf<File>()
    list.addAll(listFiles(extension))
    list.addAll(listDirectories().flatMap { it.listFilesRecursive(extension) })
    return list
}

internal fun File.relocate(from: File, to: File): File {
    assert(absolutePath.startsWith(from.absolutePath)) { "File not contained in $from!" }
    return File(absolutePath.replaceFirst(from.absolutePath, to.absolutePath))
}

val JarFile.packageNames: Set<String>
    get() = entries().asSequence().mapNotNull { entry ->
        if (entry.name.endsWith(".class") && entry.name != "module-info.class") {
            entry.name.substring(0 until entry.name.lastIndexOf('/')).replace('/', '.')
        } else null
    }.toSet()

val File.packageNames: Set<String>
    get() = listFilesRecursive("class").mapNotNull { file ->
        if (file.name != "module-info.class") {
            val cleanedPath = file.path.removePrefix(this.path).removePrefix("/")
            cleanedPath
                .substring(0 until cleanedPath.lastIndexOf('/').coerceAtLeast(0))
                .replace('/', '.').takeIf { it.isNotBlank() }
        } else null
    }.toSet()