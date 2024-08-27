package io.deepmedia.tools.grease

import com.github.jengelman.gradle.plugins.shadow.transformers.CacheableTransformer
import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer
import com.github.jengelman.gradle.plugins.shadow.transformers.TransformerContext
import org.gradle.api.file.FileTreeElement
import kotlinx.metadata.jvm.KotlinModuleMetadata
import kotlinx.metadata.jvm.UnstableMetadataApi
import org.apache.tools.zip.ZipEntry
import org.apache.tools.zip.ZipOutputStream

// from kotlin sources

@CacheableTransformer
@OptIn(UnstableMetadataApi::class)
internal class KotlinModuleShadowTransformer(private val logger: Logger) : Transformer {
    @Suppress("ArrayInDataClass")
    private data class Entry(val path: String, val bytes: ByteArray)

    private val data = mutableListOf<Entry>()

    override fun getName() = "KotlinModuleShadowTransformer"

    override fun canTransformResource(element: FileTreeElement): Boolean =
        element.path.substringAfterLast(".") == KOTLIN_MODULE

    override fun transform(context: TransformerContext) {
        fun relocate(content: String): String =
            context.relocators
                .filterNot { it is RClassRelocator }
                .fold(content) { acc, relocator -> relocator.applyToSourceContent(acc) }

        logger.i { "Transforming kotlin_module ${context.path}" }
        val metadata = KotlinModuleMetadata.read(context.`is`.readBytes())
        val module = metadata.kmModule

        val packageParts = module.packageParts.toMap()
        module.packageParts.clear()
        packageParts.map { (fqName, parts) ->
            require(parts.multiFileClassParts.isEmpty()) { parts.multiFileClassParts } // There are no multi-file class parts in core

            val fileFacades = parts.fileFacades.toList()
            parts.fileFacades.clear()
            fileFacades.mapTo(parts.fileFacades) { relocate(it) }

            relocate(fqName) to parts
        }.toMap(module.packageParts)

        data += Entry(context.path, metadata.write())
    }

    override fun hasTransformedResource(): Boolean = data.isNotEmpty()

    override fun modifyOutputStream(os: ZipOutputStream, preserveFileTimestamps: Boolean) {
        for ((path, bytes) in data) {
            os.putNextEntry(ZipEntry(path))
            os.write(bytes)
        }
        data.clear()
    }

    companion object {
        const val KOTLIN_MODULE = "kotlin_module"
    }
}