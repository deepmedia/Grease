package io.deepmedia.tools.grease

import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import com.github.jengelman.gradle.plugins.shadow.relocation.SimpleRelocator
import com.github.jengelman.gradle.plugins.shadow.transformers.Transformer
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

abstract class GreaseExtension @Inject constructor(objects: ObjectFactory) {

    internal val prefix = objects.property<String>().convention("")
    internal val relocators = objects.listProperty<Relocator>()
    internal val transformers = objects.listProperty<Transformer>()

    fun relocate(prefix: String = "grease") {
        this.prefix.set(prefix)
    }

    fun relocate(from: String, to: String, configure: Action<SimpleRelocator> = Action {  }) {
        val relocator = SimpleRelocator(from, to, emptyList(), emptyList())
        configure.execute(relocator)
        relocators.add(relocator)
    }

    fun <T : Transformer> transform(transformer: T, configure: Action<T> = Action { }) {
        configure.execute(transformer)
        transformers.add(transformer)
    }
}