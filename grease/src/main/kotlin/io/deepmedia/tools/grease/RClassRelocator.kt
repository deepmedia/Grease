package io.deepmedia.tools.grease

import com.github.jengelman.gradle.plugins.shadow.relocation.RelocatePathContext
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator

internal class RClassRelocator(
    fromPackage: String,
    toPackage: String,
    logger: Logger
) : SimpleRelocator(fromPackage, toPackage, emptyList(), emptyList()) {

    private val logger = logger.child("R-relocator")
    private val fromRPath = fromPackage.replace(".", "/")
    private val toRPath = toPackage.replace(".", "/") + "/R"
    private val fromRPathRegex = "$fromRPath.*/R".toRegex()

    init {
        include( "%regex[$fromRPathRegex\\$.*]")
        include( "%regex[$fromRPathRegex.*]")
    }

    override fun canRelocateClass(className: String?): Boolean = false

    override fun relocatePath(context: RelocatePathContext): String {
        val foundedPath = fromRPathRegex.find(context.path)?.value
        if (foundedPath == null) {
            logger.d { "Can't move from ${context.path} to $toRPath" }
            return context.path
        }
        return context.path.replaceFirst(foundedPath, toRPath)
    }

}