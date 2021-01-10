package io.deepmedia.tools.grease

import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.internal.dsl.BuildType
import com.android.build.gradle.internal.dsl.ProductFlavor
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import org.gradle.api.DomainObjectSet
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named

internal fun Configuration.artifactsOf(type: AndroidArtifacts.ArtifactType)
        = incoming.artifactView {
    attributes {
        attribute(AndroidArtifacts.ARTIFACT_TYPE, type.type)
    }
}.files

internal val Project.grease get() = greaseOf("")

internal fun Project.greaseOf(flavor: com.android.builder.model.ProductFlavor)
        = greaseOf(flavor.name)

internal fun Project.greaseOf(buildType: com.android.builder.model.BuildType)
        = greaseOf(buildType.name)

internal fun Project.greaseOf(variant: LibraryVariant)
        = greaseOf(variant.name)

private fun Project.greaseOf(name: String)
        = configurations[name.greasify()]


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
private fun Project.createGrease(name: String): Configuration {
    val configuration = configurations.create(name.greasify())
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

// Create the root configuration. Make compileOnly extend from it so that grease
// artifacts are in the classpath and we don't have compile issues.
internal fun Project.createRootConfiguration(log: Logger) {
    log.i { "Creating root configuration..." }
    createGrease("")
}

// Create one configuration per product flavor.
// Make it extend the root configuration so that artifacts are inherited.
internal fun Project.createProductFlavorConfigurations(
    flavors: NamedDomainObjectContainer<ProductFlavor>, log: Logger) {
    flavors.configureEach {
        log.i { "Creating product flavor configuration ${this.name.greasify()}..." }
        val config = createGrease(this.name)
        config.extendsFrom(grease)
    }
}

// Create one configuration per build type.
// Make it extend the root configuration so that artifacts are inherited.
internal fun Project.createBuildTypeConfigurations(
    buildTypes: NamedDomainObjectContainer<BuildType>, log: Logger) {
    buildTypes.configureEach {
        val buildType = this
        log.i { "Creating build type configuration ${buildType.name.greasify()}..." }
        val config = createGrease(buildType.name)
        config.extendsFrom(grease)
        config.attributes {
            attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class, buildType.name))
        }
    }
}

// Library variants are made of <productFlavor>...<productFlavor><buildType>.
// In addition to the root configuration, we should inherit from the build type config
// and the configurations for all flavors that make this variant.
internal fun Project.createVariantConfigurations(
    variants: DomainObjectSet<LibraryVariant>, log: Logger) {
    variants.configureEach {
        val flavors = this.productFlavors
        if (flavors.isEmpty()) {
            log.i { "Variant has no flavors, reusing the build type configuration..." }
        } else {
            log.i { "Creating variant configuration ${this.name.greasify()}..." }
            val config = createGrease(this.name)
            config.extendsFrom(grease)
            config.extendsFrom(greaseOf(buildType))
            config.extendsFrom(*flavors.map { greaseOf(it) }.toTypedArray())
        }
    }
}
