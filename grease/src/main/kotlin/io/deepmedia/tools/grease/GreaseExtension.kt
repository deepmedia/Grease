package io.deepmedia.tools.grease

import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator
import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer

abstract class GreaseExtension {

    internal val relocators = mutableListOf<Relocator>()
    internal val transformers = mutableListOf<Transformer>()

    internal var isRelocationEnabled = false
    var relocationPrefix: String = "shadow"
        set(value) {
            field = value
            isRelocationEnabled = true
        }

    fun relocate(
        from: String,
        to: String,
        configure: (SimpleRelocator.() -> Unit)? = null
    ) {
        val relocator = SimpleRelocator(from, to, emptyList(), emptyList())
        configure?.invoke(relocator)
        relocators.add(relocator)
    }

    fun <T : Transformer> transform(
        transformer: T,
        configure: (T.() -> Unit)? = null
    ) {
        configure?.invoke(transformer)
        transformers.add(transformer)
    }

}