@file:Suppress("UnstableApiUsage")

package io.deepmedia.tools.grease

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.LibraryVariant
import com.android.build.gradle.api.LibraryVariantOutput
import com.android.build.gradle.internal.LibraryTaskManager
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.TaskManager
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.res.GenerateLibraryRFileTask
import com.android.build.gradle.internal.res.ParseLibraryResourcesTask
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.MutableTaskContainer
import com.android.build.gradle.internal.tasks.*
import com.android.build.gradle.internal.tasks.factory.TaskCreationAction
import com.android.build.gradle.internal.tasks.manifest.mergeManifestsForApplication
import com.android.build.gradle.tasks.*
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.get

/**
 * Adds grease configurations for bundling dependencies in AAR files.
 * - includes source files
 * - includes resources
 * - merges manifests together
 * - includes native libraries
 * - includes assets
 * - merges consumer proguard files
 */
open class GreasePlugin : Plugin<Project> {

    private val Project.greaseDir get() = buildDir.folder("grease")

    @Suppress("NAME_SHADOWING")
    override fun apply(target: Project) {
        require(target.plugins.hasPlugin("com.android.library")) {
            "Grease must be applied after the com.android.library plugin."
        }
        val log = Logger(target, "grease")
        val android = target.extensions["android"] as LibraryExtension
        debugConfigurationHierarchy(target, log)

        // Create the configurations.
        target.createRootConfiguration(log)
        target.createProductFlavorConfigurations(android.productFlavors, log)
        target.createBuildTypeConfigurations(android.buildTypes, log)
        target.createVariantConfigurations(android.libraryVariants, log)

        // Configure all variants.
        android.libraryVariants.configureEach {
            val log = log.child("configureVariant")
            log.i { "Configuring variant ${this.name}..." }
            val configuration = target.greaseOf(this)
            configureVariantManifest(target, this, configuration, log)
            configureVariantJniLibs(target, this, configuration, log)
            configureVariantResources(target, this, configuration, log)
            configureVariantSources(target, this, configuration, log)
            configureVariantAssets(target, this, configuration, log)
            configureVariantProguardFiles(target, this, configuration, log)
        }
    }

    /**
     * Manifest processing is based on the [ManifestProcessorTask], however that is an abstract
     * class. Tasks are created lazily from creation actions extending [TaskCreationAction].
     * In case of process manifest tasks:
     * - [ProcessLibraryManifest.CreationAction]. This one is called when the library plugin is
     *   used, see [LibraryTaskManager].
     * - [ProcessApplicationManifest.CreationAction]. This one is called when the application plugin
     *   is used, see [ApplicationTaskManager].
     * - [ProcessTestManifest.CreationAction]. We probably don't care about this one.
     * Each variant's process manifest task is added right after the variant creation.
     * We should be able to retrieve the process task using variant.outputs.first():
     * variant.outputs seems to be immutable.
     *
     * The [ProcessLibraryManifest] task, unlike the application one, does not use dependency
     * manifests - all it does is merging manifests for the current buildTypes / flavours,
     * which is different than merging manifests from different libraries. We have two options:
     * - call another manifest merging task after the original one, like kezong does
     * - try to inject our extra manifests through the [ProcessLibraryManifest.manifestOverlays] property.
     *   This second option does not seem to be possible because that variable is not exposed in any way.
     */
    private fun configureVariantManifest(
        target: Project,
        variant: LibraryVariant,
        configuration: Configuration,
        logger: Logger
    ) {
        val log = logger.child("configureVariantManifest")
        variant.outputs.configureEach {
            val variantOutput = this
            variantOutput as LibraryVariantOutput
            log.i { "Configuring variant output ${variantOutput.name}..." }

            // cast ManifestProcessorTask to ProcessLibraryManifest
            @Suppress("UNCHECKED_CAST")
            val processManifestTask = processManifestProvider as TaskProvider<ProcessLibraryManifest>

            // After the file is copied we can go on with the actual manifest merging.
            // This task will overwrite the original AndroidManifest.xml.
            val reprocessManifestTask = target.tasks.register(processManifestTask.name.greasify()) {
                dependsOn(processManifestTask)

                // To retrieve the secondary files, we must query the configuration artifacts.
                val primaryManifest = processManifestTask.get().manifestOutputFile.asFile // overwrite
                val secondaryManifests = configuration.artifactsOf(AndroidArtifacts.ArtifactType.MANIFEST)
                inputs.file(primaryManifest)
                inputs.files(secondaryManifests)
                outputs.file(primaryManifest)

                doLast {
                    log.i { "Merging manifests... primary=${primaryManifest.get()}, secondary=${secondaryManifests.files.joinToString()}" }
                    mergeManifestsForApplication(
                        mainManifest = primaryManifest.get(),
                        /* Overlays are other manifests from the current 'source set' of this lib. */
                        manifestOverlays = if (false) secondaryManifests.files.toList() else listOf(),
                        /* Dependencies are other manifests from other libraries, which should be our
                         * case but it's not clear if we can use them with the LIBRARY merge type. */
                        dependencies = if (true) secondaryManifests.files.map { object : ManifestProvider {
                            override fun getManifest() = it
                            override fun getName() = null
                        } } else listOf(),
                        /* Not sure what this is but it can be empty. */
                        navigationJsons = listOf(),
                        /* Probably something about feature modules? Ignore */
                        featureName = null,
                        /* Need to apply the libraryVariant package name */
                        packageOverride = variant.applicationId,
                        /* Version data */
                        /* The merged flavor represents all flavors plus the default config. */
                        versionCode = variant.mergedFlavor.versionCode ?: 1, // Should we inspect the buildType as well?
                        versionName = variant.mergedFlavor.versionName ?: "", // Should we inspect the buildType as well?
                        minSdkVersion = variant.mergedFlavor.minSdkVersion?.apiString,
                        targetSdkVersion = variant.mergedFlavor.targetSdkVersion?.apiString,
                        maxSdkVersion = variant.mergedFlavor.maxSdkVersion,
                        /* The output destination */
                        outMergedManifestLocation = primaryManifest.get().absolutePath,
                        /* Extra outputs that can probably be null. */
                        outAaptSafeManifestLocation = null,
                        /* Either LIBRARY or APPLICATION. When using LIBRARY we can't add lib dependencies */
                        mergeType = if (true) ManifestMerger2.MergeType.APPLICATION else ManifestMerger2.MergeType.LIBRARY,
                        /* Manifest placeholders. Doing this the way the library manifest does. */
                        placeHolders = variant.mergedFlavor.manifestPlaceholders.also {
                            it.putAll(variant.buildType.manifestPlaceholders)
                        },
                        /* Optional features to be enabled. */
                        optionalFeatures = setOf(ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT),
                        /* Not sure, but it's empty in the lib processor */
                        dependencyFeatureNames = setOf(),
                        /* Output file with diagnostic info. I think. */
                        reportFile = target.greaseDir.file("manifest_report.txt"),
                        /* Logging */
                        logger = LoggerWrapper(target.logger)
                    )
                }
            }
            processManifestTask.configure { finalizedBy(reprocessManifestTask) }
        }
    }

    /**
     * (1) A possible approach for JNI is to resolve the libraries in a variant-specific folder,
     * then find a way to pass them to AGP.
     * The task that we probably want to inject into is [LibraryJniLibsTask], which copies
     * all .so files into a specific folder. We want it to be finalized by our extra-copy task.
     * The task is named "copy***JniLibsProjectAndLocalJars" and is not exposed, we must use its name.
     *
     * (2) Another approach is to resolve the libraries in a variant-specific folder,
     * then add that folder to the variant-specific source set in the jniLibs file collection.
     * In this case we must perform the copy before, probably in [MergeSourceSetFolders.MergeJniLibFoldersCreationAction].
     * Task is called "merge***JniLibFolders" and merges the source sets JNI folders together.
     * These binaries will go through symbol stripping again, which shouldn't be done for lib .sos,
     * but probably it's not too much of an issue.
     *
     * Notes on source sets: each build type (debug, release), flavor and variant has its own source set.
     * Each variant then has multiple source sets, based on its build type and flavors.
     * Basically, source sets reflect the structure of our configurations. Call gradlew sourceSets to see.
     */
    private fun configureVariantJniLibs(
        target: Project,
        variant: LibraryVariant,
        configuration: Configuration,
        logger: Logger
    ) {
        val log = logger.child("configureVariantJniLibs")
        log.i { "Configuring variant ${variant.name}..." }

        val anchorTaskName = nameOf("copy", variant.name, "JniLibsProjectAndLocalJars")
        val extractorTask = target.tasks.register(anchorTaskName.greasify()) {
            val anchorTask = target.tasks[anchorTaskName] as LibraryJniLibsTask
            inputs.files(configuration.artifactsOf(AndroidArtifacts.ArtifactType.JNI))
            // Option 1: use the directory property. This fails, Gradle can't create this task
            // because it is already the output directory of someone else.
            // outputs.dir(anchorTask.outputDirectory)
            // Option 2: resolve the directory. I think that in this case we MUST add a dependsOn()
            // or we won't be sure that the directory is available.
            dependsOn(anchorTask)
            outputs.dir(anchorTask.outputDirectory.get())
            log.i { "Configured output directory to be ${anchorTask.outputDirectory.get()}." }
            // Option 3: declare the exact files we'll be copying. This was valuable in my opinion,
            // But this configuration is causing deadlocks. Asked about it here
            // https://discuss.gradle.org/t/task-outputs-deadlock-how-to-declare-lazy-outputs-based-on-lazy-inputs/37107
            /* outputs.files(inputs.files.elements.map {
                log.i { "Configuring outputs - input artifacts were resolved, ${it.size}. Mapping them to output files." }
                val outputRoot = anchorTask.outputDirectory.get()
                val outputFiles = it.flatMap { inputRoot ->
                    val inputSharedLibraries = inputRoot.asFile.listSharedLibrariesRecursive()
                    log.i { "Configuring outputs - found ${inputSharedLibraries.size} libraries in ${inputRoot.asFile}, mapping to ${outputRoot.asFile}..." }
                    inputSharedLibraries.map { sharedLibrary ->
                        log.i { "Configuring outputs - mapping shared library ${sharedLibrary.toRelativeString(inputRoot.asFile)}..." }
                        sharedLibrary.moveHierarchy(inputRoot.asFile, outputRoot.asFile)
                    }
                }
                // Spread files and create a new collection, so that we return Provider<FileCollection>
                // to the outputs. This is also useful to lose the task dependency information that
                // we don't want to carry over from inputs to outputs.
                target.files(*outputFiles.toTypedArray())
            }) */

            doFirst {
                log.i { "Executing for variant ${variant.name} and ${inputs.files.files.size} roots..." }
                inputs.files.files.forEach { inputRoot ->
                    log.i { "Found shared libraries root: $inputRoot" }
                    val outputRoot = anchorTask.outputDirectory.get().asFile
                    val sharedLibraries = inputRoot.listFilesRecursive("so")
                    sharedLibraries.forEach {
                        log.i { "Copying ${it.toRelativeString(inputRoot)} from inputRoot=$inputRoot to outputRoot=$outputRoot..." }
                        target.copy {
                            from(it)
                            into(it.relocate(inputRoot, outputRoot).parentFile)
                        }
                    }
                }
            }
        }
        target.tasks.configureEach {
            if (name == anchorTaskName) {
                finalizedBy(extractorTask)
            }
        }
    }

    /**
     * AARs ship with a file called R.txt which already includes all resource ids from dependencies,
     * so we shouldn't probably do nothing about it as it comes for free.
     *
     * There are many tasks related to resources that are part of the pipeline.
     * We indicate by "<>" the variant name:
     * 1. generate<>BuildConfig: See [GenerateBuildConfig], [BuildConfigGenerator].
     *    This task generates the BuildConfig class in generated/source/buildConfig/<>/ .
     *    We don't need to inject here and also this file is not even part of the final AAR in my first tests.
     * 2. generate<>ResValues: See [GenerateResValues], [ResValueGenerator].
     *    This task generates the "ResValues" in generated/res/resValues/<>. I don't know what
     *    this means because the folder is empty with the current project.
     * 3. generate<>Resources: Can't find sources for this and it does nothing. Will probably be removed.
     * 4. package<>Resources: See [MergeResources], [TaskManager.MergeType.PACKAGE].
     *    This task takes resources presumably from the resource set, and processes them into
     *    /intermediates/packaged_res/<>/. This folder will contain the xml files hierarchy, though a bit processed:
     *    png might be crunched, values might be merged together in a single resource file, ...
     * 5. parse<>LocalResources: See [ParseLibraryResourcesTask].
     *    This task takes input from the previous task, /intermediates/packaged_res/<>/, and
     *    outputs a R-def.txt file in intermediates/local_only_symbol_list/<>/R-def.txt.
     *    This file contains the R attributes of the current project (no dependencies) and, as javadocs say,
     *    will be used by the next task (generate<>RFile) along with all the R.txt files from the
     *    dependencies, to create the exported R.txt in the final AAR. This is very good for us,
     *    because there's nothing to do here. The AAR R.txt already includes the dependencies.
     * 6. generate<>RFile: See [GenerateLibraryRFileTask].
     *    This task is returned by variant.outputs.first().processResourcesProvider. Inputs:
     *    - localResourcesFile is intermediates/local_only_symbol_list/<>/R-def.txt from parse<>LocalResources.
     *    - dependencies is all r.txt files from dependencies, automatically picked up.
     *    Outputs:
     *    - rClassOutputJar is a jar in intermediates/compile_only_not_namespaced_r_class_jar/debug/R.jar.
     *      It contains the compiled R class (in .class files). I don't know who uses this.
     *    - textSymbolOutputFileProperty is a txt in intermediates/compile_symbol_list/debug/R.txt.
     *      I think this will be part of the final AAR.
     *    - symbolsWithPackageNameOutputFile: intermediates/symbol_list_with_package_name/debug/package-aware-r.txt
     *      This is like the R-def.txt file mentioned above, but the first line is the package name.
     *      Again, I don't know who will be using this and why - it's not part of the final AAR file.
     * 7. bundle<>Aar: See [BundleAar], [InternalArtifactType.PACKAGED_RES], [InternalArtifactType.PUBLIC_RES]
     *    This is a simple Zip task that takes input from many other tasks and zips them into the aar.
     *    Specifically it reads from PACKAGED_RES which seems to be promising (it's in the 'res' folder).
     *    This should be the output of task 4, package<>Resources, see MergeType.PACKAGE there.
     *
     * Additionally there are a couple of tasks that are only run in release builds, after bundling
     * the AAR file, so likely useless for us:
     * 1. merge<>Resources: See [MergeResources], [TaskManager.MergeType.MERGE].
     *    THIS IS ONLY RUN IN RELEASE BUILDS.
     *    This task takes resources presumably from the resource set, and processes them into
     *    /intermediates/res/merged/<>/. This filder will contain the xml files hierarchy, though a bit processed:
     *    png might be crunched, values might be merged together in a single resource file, ...
     *    This task is returned by variant.mergeResourcesProvider .
     * 2. verify<>Resources: See [VerifyLibraryResourcesTask].
     *    THIS IS ONLY RUN IN RELEASE BUILDS.
     *    This task takes output files from the previous step, and processes them into the output
     *    /intermediates/res/compiled/<>/. They end up literally in a compiled form (*.flat files),
     *    which I don't know the purpose - maybe used in APKs and release builds want to test that
     *    this will work when AAR is imported in an app.
     */
    private fun configureVariantResources(
        target: Project,
        variant: LibraryVariant,
        configuration: Configuration,
        logger: Logger
    ) {
        val log = logger.child("configureVariantResources")
        log.i { "Configuring variant ${variant.name}..." }
        val anchorTaskName = nameOf("package", variant.name, "Resources")
        val greaseTask = target.tasks.register(anchorTaskName.greasify()) {
            val anchorTask = target.tasks[anchorTaskName] as MergeResources
            dependsOn(anchorTask)
            inputs.files(configuration.artifactsOf(AndroidArtifacts.ArtifactType.ANDROID_RES))
            outputs.dir(anchorTask.outputDir.get())
            doFirst {
                log.i { "Executing for variant ${variant.name} and ${inputs.files.files.size} roots..." }
                inputs.files.files.forEach { inputRoot ->
                    log.i { "Found resources root: $inputRoot" }
                    val outputRoot = anchorTask.outputDir.get().asFile
                    val outputPrefix = inputRoot.parentFile.name.map { char ->
                        if (char.isLetterOrDigit()) char else '_'
                    }.joinToString(separator = "")
                    val resources = inputRoot.listFilesRecursive("xml")
                    resources.forEach {
                        log.i { "Copying ${it.toRelativeString(inputRoot)} (prefix=$outputPrefix) from inputRoot=$inputRoot to outputRoot=$outputRoot..." }
                        target.copy {
                            from(it)
                            into(it.relocate(inputRoot, outputRoot).parentFile)
                            rename { "${outputPrefix}_$it" }
                        }
                    }
                }
            }
        }
        target.tasks.configureEach {
            if (name == anchorTaskName) {
                finalizedBy(greaseTask)
            }
        }
    }

    /**
     * Interesting tasks, from last to first in the pipeline:
     *
     * 1. sync<>LibJars: See [LibraryAarJarsTask].
     *    This task takes the project compiled .class files, and merges them with local JARs,
     *    to create a single JAR that will be part of the final AAR bundle.
     *    A possible approach for us is to find a way to pass our JARs as input to this task.
     *    - input mainScopeClassFiles: intermediates/javac/<>/classes (contains .class hierarchy)
     *    - output mainClassLocation: intermediates/aar_main_jar/debug/classes.jar (zipped classes from input)
     *    - output localJarsLocation: intermediates/aar_libs_directory/debug/libs (only used for local jars)
     * 2. compile<>JavaWithJavac: See [JavaCompileCreationAction].
     *    This task does the actual compilation of the project by calling javac.
     *    A possible approach for us is to write our classes in the same output folder of this task.
     *    - output destinationDir: intermediates/javac/<>/classes
     * 3. javaPreCompile<>: See [JavaPreCompileTask].
     *    It seems to do some lightweight operation before calling compile<>JavaWithJavac.
     */
    private fun configureVariantSources(
        target: Project,
        variant: LibraryVariant,
        configuration: Configuration,
        logger: Logger
    ) {
        val log = logger.child("configureVariantSources")
        log.i { "Configuring variant ${variant.name}..." }
        val compileTask = variant.javaCompileProvider // compile<>JavaWithJavac
        val bundleTaskName = nameOf("sync", variant.name, "LibJars")
        val greaseTask = target.tasks.register(compileTask.name.greasify()) {
            dependsOn(compileTask)
            // There are many options here. PROCESSED_JAR, PROCESSED_AAR, CLASSES, CLASSES_JAR ...
            // CLASSES_JAR seems to be the best though it's not clear if it's jetified or not.
            inputs.files(configuration.artifactsOf(AndroidArtifacts.ArtifactType.CLASSES_JAR))
            outputs.dir(compileTask.get().destinationDirectory.get())
            doFirst {
                log.i { "Executing for variant ${variant.name} and ${inputs.files.files.size} roots..." }
                inputs.files.files.forEach { inputJar ->
                    log.i { "Found JAR root: $inputJar" }
                    val inputFiles = target.zipTree(inputJar).matching { include("**/*.class") }
                    target.copy {
                        from(inputFiles)
                        into(outputs.files.singleFile)
                    }
                }
            }
        }
        compileTask.configure { finalizedBy(greaseTask) }
        target.tasks.configureEach {
            if (name == bundleTaskName) dependsOn(greaseTask)
        }
    }

    /**
     * Interesting tasks:
     * 1. generate<>Assets: See [MutableTaskContainer].
     *    This task does nothing and is scheduled for removal. Only usage is for next task do depend on it.
     * 2. package<>Assets: See [MergeSourceSetFolders.LibraryAssetCreationAction], [MergeSourceSetFolders].
     *    Being a library module this will not package the dependencies assets (includeDependencies=false),
     *    though in theory the task is capable of doing so. The output directory is currently
     *    /intermediates/library_assets/<>/out/.
     */
    private fun configureVariantAssets(
        target: Project,
        variant: LibraryVariant,
        configuration: Configuration,
        logger: Logger
    ) {
        val log = logger.child("configureVariantAssets")
        log.i { "Configuring variant ${variant.name}..." }
        val compileTask = variant.mergeAssetsProvider // package<>Assets
        val greaseTask = target.tasks.register(compileTask.name.greasify()) {
            dependsOn(compileTask)
            inputs.files(configuration.artifactsOf(AndroidArtifacts.ArtifactType.ASSETS))
            outputs.dir(compileTask.get().outputDir.get())
            doFirst {
                log.i { "Executing for variant ${variant.name} and ${inputs.files.files.size} roots..." }
                inputs.files.files.forEach { inputRoot ->
                    log.i { "Found asset folder root: $inputRoot" }
                    // TODO crash/warn if any asset is overwritten
                    val inputFiles = target.fileTree(inputRoot)
                    target.copy {
                        from(inputFiles)
                        into(outputs.files.singleFile)
                    }
                }
            }
        }
        compileTask.configure { finalizedBy(greaseTask) }
    }

    /**
     * Interesting tasks:
     * 1. merge<>GeneratedProguardFiles: See [MergeGeneratedProguardFilesCreationAction], [MergeFileTask].
     *    I think this task generates a proguard file out of Keep annotations, for example.
     *    In the current sample project, the output is always empty.
     *    - input: none
     *    - output: /intermediates/generated_proguard_file/<>/proguard.txt .
     * 2. merge<>ConsumerProguardFiles: See [MergeConsumerProguardFilesTask], also a [MergeFileTask].
     *    - input: the output from the previous task, /intermediates/generated_proguard_file/<>/proguard.txt .
     *    - input: all files mentioned in consumerProguardFiles() DSL block.
     *    - output: /intermediates/merged_consumer_proguard_file/<>/proguard.txt .
     * 3. generate<>LibraryProguardRules: See [GenerateLibraryProguardRulesTask].
     *    ONLY CALLED IN MINIFIED BUILDS.
     *    This tasks generates a rules file for AAPT compilation aapt_rules.txt. I imagine that it
     *    It reads from manifests/resources and it seems to be some merged rules including dependencies.
     *    Output is /intermediates/aapt_proguard_file/<>/aapt_rules.txt.
     * 4. minify<>WithR8: See [R8Task]
     *    ONLY CALLED IN MINIFIED BUILDS.
     *    This task does the actual minification.
     *
     * The approach we'll take here is to change the inputs of the merge<>ConsumerProguardFiles
     * to include the extra files.
     */
    private fun configureVariantProguardFiles(
        target: Project,
        variant: LibraryVariant,
        configuration: Configuration,
        logger: Logger
    ) {
        val log = logger.child("configureVariantProguardFiles")
        log.i { "Configuring variant ${variant.name}..." }
        target.tasks.configureEach {
            if (name == nameOf("merge", variant.name, "ConsumerProguardFiles")) {
                val task = this as MergeFileTask
                // UNFILTERED_PROGUARD_RULES, FILTERED_PROGUARD_RULES, AAPT_PROGUARD_RULES, ...
                // UNFILTERED_PROGUARD_RULES is output of the AarTransform. FILTERED_PROGUARD_RULES
                // is processed by another transform and is probably what we want in the end.
                val extraInputs = configuration.artifactsOf(AndroidArtifacts.ArtifactType.FILTERED_PROGUARD_RULES)
                val inputs = task.inputFiles.plus(extraInputs)
                task.inputFiles = inputs
                task.doFirst {
                    require (task.inputFiles === inputs) {
                        "Input proguard files have been changed after our configureEach callback!"
                    }
                    log.i { "Input proguard files: ${inputFiles.files.joinToString()}" }
                }
            }
        }
    }
}
