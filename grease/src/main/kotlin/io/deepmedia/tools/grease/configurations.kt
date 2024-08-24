package io.deepmedia.tools.grease

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.internal.dsl.BuildType
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.file.CompositeFileCollection
import org.gradle.api.internal.file.FileCollectionInternal
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import java.util.function.Consumer

internal fun Configuration.artifactsOf(type: AndroidArtifacts.ArtifactType): FileCollection = incoming.artifactView {
    attributes {
        attribute(AndroidArtifacts.ARTIFACT_TYPE, type.type)
    }
}.files

internal fun Array<out Configuration>.artifactsOf(type: AndroidArtifacts.ArtifactType): FileCollection = map {
    it.incoming.artifactView {
        attributes {
            attribute(AndroidArtifacts.ARTIFACT_TYPE, type.type)
        }
    }.files as FileCollectionInternal
}.let {
    ArtifactsFileCollection(it)
}

private class ArtifactsFileCollection(private val fileCollections: List<FileCollectionInternal>) : CompositeFileCollection() {
    override fun getDisplayName(): String = "grease file collection"

    override fun visitChildren(visitor: Consumer<FileCollectionInternal>) {
        fileCollections.forEach(visitor::accept)
    }
}


internal fun Project.grease(isTransitive: Boolean) = greaseOf(if (isTransitive) "api" else "")

internal fun Project.greaseOf(buildType: com.android.builder.model.BuildType, isTransitive: Boolean = false) =
    greaseOf(buildType.name.configurationName(isTransitive))

internal fun Project.greaseOf(variant: Variant, isTransitive: Boolean = false) =
    greaseOf(variant.name.configurationName(isTransitive))

private fun Project.greaseOf(name: String, isTransitive: Boolean = false) =
    configurations[name.configurationName(isTransitive).greasify()]


/**
 * We are interested in making compile configurations extend the grease configuration so that grease
 * artifacts are added to the classpath and we don't have compile issues due to 'class not found'.
 * Kezong uses "compileOnly" for this. The downside (I think) will be that these dependencies will
 * be listed in POM file for maven publications, though with the "compile" scope.
 *
 * We'll try using "compileClasspath" instead of "compileOnly". "compileClasspath" extends from
 * compileOnly and also from compile/api/implementation, so it should collect all libraries that
 * are meant to be available at compile time. Hopefully by adding artifacts here, they'll be
 * available to the compiler yet will not be part of the POM file. UNTESTED.
 * See https://blog.gradle.org/introducing-compile-only-dependencies
 *
 * Note that the counterpart for "compileClasspath" also exists and it's called "runtimeClasspath".
 * They map to the org.gradle.usage attribute of java-api and java-runtime respectively.
 */
private fun Project.createGrease(name: String, isTransitive: Boolean): Configuration {
    val greasifiedName = name.configurationName(isTransitive).greasify()
    val existed = configurations.findByName(greasifiedName)
    if (existed != null) return existed

    val configuration = configurations.create(greasifiedName)
    configuration.isTransitive = isTransitive
    configuration.attributes {
        // This should make sure that we don't pull in compileOnly dependencies that should not be in
        // the final bundle. The other usage, JAVA_API, would only include exposed dependencies,
        // so drop everything marked as implementation() in the original library. Not good.
        // (Still, we currently put in A LOT OF STUFF, like org.jetbrains.annotations ... Not sure how to avoid this.)
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    }
    configurations.configureEach {
        val other = this
        if (other.name == nameOf(name, "compileClasspath")) {
            extendsFrom(configuration)
        }
    }
    return configuration
}

private fun String.configurationName(isTransitive: Boolean) = if (isTransitive) nameOf(this, "api") else this

// Create the root configuration. Make compileOnly extend from it so that grease
// artifacts are in the classpath and we don't have compile issues.
internal fun Project.createRootConfiguration(isTransitive: Boolean, log: Logger) {
    log.d { "Creating root configuration..." }
    createGrease("", isTransitive)
}

// Create one configuration per product flavor.
// Make it extend the root configuration so that artifacts are inherited.
internal fun Project.createProductFlavorConfigurations(
    androidComponent: AndroidComponentsExtension<*, *, *>,
    isTransitive: Boolean,
    log: Logger,
) = androidComponent.onVariants { variant ->
    variant.flavorName?.let { flavorName ->
        log.d { "Creating product flavor configuration ${flavorName.greasify()}..." }
        val flavorConfiguration = createGrease(flavorName, isTransitive)
        flavorConfiguration.extendsFromSafely(grease(isTransitive), log)

        variant.productFlavors.forEach { (_, subFlavor) ->
            log.d { "Creating sub product flavor configuration ${subFlavor.greasify()}..." }
            val config = createGrease(subFlavor, isTransitive)
            config.extendsFromSafely(grease(isTransitive), log)
            flavorConfiguration.extendsFromSafely(config, log)
        }
        variant.productFlavors.forEach { (_, subFlavor) ->
            val buildTypedSubFlavor = nameOf(subFlavor, variant.buildType.orEmpty())
            log.d { "Creating buildTyped sub product flavor configuration ${buildTypedSubFlavor.greasify()}..." }
            val config = createGrease(buildTypedSubFlavor, isTransitive)
            config.extendsFromSafely(grease(isTransitive), log)
            config.extendsFromSafely(greaseOf(subFlavor, isTransitive), log)
            config.extendsFromSafely(flavorConfiguration, log)
        }
    }
}

// Create one configuration per build type.
// Make it extend the root configuration so that artifacts are inherited.
internal fun Project.createBuildTypeConfigurations(
    buildTypes: NamedDomainObjectContainer<BuildType>,
    isTransitive: Boolean,
    log: Logger
) {
    buildTypes.configureEach {
        val buildType = this
        log.d { "Creating build type configuration ${buildType.name.greasify()}..." }
        val config = createGrease(buildType.name, isTransitive)
        config.extendsFromSafely(grease(isTransitive), log)
        config.attributes {
            attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, buildType.name))
        }
    }
}

// Library variants are made of <productFlavor>...<productFlavor><buildType>.
// In addition to the root configuration, we should inherit from the build type config
// and the configurations for all flavors that make this variant.
internal fun Project.createVariantConfigurations(
    androidComponent: AndroidComponentsExtension<*, *, *>,
    isTransitive: Boolean,
    log: Logger
) = androidComponent.onVariants { variant ->
    log.d { "Creating variant configuration ${variant.name.greasify()}..." }
    val config = createGrease(variant.name, isTransitive)
    config.extendsFromSafely(grease(isTransitive), log)
    config.extendsFromSafely(greaseOf(variant.buildType.orEmpty(), isTransitive), log)
    variant.flavorName?.let { flavor ->
        config.extendsFromSafely(greaseOf(flavor, isTransitive), log)
        variant.productFlavors.forEach { (_, subFlavor) ->
            config.extendsFromSafely(greaseOf(subFlavor, isTransitive), log)
            config.extendsFromSafely(greaseOf(nameOf(subFlavor, variant.buildType.orEmpty()), isTransitive), log)
        }
    }

}

private fun Configuration.extendsFromSafely(configuration: Configuration, log: Logger? = null) {
    if (configuration.name != name) {
        log?.d { "Extend $name from ${configuration.name}..." }
        extendsFrom(configuration)
    }
}