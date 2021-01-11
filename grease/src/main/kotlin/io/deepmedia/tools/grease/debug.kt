package io.deepmedia.tools.grease

import com.android.build.gradle.internal.res.GenerateLibraryRFileTask
import com.android.build.gradle.internal.res.ParseLibraryResourcesTask
import com.android.build.gradle.internal.tasks.LibraryAarJarsTask
import com.android.build.gradle.tasks.GenerateBuildConfig
import com.android.build.gradle.tasks.GenerateResValues
import com.android.build.gradle.tasks.MergeResources
import com.android.build.gradle.tasks.VerifyLibraryResourcesTask
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

internal fun debugConfigurationHierarchy(target: Project, logger: Logger) {
    target.afterEvaluate {
        configurations.configureEach {
            val log = logger.child(this.name)
            val attrKeys = attributes.keySet()
            val attrs = attrKeys.map { it to attributes.getAttribute(it) }
            log.i {
                "Configuration added - " +
                        "canBeResolved=${isCanBeResolved} " +
                        "canBeConsumed=${isCanBeConsumed} " +
                        "extendsFrom=[${extendsFrom.joinToString { it.name }}] " +
                        "attributes=[${attrs.joinToString { "${it.first}:${it.second}" }}]"
            }
        }
    }
}

internal fun debugSourcesTasks(target: Project, logger: Logger) {
    target.tasks.configureEach {
        val log = logger.child(this.name).child(this::class.java.simpleName)
        when (val task = this) {
            is LibraryAarJarsTask -> doFirst {
                log.i { "mainScopeClassFiles (i): ${task.mainScopeClassFiles.files.joinToString()}" }
                log.i { "mainClassLocation (o): ${task.mainClassLocation.orNull}" }
                log.i { "localJarsLocation (o): ${task.localJarsLocation.orNull}" }
            }
            is JavaCompile -> doFirst {
                log.i { "source (i): ${task.source.files.joinToString()}" }
                log.i { "source (i): ${task.source.files.joinToString()}" }
                log.i { "generatedSourceOutputDirectory (o): ${task.options.generatedSourceOutputDirectory.orNull}" }
                log.i { "headerOutputDirectory (o): ${task.options.headerOutputDirectory.orNull}" }
                log.i { "destinationDirectory (o): ${task.destinationDirectory.orNull}" }
            }
        }
    }
}

internal fun debugResourcesTasks(target: Project, logger: Logger) {
    target.tasks.configureEach {
        val log = logger.child(this.name).child(this::class.java.simpleName)
        when (val task = this) {
            is GenerateLibraryRFileTask -> doFirst {
                log.i { "localResourcesFile (i): ${task.localResourcesFile.orNull}" }
                log.i { "dependencies (i): ${task.dependencies.files.joinToString()}" }
                log.i { "rClassOutputJar (o): ${task.rClassOutputJar.orNull}" }
                log.i { "sourceOutputDir (o): ${task.sourceOutputDir}" }
                log.i { "textSymbolOutputFileProperty (o): ${task.textSymbolOutputFileProperty.orNull}" }
                log.i { "symbolsWithPackageNameOutputFile (o): ${task.symbolsWithPackageNameOutputFile.orNull}" }
                log.i { "symbolsWithPackageNameOutputFile (o): ${task.symbolsWithPackageNameOutputFile.orNull}" }
            }
            is ParseLibraryResourcesTask -> doFirst {
                log.i { "inputResourcesDir (i): ${task.inputResourcesDir.orNull}" }
                log.i { "librarySymbolsFile (o): ${task.librarySymbolsFile.orNull}" }
            }
            is GenerateBuildConfig -> doFirst {
                log.i { "mergedManifests (i): ${task.mergedManifests.orNull}" } // empty
                log.i { "items (i): ${task.items.orNull}" } // empty
                log.i { "sourceOutputDir (o): ${task.sourceOutputDir}" } // generated/source/<VARIANT>. contains the BuildConfig file
            }
            is GenerateResValues -> doFirst {
                log.i { "items (i): ${task.items.orNull}" } // empty
                log.i { "resOutputDir (o): ${task.resOutputDir}" } // generated/res/resValues/<VARIANT>. nothing there for now
            }
            is MergeResources -> doFirst {
                // When package<VARIANT>Resources: intermediates/packaged_res/<VARIANT>.
                // There we find a copy of the resources.
                // When merge<VARIANT>Resources (soon after): intermediates/res/merged/<VARIANT>.
                // This is clearly the input of the verify task below.
                log.i { "outputDir (o): ${task.outputDir.orNull}" }
                // When package<VARIANT>Resources: intermediates/public_res/<VARIANT>/public.txt
                // When merge<VARIANT>Resources: empty.
                log.i { "publicFile (o): ${task.publicFile.orNull}" }
            }
            is VerifyLibraryResourcesTask -> doFirst {
                log.i { "manifestFiles (i): ${task.manifestFiles.orNull}" } // intermediates/aapt_friendly_merged_manifests/<VARIANT>/aapt
                log.i { "inputDirectory (i): ${task.inputDirectory.orNull}" } // intermediates/res/merged/<VARIANT>/ . Contains a copy of the resources, "merged" probably in the sense that all values are in a single file called values.xml ?
                log.i { "compiledDependenciesResources (i): ${task.compiledDependenciesResources.files.joinToString()}" } // empty
                log.i { "compiledDirectory (o): ${task.compiledDirectory}" } // intermediates/res/compiled/<VARIANT>/ . Contains the input resources compiled to a misterious *.flat format, probably used in APKs.
            }
        }
    }
}