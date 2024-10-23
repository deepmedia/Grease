package io.deepmedia.tools.grease

import com.android.build.api.component.analytics.AnalyticsEnabledVariant
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.api.variant.impl.getApiString
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.LibraryTaskManager
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.TaskManager
import com.android.build.gradle.internal.component.ComponentCreationConfig
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.res.GenerateLibraryRFileTask
import com.android.build.gradle.internal.res.ParseLibraryResourcesTask
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.MutableTaskContainer
import com.android.build.gradle.internal.tasks.LibraryJniLibsTask
import com.android.build.gradle.internal.tasks.MergeFileTask
import com.android.build.gradle.internal.tasks.Workers
import com.android.build.gradle.internal.tasks.factory.TaskCreationAction
import com.android.build.gradle.internal.tasks.manifest.mergeManifests
import com.android.build.gradle.tasks.BundleAar
import com.android.build.gradle.tasks.MergeResources
import com.android.build.gradle.tasks.ProcessLibraryManifest
import com.android.ide.common.resources.CopyToOutputDirectoryResourceCompilationService
import com.android.ide.common.symbols.parseManifest
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.ManifestProvider
import com.android.utils.appendCapitalized
import com.github.jengelman.gradle.plugins.shadow.ShadowStats
import com.github.jengelman.gradle.plugins.shadow.relocation.RelocatePathContext
import com.github.jengelman.gradle.plugins.shadow.relocation.Relocator
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.kotlin.dsl.support.unzipTo
import org.gradle.kotlin.dsl.support.zipTo
import java.io.File

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

    override fun apply(target: Project) {
        target.plugins.withId("com.android.library") {
            val log = Logger(target, "grease")
            val android = target.extensions.getByType(LibraryExtension::class.java)
            val androidComponents = target.extensions.getByType(AndroidComponentsExtension::class.java)
            val greaseExtension = target.extensions.create("grease", GreaseExtension::class.java)

            debugGreasyConfigurationHierarchy(target, log)

            // Create the configurations.
            fun createConfigurations(isTransitive: Boolean) {
                target.createRootConfiguration(isTransitive, log)
                target.createProductFlavorConfigurations(androidComponents, isTransitive, log)
                target.createBuildTypeConfigurations(android.buildTypes, isTransitive, log)
                target.createVariantConfigurations(androidComponents, isTransitive, log)
            }
            createConfigurations(false)
            createConfigurations(true)

            fun configure(variant: Variant, runtime: List<Configuration>, api: List<Configuration>) {
                configureVariantManifest(target, variant, runtime, log)
                configureVariantJniLibs(target, variant, runtime, log)
                configureVariantAidlParcelables(target, variant, runtime + api, log)
                configureVariantResources(target, variant, runtime, log)
                configureVariantSources(target, variant, runtime, greaseExtension, log)
                configureVariantAssets(target, variant, runtime, log)
                configureVariantProguardFiles(target, variant, runtime, log)
            }
            // Configure all variants.
            androidComponents.onVariants { variant ->
                val childLog = log.child("configureVariant")
                childLog.d { "Configuring variant ${variant.name}..." }
                target.afterEvaluate {
                    configure(
                        variant = variant,
                        runtime = listOf(target.greaseOf(variant), target.greaseOf(variant, true)),
                        api = listOf(target.greaseApiOf(variant), target.greaseApiOf(variant, true))
                    )
                }
            }
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
        variant: Variant,
        configurations: List<Configuration>,
        logger: Logger
    ) {
        val log = logger.child("configureVariantManifest")
        log.d { "Configuring variant output ${variant.name}..." }

        val componentConfig = variant.componentCreationConfigOrThrow()

        target.locateTask(componentConfig.resolveTaskName("process", "Manifest"))?.configure {
            val processManifestTask = this as ProcessLibraryManifest

            val extraManifests = configurations.artifactsOf(AndroidArtifacts.ArtifactType.MANIFEST)
            dependsOn(extraManifests)

            // After the file is copied we can go on with the actual manifest merging.
            // This task will overwrite the original AndroidManifest.xml.
            doLast {

                val reportFile = target.greaseBuildDir.get().file("manifest_report.txt")
                target.delete(reportFile)

                // To retrieve the secondary files, we must query the configuration artifacts.
                val primaryManifest = processManifestTask.manifestOutputFile.asFile // overwrite

                val mergedFlavor = componentConfig.oldVariantApiLegacySupport?.mergedFlavor

                log.d { "Merging manifests... primary=${primaryManifest.get()}, secondary=${extraManifests.files.joinToString()}" }

                mergeManifests(
                    mainManifest = primaryManifest.get(),
                    /* Overlays are other manifests from the current 'source set' of this lib. */
                    manifestOverlays = if (false) extraManifests.files.toList() else listOf(),
                    /* Dependencies are other manifests from other libraries, which should be our
                         * case but it's not clear if we can use them with the LIBRARY merge type. */
                    dependencies = if (true) extraManifests.files.map {
                        object : ManifestProvider {
                            override fun getManifest() = it
                            override fun getName() = null
                        }
                    } else listOf(),
                    namespace = variant.namespace.get(),
                    /* Not sure what this is but it can be empty. */
                    navigationJsons = listOf(),
                    /* Probably something about feature modules? Ignore */
                    featureName = null,
                    /* Need to apply the libraryVariant package name */
                    packageOverride = componentConfig.applicationId.get(),
                    /* Version data */
                    /* The merged flavor represents all flavors plus the default config. */
                    versionCode = mergedFlavor?.versionCode,
                    versionName = mergedFlavor?.versionName,
                    minSdkVersion = componentConfig.minSdk.getApiString(),
                    targetSdkVersion = mergedFlavor?.targetSdkVersion?.apiString,
                    maxSdkVersion = mergedFlavor?.maxSdkVersion,
                    testOnly = false,
                    extractNativeLibs = null,
                    generatedLocaleConfigAttribute = null,
                    profileable = false,
                    /* The output destination */
                    outMergedManifestLocation = primaryManifest.get().absolutePath,
                    /* Extra outputs that can probably be null. */
                    outAaptSafeManifestLocation = null,
                    /* Either LIBRARY or APPLICATION. When using LIBRARY we can't add lib dependencies */
                    mergeType = ManifestMerger2.MergeType.FUSED_LIBRARY,
                    /* Manifest placeholders. Doing this the way the library manifest does. */
                    placeHolders = mergedFlavor?.manifestPlaceholders.orEmpty() + variant.manifestPlaceholders.get(),
                    /* Optional features to be enabled. */
                    optionalFeatures = setOf(ManifestMerger2.Invoker.Feature.NO_PLACEHOLDER_REPLACEMENT),
                    /* Not sure, but it's empty in the lib processor */
                    dependencyFeatureNames = setOf(),
                    /* Output file with diagnostic info. I think. */
                    reportFile = reportFile.asFile,
                    /* Logging */
                    logger = LoggerWrapper(target.logger)
                )
            }
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
        variant: Variant,
        configurations: List<Configuration>,
        logger: Logger
    ) {
        val log = logger.child("configureVariantJniLibs")
        log.d { "Configuring variant ${variant.name}..." }

        val creationConfig = variant.componentCreationConfigOrThrow()

        target.locateTask(creationConfig.resolveTaskName("copy", "JniLibsProjectAndLocalJars"))?.configure {
            val copyJniTask = this as LibraryJniLibsTask
            val extraJniLibs = configurations.artifactsOf(AndroidArtifacts.ArtifactType.JNI)
            dependsOn(extraJniLibs)

            fun injectJniLibs() {
                log.d { "Executing for variant ${variant.name} and ${extraJniLibs.files.size} roots..." }
                extraJniLibs.files.forEach { inputRoot ->
                    log.d { "Found shared libraries root: $inputRoot" }
                    val outputRoot = copyJniTask.outputDirectory.get().asFile
                    val sharedLibraries = inputRoot.listFilesRecursive("so")
                    sharedLibraries.forEach {
                        log.d { "Copying ${it.toRelativeString(inputRoot)} from inputRoot=$inputRoot to outputRoot=$outputRoot..." }
                        target.copy {
                            from(it)
                            into(it.relocate(inputRoot, outputRoot).parentFile)
                        }
                    }
                }
            }

            val files = projectNativeLibs.get().files().files + localJarsNativeLibs?.files.orEmpty()
            if (files.isNotEmpty()) {
                doLast { injectJniLibs() }
            } else {
                injectJniLibs()
            }
        }
    }

    private fun configureVariantAidlParcelables(
        target: Project,
        variant: Variant,
        configurations: List<Configuration>,
        logger: Logger
    ) {
        val log = logger.child("configureVariantAidlParcelables")
        log.d { "Configuring variant ${variant.name}..." }
        val creationConfig = variant.componentCreationConfigOrThrow()
        creationConfig.taskContainer.aidlCompileTask?.configure {
            val extraAidlFiles = configurations.artifactsOf(AndroidArtifacts.ArtifactType.AIDL)
            dependsOn(extraAidlFiles)
            fun injectAidlFiles() {
                log.d { "Executing for variant ${variant.name} and ${extraAidlFiles.files.size} roots..." }
                extraAidlFiles.files.forEach { inputRoot ->
                    log.d { "Found aidl parcelables files root: $inputRoot" }
                    val inputFiles = target.fileTree(inputRoot)
                    target.copy {
                        from(inputFiles)
                        into(packagedDir.get())
                    }
                }
            }
            if (sourceFiles.get().files.isNotEmpty()) {
                doLast { injectAidlFiles() }
            } else {
                injectAidlFiles()
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
        variant: Variant,
        configurations: List<Configuration>,
        logger: Logger
    ) {

        val log = logger.child("configureVariantResources")
        log.d { "Configuring variant ${variant.name}..." }
        val creationConfig = variant.componentCreationConfigOrThrow()

        target.locateTask(creationConfig.resolveTaskName("package", "Resources"))?.configure {
            this as MergeResources

            val resourcesMergingWorkdir = target.greaseBuildDir.get().dir(variant.name).dir("resources")
            val mergedResourcesDir = resourcesMergingWorkdir.dir("merged")
            val blameDir = resourcesMergingWorkdir.dir("blame")
            val extraAndroidRes = configurations.artifactsOf(AndroidArtifacts.ArtifactType.ANDROID_RES)
            dependsOn(extraAndroidRes)

            outputs.upToDateWhen { false } // always execute

            fun injectResources() {
                target.delete(resourcesMergingWorkdir)

                val executorFacade = Workers.withGradleWorkers(
                    creationConfig.services.projectInfo.path,
                    path,
                    workerExecutor,
                    analyticsService
                )
                log.d { "Merge additional resources into $mergedResourcesDir" }
                mergeResourcesWithCompilationService(
                    resCompilerService = CopyToOutputDirectoryResourceCompilationService,
                    incrementalMergedResources = mergedResourcesDir.asFile,
                    mergedResources = outputDir.asFile.get(),
                    resourceSets = extraAndroidRes.files.toList(),
                    minSdk = minSdk.get(),
                    aaptWorkerFacade = executorFacade,
                    blameLogOutputFolder = blameDir.asFile,
                    logger = this.logger
                )
            }

            if (inputs.hasInputs) {
                doLast { injectResources() }
            } else {
                injectResources()
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
        variant: Variant,
        configurations: List<Configuration>,
        greaseExtension: GreaseExtension,
        logger: Logger
    ) {
        val log = logger.child("configureVariantSources")
        log.d { "Configuring variant ${variant.name}..." }

        val creationConfig = variant.componentCreationConfigOrThrow()

        val workdir = target.greaseBuildDir.get().dir(variant.name)
        workdir.asFile.deleteRecursively()
        val aarExtractWorkdir = workdir.dir("extract").dir("aar")
        val jarExtractWorkdir = workdir.dir("extract").dir("jar")
        val jarFileName = "classes.jar"

        val bundleLibraryTask = creationConfig.taskContainer.bundleLibraryTask

        val greaseExpandTask = target.tasks.locateOrRegisterTask(
            creationConfig.resolveTaskName("extract", "Aar").greasify(),
        ) {
            val bundleAar = bundleLibraryTask?.get() as BundleAar

            outputs.upToDateWhen { false } // always execute

            inputs.file(bundleAar.archiveFile)
            outputs.file(aarExtractWorkdir.file(jarFileName).asFile)

            doFirst {
                target.delete(aarExtractWorkdir)
                unzipTo(aarExtractWorkdir.asFile, bundleAar.archiveFile.get().asFile)
            }
        }

        val greaseProcessTask = target.tasks.locateOrRegisterTask(
            creationConfig.resolveTaskName("process", "Jar").greasify(),
        ) {

            // There are many options here. PROCESSED_JAR, PROCESSED_AAR, CLASSES, CLASSES_JAR ...
            // CLASSES_JAR seems to be the best though it's not clear if it's jetified or not.
            val extraJars = configurations.artifactsOf(AndroidArtifacts.ArtifactType.CLASSES_JAR)
            dependsOn(extraJars)
            dependsOn(greaseExpandTask)

            outputs.upToDateWhen { false } // always execute
            inputs.files(greaseExpandTask.get().outputs.files)
            outputs.dir(jarExtractWorkdir)

            fun injectClasses(inputJar: File) {
                log.d { "Processing inputJar=$inputJar outputDir=${jarExtractWorkdir}..." }
                val inputFiles = target.zipTree(inputJar).matching { include("**/*.class", "**/*.kotlin_module") }
                target.copy {
                    from(inputFiles)
                    into(jarExtractWorkdir)
                }
            }

            doFirst {
                target.delete(jarExtractWorkdir)
                log.d { "Executing merging for variant ${variant.name} and ${extraJars.files.size} roots..." }
                extraJars.files.forEach(::injectClasses)
                aarExtractWorkdir.file(jarFileName).asFile.run(::injectClasses)
            }
        }

        val greaseShadowTask = target.tasks.locateOrRegisterTask(
            creationConfig.resolveTaskName("shadow", "Aar").greasify(),
            ShadowJar::class.java
        ) {
            val compileTask = creationConfig.taskContainer.javacTask
            val extraManifests = configurations.artifactsOf(AndroidArtifacts.ArtifactType.MANIFEST)
            val greaseShadowDir = workdir.dir("shadow")
            val bundleAar = bundleLibraryTask?.get() as BundleAar

            outputs.upToDateWhen { false } // always execute

            dependsOn(extraManifests)
            dependsOn(greaseExpandTask)
            dependsOn(greaseProcessTask)

            archiveFileName.set(jarFileName)
            destinationDirectory.set(greaseShadowDir)

            from(greaseProcessTask.get().outputs)
            val addedPackagesNames = mutableSetOf<String>()

            doFirst {
                target.delete(greaseShadowDir)
                greaseShadowDir.asFile.mkdirs()

                log.d { "Executing shadowing for variant ${variant.name} and ${extraManifests.files.size} roots with namespace ${variant.namespace.get()}..." }
                extraManifests.forEach { inputFile ->
                    val manifestData = parseManifest(inputFile)
                    manifestData.`package`?.let { fromPackageName ->
                        log.d { "Processing R class from $fromPackageName manifestInput=${inputFile.path} outputDir=${compileTask.get().destinationDirectory.get()}..." }
                        relocate(RClassRelocator(fromPackageName, variant.namespace.get(), log))
                    }
                }

                val relocationPrefix = greaseExtension.prefix.get()
                if (relocationPrefix.isNotEmpty()) {
                    val sequence = greaseProcessTask.get().outputs.files.asSequence() + aarExtractWorkdir.dir("aidl").asFile
                    sequence
                        .flatMap { inputFile -> inputFile.packageNames }
                        .distinct()
                        .map { packageName -> packageName to "${relocationPrefix}.$packageName" }
                        .distinct()
                        .filterNot { (packageName, _) -> addedPackagesNames.any(packageName::contains) }
                        .forEach { (packageName, newPackageName) ->
                            log.d { "Relocate package from $packageName to $newPackageName" }
                            relocate(packageName, newPackageName)
                            addedPackagesNames += packageName
                        }
                }

                greaseExtension.relocators.get().forEach<Relocator?>(::relocate)
                greaseExtension.transformers.get().forEach(::transform)
                transform(KotlinModuleShadowTransformer(logger.child("kotlin_module")))
            }

            doLast {
                val shadowJar = greaseShadowDir.file(jarFileName).asFile
                log.d { "Copy shaded inputJar=${shadowJar} outputDir=$aarExtractWorkdir..." }
                target.copy {
                    from(shadowJar)
                    into(aarExtractWorkdir)
                }

                replacePackagesInManifest(
                    aarExtractWorkdir.file("AndroidManifest.xml").asFile,
                    greaseShadowDir.file("AndroidManifest.xml").asFile,
                    relocators,
                    target,
                )

                relocateAidlFiles(
                    aarExtractWorkdir.dir("aidl"),
                    greaseShadowDir.dir("aidl"),
                    relocators,
                    target,
                )

                val oldArchive = bundleAar.archiveFile.get().asFile
                val archiveParent = oldArchive.parentFile
                val archiveName = oldArchive.name
                target.delete(oldArchive)
                zipTo(archiveParent.file(archiveName), aarExtractWorkdir.asFile)
            }
        }

        bundleLibraryTask?.configure {
            outputs.upToDateWhen { false }
            finalizedBy(greaseShadowTask)
        }
        greaseExpandTask.configure {
            mustRunAfter(bundleLibraryTask)
            finalizedBy(greaseProcessTask)
        }
        greaseProcessTask.configure {
            mustRunAfter(bundleLibraryTask)
            finalizedBy(greaseShadowTask)
        }
        greaseShadowTask.configure {
            mustRunAfter(bundleLibraryTask)
        }
        target.plugins.withId("org.gradle.maven-publish") {
            target.tasks.withType(PublishToMavenRepository::class.java) {
                val publication = publication
                if (publication is DefaultMavenPublication) {
                    if (creationConfig.name == publication.component.get().name) {
                        dependsOn(greaseShadowTask)
                    }
                }
            }
        }
    }

    private fun replacePackagesInManifest(
        input: File,
        output: File,
        relocators: List<Relocator>,
        target: Project,
    ) {
        val reader = input.bufferedReader()
        val writer = output.bufferedWriter()
        reader.useLines { strings ->
            strings
                .map { string ->
                    relocators
                        .filterNot { it is RClassRelocator }
                        .fold(string) { acc, relocator ->
                            relocator.applyToSourceContent(acc)
                        }
                }.forEach {
                    writer.write(it)
                    writer.newLine()
                }
        }
        writer.close()

        target.copy {
            from(output)
            into(input.parentFile)
        }
    }


    private fun relocateAidlFiles(
        inputDir: Directory,
        outputDir: Directory,
        relocators: List<Relocator>,
        target: Project,
    ) {
        if (inputDir.asFileTree.isEmpty) return

        inputDir.asFileTree.forEach { file ->
            val relocatePathContext = RelocatePathContext().apply {
                stats = ShadowStats()
            }
            val reader = file.bufferedReader()
            val relocatedPath = relocators
                .filterNot { it is RClassRelocator }
                .fold(file.toRelativeString(inputDir.asFile)) { acc, relocator ->
                    relocator.relocatePath(relocatePathContext.apply { path = acc })
                }
            val writer = outputDir.asFile.file(relocatedPath).bufferedWriter()
            reader.useLines { strings ->
                strings
                    .map { string ->
                        relocators
                            .filterNot { it is RClassRelocator }
                            .fold(string) { acc, relocator ->
                                relocator.applyToSourceContent(acc)
                            }
                    }.forEach {
                        writer.write(it)
                        writer.newLine()
                    }
            }
            writer.close()
        }
        inputDir.asFile.deleteRecursively()
        target.copy {
            from(outputDir)
            into(inputDir)
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
        variant: Variant,
        configurations: List<Configuration>,
        logger: Logger
    ) {
        val log = logger.child("configureVariantAssets")
        log.d { "Configuring variant ${variant.name}..." }
        val creationConfig = variant.componentCreationConfigOrThrow()
        creationConfig.taskContainer.mergeAssetsTask.configure {
            val extraAssets = configurations.artifactsOf(AndroidArtifacts.ArtifactType.ASSETS)
            dependsOn(extraAssets)
            fun injectAssets() {
                log.d { "Executing for variant ${variant.name} and ${extraAssets.files.size} roots..." }
                extraAssets.files.forEach { inputRoot ->
                    log.d { "Found asset folder root: $inputRoot" }
                    val inputFiles = target.fileTree(inputRoot)
                    target.copy {
                        from(inputFiles)
                        into(outputDir.get())
                    }
                }
            }
            if (inputs.hasInputs) {
                doLast { injectAssets() }
            } else {
                injectAssets()
            }
        }
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
        variant: Variant,
        configurations: List<Configuration>,
        logger: Logger
    ) {
        val log = logger.child("configureVariantProguardFiles")
        log.d { "Configuring variant ${variant.name}..." }
        val creationConfig = variant.componentCreationConfigOrThrow()
        target.locateTask(creationConfig.resolveTaskName("merge", "ConsumerProguardFiles"))?.configure {
            val mergeFileTask = this as MergeFileTask
            // UNFILTERED_PROGUARD_RULES, FILTERED_PROGUARD_RULES, AAPT_PROGUARD_RULES, ...
            // UNFILTERED_PROGUARD_RULES is output of the AarTransform. FILTERED_PROGUARD_RULES
            // is processed by another transform and is probably what we want in the end.
            val extraInputs = configurations.artifactsOf(AndroidArtifacts.ArtifactType.FILTERED_PROGUARD_RULES)
            dependsOn(extraInputs)

            mergeFileTask.inputs.files(extraInputs + mergeFileTask.inputFiles.files)
            mergeFileTask.doFirst {
                log.d { "Input proguard files: ${mergeFileTask.inputs.files.joinToString()}" }
            }
        }
    }
}

private fun ComponentCreationConfig.resolveTaskName(prefix: String, suffix: String): String =
    prefix.appendCapitalized(name, suffix)

private fun Variant.componentCreationConfigOrThrow(): ComponentCreationConfig {
    return when (this) {
        is ComponentCreationConfig -> this
        is AnalyticsEnabledVariant -> this.delegate.componentCreationConfigOrThrow()
        else -> error("Could not find ComponentCreationConfig in $this.")
    }
}
