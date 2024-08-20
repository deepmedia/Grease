package io.deepmedia.tools.grease

import com.android.build.gradle.internal.LoggerWrapper
import com.android.ide.common.blame.MergingLog
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.MergedResourceWriter
import com.android.ide.common.resources.MergedResourceWriterRequest
import com.android.ide.common.resources.ResourceCompilationService
import com.android.ide.common.resources.ResourceMerger
import com.android.ide.common.resources.ResourceSet
import com.android.ide.common.workers.WorkerExecutorFacade
import org.gradle.api.logging.Logger
import java.io.File

internal fun mergeResourcesWithCompilationService(
    resCompilerService: ResourceCompilationService,
    incrementalMergedResources: File,
    mergedResources: File,
    resourceSets: List<File>,
    minSdk: Int,
    aaptWorkerFacade: WorkerExecutorFacade,
    blameLogOutputFolder: File,
    logger: Logger) {
    val mergedResourcesDir = mergedResources.also {
        it.mkdirs()
    }
    val sourcesResourceSet = ResourceSet(
        null, ResourceNamespace.RES_AUTO, null, false, null
    ).apply {
        addSources(resourceSets.reversed())
    }
    val resourceMerger = ResourceMerger(minSdk).apply {
        sourcesResourceSet.loadFromFiles(LoggerWrapper(logger))
        addDataSet(sourcesResourceSet)
    }
    aaptWorkerFacade.use { workerExecutorFacade ->
        resCompilerService.use { resCompilationService ->
            val mergeResourcesWriterRequest = MergedResourceWriterRequest(
                workerExecutor = workerExecutorFacade,
                rootFolder = mergedResourcesDir,
                publicFile = null,
                blameLog = getCleanBlameLog(blameLogOutputFolder),
                preprocessor = null,
                resourceCompilationService = resCompilationService,
                temporaryDirectory = incrementalMergedResources,
                dataBindingExpressionRemover = null,
                notCompiledOutputDirectory = null,
                pseudoLocalesEnabled = false,
                crunchPng = false,
                moduleSourceSets = emptyMap()
            )
            val writer = MergedResourceWriter(mergeResourcesWriterRequest)
            resourceMerger.mergeData(writer, true)
            resourceMerger.writeBlobTo(incrementalMergedResources, writer, false)
        }
    }
}

private fun getCleanBlameLog(blameLogOutputFolder: File): MergingLog {
    return MergingLog(blameLogOutputFolder)
}
