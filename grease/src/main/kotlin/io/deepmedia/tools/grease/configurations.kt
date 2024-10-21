package io.deepmedia.tools.grease

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.internal.dsl.BuildType
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType
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

internal fun Configuration.artifactsOf(type: ArtifactType): FileCollection = incoming.artifactView {
    attributes {
        attribute(AndroidArtifacts.ARTIFACT_TYPE, type.type)
    }
}.files

internal fun List<Configuration>.artifactsOf(type: ArtifactType): FileCollection = map {
    it.incoming.artifactView {
        attributes {
            attribute(AndroidArtifacts.ARTIFACT_TYPE, type.type)
        }
    }.files as FileCollectionInternal
}.let {
    ArtifactsFileCollection(it)
}

private class ArtifactsFileCollection(private val fileCollections: List<FileCollectionInternal>) :
    CompositeFileCollection() {
    override fun getDisplayName(): String = "grease file collection"

    override fun visitChildren(visitor: Consumer<FileCollectionInternal>) {
        fileCollections.forEach(visitor::accept)
    }
}

internal fun Project.grease(isTransitive: Boolean) = greaseOf("".configurationName(isTransitive))

internal fun Project.greaseOf(variant: Variant, isTransitive: Boolean = false) =
    greaseOf(variant.name, isTransitive)

private fun Project.greaseOf(name: String, isTransitive: Boolean = false) =
    configurations[name.configurationName(isTransitive).greasify()]

internal fun Project.greaseApiOf(variant: Variant, isTransitive: Boolean = false) =
    configurations[nameOf(variant.name.configurationName(isTransitive), "api").greasify()]

private fun Project.greaseApiOf(config: Configuration, isTransitive: Boolean = false) =
    configurations[nameOf(config.name.configurationName(isTransitive), "api")]


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
private fun Project.createGrease(name: String, transitive: Boolean): Configuration {
    val greasifiedName = name.configurationName(transitive).greasify()
    val existed = configurations.findByName(greasifiedName)
    if (existed != null) return existed

    val configuration = configurations.create(greasifiedName) {
        isTransitive = transitive
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        }
    }
    val configurationApi = configurations.create(nameOf(greasifiedName, "api")) {
        isTransitive = transitive
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_API))
        }
        extendsFrom(configuration)
    }
    configurations.configureEach {
        val other = this
        if (other.name == nameOf(name, "compileOnly")) {
            other.extendsFrom(configuration)
        }
    }
    return configuration
}

private fun String.configurationName(isTransitive: Boolean) =
    if (isTransitive) nameOf(this, "tree") else this

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
        flavorConfiguration.extendsFromSafely(this, grease(isTransitive), log)

        variant.productFlavors.forEach { (_, subFlavor) ->
            log.d { "Creating sub product flavor configuration ${subFlavor.greasify()}..." }
            val config = createGrease(subFlavor, isTransitive)
            config.extendsFromSafely(this, grease(isTransitive), log)
            flavorConfiguration.extendsFromSafely(this, config, log)
        }
        variant.productFlavors.forEach { (_, subFlavor) ->
            val buildTypedSubFlavor = nameOf(subFlavor, variant.buildType.orEmpty())
            log.d { "Creating buildTyped sub product flavor configuration ${buildTypedSubFlavor.greasify()}..." }
            val config = createGrease(buildTypedSubFlavor, isTransitive)
            config.attributes {
                attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, variant.buildType.orEmpty()))
            }
            greaseApiOf(config).attributes {
                attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, variant.buildType.orEmpty()))
            }
            config.extendsFromSafely(this, grease(isTransitive), log)
            config.extendsFromSafely(this, greaseOf(variant.buildType.orEmpty(), isTransitive), log)
            config.extendsFromSafely(this, greaseOf(subFlavor, isTransitive), log)
            config.extendsFromSafely(this, flavorConfiguration, log)
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
        config.extendsFromSafely(this@createBuildTypeConfigurations, grease(isTransitive), log)
        config.attributes {
            attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, buildType.name))
        }
        greaseApiOf(config).attributes {
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
    config.attributes {
        attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, variant.buildType.orEmpty()))
    }
    greaseApiOf(config).attributes {
        attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class,  variant.buildType.orEmpty()))
    }
    config.extendsFromSafely(this, grease(isTransitive), log)
    config.extendsFromSafely(this, greaseOf(variant.buildType.orEmpty(), isTransitive), log)
    variant.flavorName?.let { flavor ->
        config.extendsFromSafely(this, greaseOf(flavor, isTransitive), log)
        variant.productFlavors.forEach { (_, subFlavor) ->
            config.extendsFromSafely(this, greaseOf(subFlavor, isTransitive), log)
            config.extendsFromSafely(this, greaseOf(nameOf(subFlavor, variant.buildType.orEmpty()), isTransitive), log)
        }
    }

}

private fun Configuration.extendsFromSafely(project: Project, configuration: Configuration, log: Logger? = null) {
    if (configuration.name != name) {
        log?.d { "Extend $name from ${configuration.name}..." }
        extendsFrom(configuration)
        project.greaseApiOf(this).extendsFrom(project.greaseApiOf(configuration))
    }
}