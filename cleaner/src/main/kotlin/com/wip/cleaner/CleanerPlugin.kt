package com.wip.cleaner

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.common.models.ChapterCleanerResult
import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.CleanerResult
import com.wip.common.models.ExecutionDevice
import com.wip.common.models.InpaintingUtils
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.ModelSpec
import com.wip.common.models.OnnxInferenceEngine
import com.wip.common.models.OnnxInferenceSession
import com.wip.common.models.VisionResult
import com.wip.common.models.sortedNaturally
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginAction
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginLoad
import org.wip.plugintoolkit.api.annotations.PluginLocks
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.RequiresLock

@PluginInfo(
    id = "com.wip.cleaner",
    name = "Cleaner",
    version = "1.0.1",
    description = "Inpaints and erases segmented text and artifacts from images using segmentation maps.",
    supportedOs = [OS.WINDOWS, OS.LINUX, OS.MACOS]
)
class CleanerPlugin {

    init {
        try {
            val registry = IIORegistry.getDefaultInstance()
            val providers = registry.getServiceProviders(javax.imageio.spi.ImageReaderSpi::class.java, false)
            while (providers.hasNext()) {
                val provider = providers.next()
                if (provider.javaClass.name == "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi") {
                    registry.deregisterServiceProvider(provider)
                }
            }
            registry.registerServiceProvider(WebPImageReaderSpi())
        } catch (e: Exception) {
            // Ignore WebP SPI registration failure
        }
    }

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[Cleaner] onLoad: Initializing Cleaner Plugin...")
        return Result.success(Unit)
    }

    @PluginLocks
    suspend fun checkLocks(context: PluginContext): Map<String, Boolean> {
        val logger = context.logger
        logger.info("[Cleaner] checkLocks: Checking inpainting model locks...")
        val locks = mutableMapOf<String, Boolean>()
        for (model in InpaintingModel.entries) {
            val installed = ModelManager.Default.isModelInstalled(model.modelId, context.fileSystem, logger)
            logger.info("[Cleaner] checkLocks: InpaintingModel ${model.name} (${model.modelId}) installed: $installed")
            locks["model:${model.modelId}"] = installed
            locks[model.modelId] = installed
            locks["model:${model.modelId.lowercase()}"] = installed
            locks[model.modelId.lowercase()] = installed
            when (model) {
                InpaintingModel.LAMA -> {
                    locks["model:lama"] = installed
                    locks["lama"] = installed
                    locks["model:big-lama"] = installed
                    locks["big-lama"] = installed
                }
                InpaintingModel.MANGA -> {
                    locks["model:manga"] = installed
                    locks["manga"] = installed
                    locks["model:anime-manga-big-lama"] = installed
                    locks["anime-manga-big-lama"] = installed
                }
                InpaintingModel.MIGAN -> {
                    locks["model:migan"] = installed
                    locks["migan"] = installed
                    locks["model:migan_traced"] = installed
                    locks["migan_traced"] = installed
                }
            }
        }
        logger.info("[Cleaner] checkLocks: Completed inpainting locks check: $locks")
        return locks
    }

    @PluginAction(
        name = "Download Model",
        description = "Downloads a specific ONNX inpainting model to local plugin storage"
    )
    suspend fun downloadModel(
        @CapabilityParam(description = "Select inpainting model to download", defaultValue = "\"LAMA\"")
        model: InpaintingDownloadModel? = InpaintingDownloadModel.LAMA,
        context: PluginContext
    ) {
        val logger = context.logger
        val targetModel = model ?: InpaintingDownloadModel.LAMA
        logger.info("[Cleaner] downloadModel action triggered for: ${targetModel.name} (${targetModel.modelId})")
        val result = ModelManager.Default.downloadModel(targetModel.modelId, context)
        if (result.isFailure) {
            logger.error("[Cleaner] downloadModel action failed for ${targetModel.modelId}: ${result.exceptionOrNull()?.message}")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download model ${targetModel.modelId}")
        }
        logger.info("[Cleaner] downloadModel action succeeded for: ${targetModel.modelId}")
    }

    @PluginAction(
        name = "Download All Models",
        description = "Downloads all required ONNX inpainting models to local plugin storage"
    )
    suspend fun downloadAllModels(context: PluginContext) {
        val logger = context.logger
        logger.info("[Cleaner] downloadAllModels action triggered")
        val inpaintingIds = InpaintingModel.entries.map { it.modelId }
        val result = ModelManager.Default.downloadModels(inpaintingIds, context)
        if (result.isFailure) {
            logger.error("[Cleaner] downloadAllModels action failed: ${result.exceptionOrNull()?.message}")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download all inpainting models")
        }
        logger.info("[Cleaner] downloadAllModels action succeeded")
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[Cleaner] setup: Starting Cleaner Plugin setup...")
        return try {
            val lamaInstalled = ModelManager.Default.isModelInstalled(ModelCatalog.LAMA_ID, context.fileSystem, logger)
            logger.info("[Cleaner] setup: Default LaMa model installed status = $lamaInstalled")
            if (!lamaInstalled) {
                logger.info("[Cleaner] setup: Default LaMa model not installed; downloading...")
                val downloadRes = ModelManager.Default.downloadModel(ModelCatalog.LAMA_ID, context)
                if (downloadRes.isFailure) {
                    logger.warn("[Cleaner] setup: Model download during setup did not complete: ${downloadRes.exceptionOrNull()?.message}. Models can be retrieved via download actions.")
                } else {
                    logger.info("[Cleaner] setup: Default LaMa model downloaded successfully.")
                }
            }
            logger.info("[Cleaner] setup: Cleaner Plugin setup complete.")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn("[Cleaner] setup: Cleaner setup encountered non-fatal error: ${e.message}")
            Result.success(Unit)
        }
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[Cleaner] validate: Validating Cleaner Plugin requirements...")
        val anyInstalled = InpaintingModel.entries.any {
            val isInst = ModelManager.Default.isModelInstalled(it.modelId, context.fileSystem, logger)
            logger.info("[Cleaner] validate: Model ${it.name} (${it.modelId}) installed: $isInst")
            isInst
        }

        if (!anyInstalled) {
            val msg = "No inpainting models installed (LaMa, Manga, MIGAN). Please download a model first."
            logger.warn("[Cleaner] validate: Validation failed: $msg")
            return Result.failure(IllegalStateException(msg))
        }

        logger.info("[Cleaner] validate: Cleaner Plugin validation passed - at least one inpainting model is installed.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[Cleaner] update: Cleaner Plugin update hook complete.")
        return Result.success(Unit)
    }

    private suspend fun getInpaintingSession(
        model: InpaintingModel,
        context: PluginContext
    ): Pair<OnnxInferenceSession, ModelSpec>? {
        val spec = ModelManager.Default.getModelSpec(model.modelId, context.fileSystem)
            ?: ModelSpec(
                modelTypeRaw = model.modelId,
                name = model.displayName,
                inputWidth = 512,
                inputHeight = 512
            )

        val session = ModelManager.Default.createInferenceSession(
            modelId = model.modelId,
            fileSystem = context.fileSystem,
            preferredDevice = ExecutionDevice.AUTO,
            logger = context.logger
        )

        return if (session != null) {
            Pair(session, spec)
        } else {
            context.logger.warn("ONNX model session could not be created for ${model.displayName} (${model.modelId}). Falling back to baseline pure inpainter.")
            null
        }
    }

    private suspend fun cleanImageInternal(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel,
        sessionPair: Pair<OnnxInferenceSession, ModelSpec>?,
        targetClasses: List<String>,
        dilationRadius: Int,
        saveMask: Boolean,
        isolatedRegionsOnly: Boolean,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        val logger = context.logger
        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Input image does not exist: $imagePath")
        }

        val outDir = File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val baseImage = withContext(Dispatchers.IO) {
            ImageIO.read(inputFile)
        } ?: throw IllegalArgumentException("Failed to decode image from path: $imagePath")

        val targetSet = targetClasses.map { it.trim().lowercase() }.toSet()
        val textObjects = segmentationData.objects.filter { it.label.trim().lowercase() in targetSet }

        val mask = InpaintingUtils.renderMaskFromObjects(
            objects = segmentationData.objects,
            imageWidth = baseImage.width,
            imageHeight = baseImage.height,
            targetClasses = targetSet,
            dilationPx = dilationRadius
        )

        var maskPath: String? = null
        if (saveMask) {
            val maskFile = File(outDir, "${inputFile.nameWithoutExtension}_mask.png")
            withContext(Dispatchers.IO) {
                ImageIO.write(mask, "png", maskFile)
            }
            maskPath = maskFile.absolutePath
        }

        val cleanedImage = if (isolatedRegionsOnly) {
            InpaintingUtils.inpaintImageIsolated(
                sourceImage = baseImage,
                mask = mask,
                session = sessionPair?.first,
                spec = sessionPair?.second,
                roiPaddingPx = 24,
                featherRadiusPx = 2
            )
        } else {
            // Run neural ONNX inpainting with fallback to pure Kotlin inpainting
            if (sessionPair != null) {
                InpaintingUtils.inpaintWithOnnx(
                    sourceImage = baseImage,
                    mask = mask,
                    session = sessionPair.first,
                    spec = sessionPair.second,
                    roiPaddingPx = 24
                )
            } else {
                InpaintingUtils.inpaintImage(baseImage, mask, roiPaddingPx = 24)
            }
        }

        val outputFormat = if (isolatedRegionsOnly) {
            "png"
        } else if (inputFile.extension.lowercase() in setOf("jpg", "jpeg", "webp", "png")) {
            inputFile.extension.lowercase()
        } else {
            "png"
        }

        val suffix = if (isolatedRegionsOnly) "_patches" else ""
        val outputFile = File(outDir, "${inputFile.nameWithoutExtension}$suffix.$outputFormat")
        withContext(Dispatchers.IO) {
            ImageIO.write(cleanedImage, outputFormat, outputFile)
        }

        logger.info("Cleaning complete for $imagePath with ${model.displayName} (isolatedRegionsOnly=$isolatedRegionsOnly). Cleaned ${textObjects.size} text instances -> ${outputFile.absolutePath}")

        return CleanerResult(
            cleanedImagePath = outputFile.absolutePath,
            maskPath = maskPath,
            cleanedObjectsCount = textObjects.size
        )
    }

    @Capability(
        name = "Clean Image",
        description = "Inpaints and erases segmented text regions from an image using segmentation data"
    )
    @RequiresLock(locks = ["model:lama"])
    suspend fun cleanImage(
        @CapabilityInput(description = "Path to the base image to clean", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityParam(description = "Segmentation result containing objects to inpaint")
        segmentationData: VisionResult,
        @CapabilityOutput(
            description = "Directory to save cleaned image",
            autogeneratedPattern = "{imagePath}/clean_chapter/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Inpainting model to use for background reconstruction", defaultValue = "\"LAMA\"")
        model: InpaintingModel = InpaintingModel.LAMA,
        @CapabilityParam(description = "List of class labels to inpaint out", defaultValue = "[\"text\"]")
        targetClasses: List<String> = listOf("text"),
        @CapabilityParam(description = "Mask dilation radius in pixels for contour coverage", defaultValue = "3")
        dilationRadius: Int = 3,
        @CapabilityParam(description = "Save the generated binary mask file alongside the cleaned image", defaultValue = "false")
        saveMask: Boolean = false,
        @CapabilityParam(description = "Output only the isolated inpainted regions with transparency (PNG)", defaultValue = "false")
        isolatedRegionsOnly: Boolean = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        context.logger.info("Starting Cleaner on image: $imagePath with model: ${model.displayName}. Targeting classes: $targetClasses, isolatedRegionsOnly: $isolatedRegionsOnly")
        val sessionPair = getInpaintingSession(model, context)
        return try {
            cleanImageInternal(
                imagePath = imagePath,
                segmentationData = segmentationData,
                outputDir = outputDir,
                model = model,
                sessionPair = sessionPair,
                targetClasses = targetClasses,
                dilationRadius = dilationRadius,
                saveMask = saveMask,
                isolatedRegionsOnly = isolatedRegionsOnly,
                context = context,
                hostFs = hostFs
            )
        } finally {
            sessionPair?.first?.close()
        }
    }

    @Capability(
        name = "Clean Image (Patches Only)",
        description = "Inpaints segmented text regions and outputs only the reconstructed patches on a transparent PNG canvas"
    )
    @RequiresLock(locks = ["model:lama"])
    suspend fun cleanImagePatchesOnly(
        @CapabilityInput(description = "Path to the base image to clean", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityParam(description = "Segmentation result containing objects to inpaint")
        segmentationData: VisionResult,
        @CapabilityOutput(
            description = "Directory to save transparent patch image",
            autogeneratedPattern = "{imagePath}/clean_chapter_patches/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Inpainting model to use for background reconstruction", defaultValue = "\"LAMA\"")
        model: InpaintingModel = InpaintingModel.LAMA,
        @CapabilityParam(description = "List of class labels to inpaint out", defaultValue = "[\"text\"]")
        targetClasses: List<String> = listOf("text"),
        @CapabilityParam(description = "Mask dilation radius in pixels", defaultValue = "3")
        dilationRadius: Int = 3,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        return cleanImage(
            imagePath = imagePath,
            segmentationData = segmentationData,
            outputDir = outputDir,
            model = model,
            targetClasses = targetClasses,
            dilationRadius = dilationRadius,
            saveMask = false,
            isolatedRegionsOnly = true,
            context = context,
            hostFs = hostFs
        )
    }

    @Capability(
        name = "Clean Chapter",
        description = "Inpaints and erases segmented text across an entire chapter/folder of images"
    )
    @RequiresLock(locks = ["model:lama"])
    suspend fun cleanChapter(
        @CapabilityInput(description = "Path to folder containing original chapter images", semanticTypes = ["path/folder"])
        inputFolder: String,
        @CapabilityParam(description = "Chapter vision result containing segmentations for each page")
        chapterVisionResult: ChapterVisionResult,
        @CapabilityOutput(
            description = "Directory to save cleaned chapter images",
            autogeneratedPattern = "{inputFolder}/clean_chapter/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Inpainting model to use", defaultValue = "\"LAMA\"")
        model: InpaintingModel = InpaintingModel.LAMA,
        @CapabilityParam(description = "List of class labels to inpaint out", defaultValue = "[\"text\"]")
        targetClasses: List<String> = listOf("text"),
        @CapabilityParam(description = "Mask dilation radius in pixels", defaultValue = "3")
        dilationRadius: Int = 3,
        @CapabilityParam(description = "Save generated binary masks", defaultValue = "false")
        saveMasks: Boolean = false,
        @CapabilityParam(description = "Output only the isolated inpainted regions with transparency (PNG)", defaultValue = "false")
        isolatedRegionsOnly: Boolean = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterCleanerResult {
        val logger = context.logger
        val progressReporter = context.progress

        val folder = File(inputFolder)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Input folder not found or is not a directory: $inputFolder")
        }

        val outDir = File(outputDir).apply { mkdirs() }
        val visionMap = chapterVisionResult.results.associateBy { it.pageName }

        val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")
        val imageFiles = folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.sortedNaturally() ?: emptyList()

        if (imageFiles.isEmpty()) {
            throw IllegalArgumentException("No images found in folder: $inputFolder")
        }

        logger.info("Starting Chapter Cleaner for ${imageFiles.size} images with model ${model.displayName} (isolatedRegionsOnly=$isolatedRegionsOnly).")

        val totalImages = imageFiles.size
        val results = mutableListOf<CleanerResult>()

        val sessionPair = getInpaintingSession(model, context)
        try {
            for ((index, file) in imageFiles.withIndex()) {
                val vResult = visionMap[file.name] ?: VisionResult(
                    objects = emptyList(),
                    imageWidth = 0,
                    imageHeight = 0,
                    pageName = file.name
                )

                val cResult = cleanImageInternal(
                    imagePath = file.absolutePath,
                    segmentationData = vResult,
                    outputDir = outDir.absolutePath,
                    model = model,
                    sessionPair = sessionPair,
                    targetClasses = targetClasses,
                    dilationRadius = dilationRadius,
                    saveMask = saveMasks,
                    isolatedRegionsOnly = isolatedRegionsOnly,
                    context = context,
                    hostFs = hostFs
                )
                results.add(cResult)
                progressReporter.report((index + 1).toFloat() / totalImages.toFloat())
            }
        } finally {
            sessionPair?.first?.close()
        }

        val cleanedPaths = results.map { it.cleanedImagePath }
        val maskPaths = results.mapNotNull { it.maskPath }

        logger.info("Chapter Cleaner complete. Cleaned $totalImages pages.")

        return ChapterCleanerResult(
            cleanedImagePaths = cleanedPaths,
            maskPaths = maskPaths,
            totalCleanedPages = totalImages
        )
    }

    @Capability(
        name = "Clean Chapter (Patches Only)",
        description = "Inpaints segmented text across an entire chapter and outputs only transparent PNG patch layers"
    )
    @RequiresLock(locks = ["model:lama"])
    suspend fun cleanChapterPatchesOnly(
        @CapabilityInput(description = "Path to folder containing original chapter images", semanticTypes = ["path/folder"])
        inputFolder: String,
        @CapabilityParam(description = "Chapter vision result containing segmentations for each page")
        chapterVisionResult: ChapterVisionResult,
        @CapabilityOutput(
            description = "Directory to save transparent patch images",
            autogeneratedPattern = "{inputFolder}/clean_chapter_patches/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Inpainting model to use", defaultValue = "\"LAMA\"")
        model: InpaintingModel = InpaintingModel.LAMA,
        @CapabilityParam(description = "List of class labels to inpaint out", defaultValue = "[\"text\"]")
        targetClasses: List<String> = listOf("text"),
        @CapabilityParam(description = "Mask dilation radius in pixels", defaultValue = "3")
        dilationRadius: Int = 3,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterCleanerResult {
        return cleanChapter(
            inputFolder = inputFolder,
            chapterVisionResult = chapterVisionResult,
            outputDir = outputDir,
            model = model,
            targetClasses = targetClasses,
            dilationRadius = dilationRadius,
            saveMasks = false,
            isolatedRegionsOnly = true,
            context = context,
            hostFs = hostFs
        )
    }

    @Capability(
        name = "Generate Mask",
        description = "Generates a binary PNG mask for specified target classes from VisionResult"
    )
    suspend fun generateMask(
        @CapabilityParam(description = "Segmentation data")
        segmentationData: VisionResult,
        @CapabilityOutput(description = "Output mask file path", semanticTypes = ["path/file"])
        outputMaskPath: String,
        @CapabilityParam(description = "Target classes to include in the mask", defaultValue = "[\"text\"]")
        targetClasses: List<String> = listOf("text"),
        @CapabilityParam(description = "Mask dilation radius in pixels", defaultValue = "3")
        dilationRadius: Int = 3,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val targetSet = targetClasses.map { it.trim().lowercase() }.toSet()
        val mask = InpaintingUtils.renderMaskFromObjects(
            objects = segmentationData.objects,
            imageWidth = segmentationData.imageWidth,
            imageHeight = segmentationData.imageHeight,
            targetClasses = targetSet,
            dilationPx = dilationRadius
        )

        val maskFile = File(outputMaskPath)
        maskFile.parentFile?.mkdirs()
        withContext(Dispatchers.IO) {
            ImageIO.write(mask, "png", maskFile)
        }

        return maskFile.absolutePath
    }
}
