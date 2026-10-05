package com.wip.cleaner

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.common.models.BlendingMode
import com.wip.common.models.ChapterCleanerResult
import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.CleanerResult
import com.wip.common.models.ExecutionDevice
import com.wip.common.models.InpaintingOptions
import com.wip.common.models.InpaintingUtils
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.ModelSpec
import com.wip.common.models.OcrCategory
import com.wip.common.models.OnnxInferenceSession
import com.wip.common.models.VisionResult
import com.wip.common.models.sortedNaturally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import org.wip.plugintoolkit.api.ConditionOperator
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.DependsOn
import org.wip.plugintoolkit.api.annotations.PluginAction
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginLoad
import org.wip.plugintoolkit.api.annotations.PluginLocks
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.RequiresLock
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry

@PluginInfo(
    id = "com.wip.cleaner",
    name = "WOM Cleaner",
    version = "1.5.1",
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

                InpaintingModel.ZITS -> {
                    locks["model:zits"] = installed
                    locks["zits"] = installed
                    locks["model:zits-inpaint-0717"] = installed
                    locks["zits-inpaint-0717"] = installed
                }

                InpaintingModel.ZITSPP -> {
                    locks["model:zitspp"] = installed
                    locks["zitspp"] = installed
                    locks["model:zits++"] = installed
                    locks["zits++"] = installed
                    locks["model:zits_plusplus"] = installed
                    locks["zits_plusplus"] = installed
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
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            logger.error("[Cleaner] downloadModel action failed for ${targetModel.modelId}: $err")
            context.showToast("Failed to download model ${targetModel.name}: $err")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download model ${targetModel.modelId}")
        }
        logger.info("[Cleaner] downloadModel action succeeded for: ${targetModel.modelId}")
        context.showToast("Downloaded model: ${targetModel.name} successfully!")
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
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            logger.error("[Cleaner] downloadAllModels action failed: $err")
            context.showToast("Failed to download all inpainting models: $err")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download all inpainting models")
        }
        logger.info("[Cleaner] downloadAllModels action succeeded")
        context.showToast("All inpainting models downloaded successfully!")
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
            val msg =
                "No inpainting models installed (LaMa, Manga, MIGAN, ZITS, ZITS++). Please download a model first."
            logger.warn("[Cleaner] validate: Validation failed: $msg")
            return Result.failure(IllegalStateException(msg))
        }

        logger.info("[Cleaner] validate: Cleaner Plugin validation passed - at least one inpainting model is installed.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[Cleaner] update: Organizing models directory...")
        ModelManager.Default.organizeModelsDirectory(context.fileSystem, context.logger)
        context.logger.info("[Cleaner] update: Cleaner Plugin update hook complete.")
        return Result.success(Unit)
    }

    data class InpaintingSessionBundle(
        val session: OnnxInferenceSession?,
        val spec: ModelSpec,
        val multiSessions: Map<String, OnnxInferenceSession> = emptyMap()
    ) : AutoCloseable {
        override fun close() {
            session?.close()
            multiSessions.values.forEach { it.close() }
        }
    }

    private suspend fun getInpaintingSession(
        model: InpaintingModel,
        context: PluginContext
    ): InpaintingSessionBundle? {
        val spec = ModelManager.Default.getModelSpec(model.modelId, context.fileSystem, context.logger)
            ?: ModelSpec(
                modelTypeRaw = model.modelId,
                name = model.displayName,
                inputWidth = 512,
                inputHeight = 512
            )

        val multiSessions = if (spec.components.isNotEmpty()) {
            ModelManager.Default.createInferenceSessions(
                modelId = model.modelId,
                fileSystem = context.fileSystem,
                preferredDevice = ExecutionDevice.AUTO,
                logger = context.logger
            )
        } else {
            emptyMap()
        }

        val primarySession = multiSessions["generator"] ?: ModelManager.Default.createInferenceSession(
            modelId = model.modelId,
            fileSystem = context.fileSystem,
            preferredDevice = ExecutionDevice.AUTO,
            logger = context.logger
        )

        return if (primarySession != null || multiSessions.isNotEmpty()) {
            InpaintingSessionBundle(primarySession, spec, multiSessions)
        } else {
            context.logger.warn("ONNX model session could not be created for ${model.displayName} (${model.modelId}). Falling back to baseline pure inpainter.")
            null
        }
    }

    fun resolveEffectiveTargetLabels(
        cleanClasses: List<OcrCategory>,
        targetClasses: List<String>? = null,
        segmentationData: VisionResult? = null
    ): Set<String> {
        val result = mutableSetOf<String>()
        for (cat in cleanClasses) {
            result.add(cat.name.lowercase())
        }
        if (result.contains("speech")) {
            result.add("dialogue")
        }

        val hasReclassified = segmentationData?.objects?.any {
            val l = it.label.trim().lowercase()
            l == "speech" || l == "sfx" || l == "non_text"
        } ?: false

        if (!hasReclassified && result.contains("speech")) {
            result.add("text")
            result.add("balloon")
        }

        targetClasses?.forEach { tc ->
            val cleanTc = tc.trim().lowercase()
            if (cleanTc.isNotBlank()) {
                result.add(cleanTc)
            }
        }
        return result
    }

    fun resolveEffectiveTargetLabels(
        cleanClasses: List<OcrCategory>,
        targetClasses: List<String>
    ): Set<String> = resolveEffectiveTargetLabels(cleanClasses, targetClasses, null)

    private suspend fun cleanImageCore(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel,
        strategy: CleaningStrategy,
        sessionBundle: InpaintingSessionBundle?,
        cleanClasses: List<OcrCategory>,
        targetClasses: List<String>? = null,
        dilationRadius: Int,
        adaptivePadding: Boolean = true,
        saveMask: Boolean = false,
        isolatedRegions: Boolean = false,
        saveCropPatches: Boolean = false,
        splitRegions: Boolean = false,
        options: InpaintingOptions = InpaintingOptions(),
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        val logger = context.logger
        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Input image does not exist: $imagePath")
        }

        val outDir = File(outputDir).apply { if (!exists()) mkdirs() }

        val baseImage = withContext(Dispatchers.IO) {
            ImageIO.read(inputFile)
        } ?: throw IllegalArgumentException("Failed to decode image from path: $imagePath")

        val targetSet = resolveEffectiveTargetLabels(cleanClasses, targetClasses, segmentationData)
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

        val deterministicFill = strategy != CleaningStrategy.NEURAL_ONLY
        val activeSession = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) null else sessionBundle?.session
        val activeSpec = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) null else sessionBundle?.spec
        val activeMultiSessions = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) emptyMap() else (sessionBundle?.multiSessions ?: emptyMap())

        val cleanedImage = if (isolatedRegions) {
            InpaintingUtils.inpaintProductionHybridIsolated(
                sourceImage = baseImage,
                mask = mask,
                session = activeSession,
                spec = activeSpec,
                adaptivePadding = adaptivePadding,
                deterministicFill = deterministicFill,
                minContextSize = 256,
                featherRadiusPx = options.featherRadius,
                options = options,
                multiSessions = activeMultiSessions
            )
        } else {
            InpaintingUtils.inpaintProductionHybrid(
                sourceImage = baseImage,
                mask = mask,
                session = activeSession,
                spec = activeSpec,
                adaptivePadding = adaptivePadding,
                deterministicFill = deterministicFill,
                minContextSize = 256,
                options = options,
                multiSessions = activeMultiSessions
            )
        }

        val outputFormat = if (isolatedRegions) {
            "png"
        } else if (inputFile.extension.lowercase() in setOf("jpg", "jpeg", "webp", "png")) {
            inputFile.extension.lowercase()
        } else {
            "png"
        }

        val suffix = if (isolatedRegions) "_patches" else ""
        val outputFile = File(outDir, "${inputFile.nameWithoutExtension}$suffix.$outputFormat")
        withContext(Dispatchers.IO) {
            ImageIO.write(cleanedImage, outputFormat, outputFile)
        }

        val patchPaths = mutableListOf<String>()
        val effectiveSavePatches = saveCropPatches || splitRegions
        if (effectiveSavePatches && textObjects.isNotEmpty()) {
            val patchesSubdir = File(outDir, "${inputFile.nameWithoutExtension}_patches")
            if (!patchesSubdir.exists()) patchesSubdir.mkdirs()
            val imgW = cleanedImage.width
            val imgH = cleanedImage.height
            for (obj in textObjects) {
                val originalIndex = segmentationData.objects.indexOf(obj).let { if (it >= 0) it else textObjects.indexOf(obj) }
                val singleObjMask = InpaintingUtils.renderMaskFromObjects(
                    objects = listOf(obj),
                    imageWidth = imgW,
                    imageHeight = imgH,
                    targetClasses = emptySet(),
                    dilationPx = dilationRadius
                )
                val patchImg = InpaintingUtils.isolateCleanedRegion(
                    cleanedImage = cleanedImage,
                    mask = singleObjMask,
                    featherRadiusPx = options.featherRadius
                )
                val cleanLabel = obj.label.trim().lowercase().ifBlank { "none" }
                val patchFile = File(patchesSubdir, "${inputFile.nameWithoutExtension}_patch_${originalIndex}_${cleanLabel}.png")
                withContext(Dispatchers.IO) {
                    ImageIO.write(patchImg, "png", patchFile)
                }
                patchPaths.add(patchFile.absolutePath)
            }
        }

        logger.info("Cleaning complete for $imagePath with ${model.displayName} [${strategy.displayName}] (isolatedRegions=$isolatedRegions, saveCropPatches=$effectiveSavePatches, patches=${patchPaths.size}). Cleaned ${textObjects.size} text instances -> ${outputFile.absolutePath}")

        return CleanerResult(
            cleanedImagePath = outputFile.absolutePath,
            maskPath = maskPath,
            cleanedObjectsCount = textObjects.size,
            segmentationData = segmentationData,
            clean = if (effectiveSavePatches) patchPaths else emptyList()
        )
    }

    private suspend fun cleanImageInternal(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel,
        sessionBundle: InpaintingSessionBundle?,
        targetClasses: List<String>,
        cleanClasses: List<OcrCategory> = listOf(OcrCategory.speech, OcrCategory.sfx, OcrCategory.text, OcrCategory.balloon, OcrCategory.watermark, OcrCategory.non_text, OcrCategory.none),
        outputCropPatches: Boolean = false,
        dilationRadius: Int,
        saveMask: Boolean,
        isolatedRegionsOnly: Boolean,
        options: InpaintingOptions = InpaintingOptions(),
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult = cleanImageCore(
        imagePath = imagePath,
        segmentationData = segmentationData,
        outputDir = outputDir,
        model = model,
        strategy = CleaningStrategy.NEURAL_ONLY,
        sessionBundle = sessionBundle,
        cleanClasses = cleanClasses,
        targetClasses = targetClasses,
        dilationRadius = dilationRadius,
        adaptivePadding = false,
        saveMask = saveMask,
        isolatedRegions = isolatedRegionsOnly,
        saveCropPatches = outputCropPatches,
        options = options,
        context = context,
        hostFs = hostFs
    )

    private suspend fun cleanImageInternalHybrid(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel,
        strategy: CleaningStrategy,
        sessionBundle: InpaintingSessionBundle?,
        targetClasses: List<String>,
        cleanClasses: List<OcrCategory> = listOf(OcrCategory.speech, OcrCategory.sfx, OcrCategory.text, OcrCategory.balloon, OcrCategory.watermark, OcrCategory.non_text, OcrCategory.none),
        outputCropPatches: Boolean = false,
        dilationRadius: Int,
        adaptivePadding: Boolean,
        saveMask: Boolean,
        isolatedRegionsOnly: Boolean,
        options: InpaintingOptions = InpaintingOptions(),
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult = cleanImageCore(
        imagePath = imagePath,
        segmentationData = segmentationData,
        outputDir = outputDir,
        model = model,
        strategy = strategy,
        sessionBundle = sessionBundle,
        cleanClasses = cleanClasses,
        targetClasses = targetClasses,
        dilationRadius = dilationRadius,
        adaptivePadding = adaptivePadding,
        saveMask = saveMask,
        isolatedRegions = isolatedRegionsOnly,
        saveCropPatches = outputCropPatches,
        options = options,
        context = context,
        hostFs = hostFs
    )

    @Capability(
        name = "Clean Image",
        description = "Inpaints and erases segmented text and artifacts from an image using segmentation data"
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
        @CapabilityParam(
            description = "Inpainting model to use for background reconstruction",
            defaultValue = "\"LAMA\""
        )
        model: InpaintingModel = InpaintingModel.LAMA,
        @CapabilityParam(
            description = "Cleaning strategy mode (AUTO_HYBRID: deterministic balloons + neural fallback, NEURAL_ONLY: always neural inpainter, DETERMINISTIC_ONLY: solid & gradient only)",
            defaultValue = "\"AUTO_HYBRID\""
        )
        strategy: CleaningStrategy = CleaningStrategy.AUTO_HYBRID,
        @CapabilityParam(
            description = "Element classes to clean/inpaint",
            defaultValue = "[\"speech\", \"sfx\", \"text\", \"balloon\", \"watermark\", \"non_text\", \"none\"]"
        )
        clean_classes: List<OcrCategory> = listOf(
            OcrCategory.speech,
            OcrCategory.sfx,
            OcrCategory.text,
            OcrCategory.balloon,
            OcrCategory.watermark,
            OcrCategory.non_text,
            OcrCategory.none
        ),
        @CapabilityParam(description = "Mask dilation radius in pixels for contour coverage", defaultValue = "3")
        dilationRadius: Int = 3,
        @CapabilityParam(
            description = "Output only the isolated inpainted regions with transparency (PNG)",
            defaultValue = "false"
        )
        isolated_regions: Boolean = false,
        @CapabilityParam(
            description = "Save individual clean crop patch images for each cleaned element so PSD builder can place and control them per-layer",
            defaultValue = "false",
            isAdvanced = true
        )
        save_crop_patches: Boolean = false,
        split_regions: Boolean = false,
        @CapabilityParam(
            description = "Enable adaptive 2.5x context expansion for neural redraws",
            defaultValue = "true",
            isAdvanced = true
        )
        adaptivePadding: Boolean = true,
        @CapabilityParam(
            description = "Save the generated binary mask file alongside the cleaned image",
            defaultValue = "false",
            isAdvanced = true
        )
        save_mask: Boolean = false,
        @CapabilityParam(
            description = "Boundary feathering radius (px) for smooth alpha blending",
            defaultValue = "2",
            isAdvanced = true
        )
        featherRadius: Int = 2,
        @CapabilityParam(
            description = "Boundary blending technique to eliminate seams (FEATHER, POISSON, MODIFIED_POISSON, LAPLACIAN_PYRAMID, NONE)",
            defaultValue = "\"FEATHER\"",
            isAdvanced = true
        )
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        @CapabilityParam(
            description = "Context expansion margin (px) around mask bounding box",
            defaultValue = "32",
            isAdvanced = true
        )
        cropMargin: Int = 32,
        @CapabilityParam(
            description = "Autoregressive TSR sampling iterations",
            defaultValue = "5",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        iterations: Int = 5,
        @CapabilityParam(
            description = "Additive color offset correction [-1.0, 1.0]",
            defaultValue = "0.0",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        addV: Double = 0.0,
        @CapabilityParam(
            description = "Multiplicative contrast scaling [0.0, 2.0]",
            defaultValue = "1.0",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        mulV: Double = 1.0,
        @CapabilityParam(
            description = "Gaussian smoothing sigma for edge detection",
            defaultValue = "1.5",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        sigma256: Double = 1.5,
        @CapabilityParam(
            description = "Wireframe proposal acceptance threshold",
            defaultValue = "0.85",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        maskTh: Double = 0.85,
        @CapabilityParam(
            description = "Suppress line hallucination inside hole to remove object cleanly",
            defaultValue = "false",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        objRemoval: Boolean = false,
        @CapabilityParam(
            description = "Edge-NMS binarization threshold [0, 255] (lower = more edges, higher = fewer edges)",
            defaultValue = "50",
            isAdvanced = true
        )
        @DependsOn(param = "model", value = "ZITSPP")
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        val options = InpaintingOptions(
            featherRadius = featherRadius,
            blendingMode = blendingMode,
            cropMargin = cropMargin,
            iterations = iterations,
            addV = addV,
            mulV = mulV,
            sigma256 = sigma256,
            maskTh = maskTh,
            objRemoval = objRemoval,
            binaryThreshold = binaryThreshold
        )
        context.logger.info("Starting Cleaner on image: $imagePath with model: ${model.displayName} [${strategy.displayName}]. Targeting classes: $clean_classes, isolatedRegions: $isolated_regions, saveCropPatches: $save_crop_patches")
        val sessionBundle = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) null else getInpaintingSession(model, context)
        return try {
            cleanImageCore(
                imagePath = imagePath,
                segmentationData = segmentationData,
                outputDir = outputDir,
                model = model,
                strategy = strategy,
                sessionBundle = sessionBundle,
                cleanClasses = clean_classes,
                dilationRadius = dilationRadius,
                adaptivePadding = adaptivePadding,
                saveMask = save_mask,
                isolatedRegions = isolated_regions,
                saveCropPatches = save_crop_patches,
                splitRegions = split_regions,
                options = options,
                context = context,
                hostFs = hostFs
            )
        } finally {
            sessionBundle?.close()
        }
    }

    @Capability(
        name = "Clean Chapter",
        description = "Inpaints and erases segmented text and artifacts across an entire chapter/folder of images"
    )
    @RequiresLock(locks = ["model:lama"])
    suspend fun cleanChapter(
        @CapabilityInput(
            description = "Path to folder containing original chapter images",
            semanticTypes = ["path/folder"]
        )
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
        @CapabilityParam(
            description = "Cleaning strategy mode (AUTO_HYBRID: deterministic balloons + neural fallback, NEURAL_ONLY: always neural inpainter, DETERMINISTIC_ONLY: solid & gradient only)",
            defaultValue = "\"AUTO_HYBRID\""
        )
        strategy: CleaningStrategy = CleaningStrategy.AUTO_HYBRID,
        @CapabilityParam(
            description = "Element classes to clean/inpaint",
            defaultValue = "[\"speech\", \"sfx\", \"text\", \"balloon\", \"watermark\", \"non_text\", \"none\"]"
        )
        clean_classes: List<OcrCategory> = listOf(
            OcrCategory.speech,
            OcrCategory.sfx,
            OcrCategory.text,
            OcrCategory.balloon,
            OcrCategory.watermark,
            OcrCategory.non_text,
            OcrCategory.none
        ),
        @CapabilityParam(description = "Mask dilation radius in pixels", defaultValue = "3")
        dilationRadius: Int = 3,
        @CapabilityParam(
            description = "Output only the isolated inpainted regions with transparency (PNG)",
            defaultValue = "false"
        )
        isolated_regions: Boolean = false,
        @CapabilityParam(
            description = "Save individual clean crop patch images for each cleaned element so PSD builder can place and control them per-layer",
            defaultValue = "false",
            isAdvanced = true
        )
        save_crop_patches: Boolean = false,
        split_regions: Boolean = false,
        @CapabilityParam(
            description = "Enable adaptive 2.5x context expansion for neural redraws",
            defaultValue = "true",
            isAdvanced = true
        )
        adaptivePadding: Boolean = true,
        @CapabilityParam(
            description = "Save generated binary masks",
            defaultValue = "false",
            isAdvanced = true
        )
        save_mask: Boolean = false,
        @CapabilityParam(
            description = "Boundary feathering radius (px) for smooth alpha blending",
            defaultValue = "2",
            isAdvanced = true
        )
        featherRadius: Int = 2,
        @CapabilityParam(
            description = "Boundary blending technique to eliminate seams (FEATHER, POISSON, MODIFIED_POISSON, LAPLACIAN_PYRAMID, NONE)",
            defaultValue = "\"FEATHER\"",
            isAdvanced = true
        )
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        @CapabilityParam(
            description = "Context expansion margin (px) around mask bounding box",
            defaultValue = "32",
            isAdvanced = true
        )
        cropMargin: Int = 32,
        @CapabilityParam(
            description = "Autoregressive TSR sampling iterations",
            defaultValue = "5",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        iterations: Int = 5,
        @CapabilityParam(
            description = "Additive color offset correction [-1.0, 1.0]",
            defaultValue = "0.0",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        addV: Double = 0.0,
        @CapabilityParam(
            description = "Multiplicative contrast scaling [0.0, 2.0]",
            defaultValue = "1.0",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        mulV: Double = 1.0,
        @CapabilityParam(
            description = "Gaussian smoothing sigma for edge detection",
            defaultValue = "1.5",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        sigma256: Double = 1.5,
        @CapabilityParam(
            description = "Wireframe proposal acceptance threshold",
            defaultValue = "0.85",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        maskTh: Double = 0.85,
        @CapabilityParam(
            description = "Suppress line hallucination inside hole to remove object cleanly",
            defaultValue = "false",
            isAdvanced = true
        )
        @DependsOn(param = "model", operator = ConditionOperator.IN, values = ["ZITS", "ZITSPP"])
        objRemoval: Boolean = false,
        @CapabilityParam(
            description = "Edge-NMS binarization threshold [0, 255] (lower = more edges, higher = fewer edges)",
            defaultValue = "50",
            isAdvanced = true
        )
        @DependsOn(param = "model", value = "ZITSPP")
        binaryThreshold: Int = 50,
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

        val options = InpaintingOptions(
            featherRadius = featherRadius,
            blendingMode = blendingMode,
            cropMargin = cropMargin,
            iterations = iterations,
            addV = addV,
            mulV = mulV,
            sigma256 = sigma256,
            maskTh = maskTh,
            objRemoval = objRemoval,
            binaryThreshold = binaryThreshold
        )

        logger.info("Starting Chapter Cleaner for ${imageFiles.size} images with model ${model.displayName} [${strategy.displayName}] (isolatedRegions=$isolated_regions, saveCropPatches=$save_crop_patches).")

        val totalImages = imageFiles.size
        val results = mutableListOf<CleanerResult>()

        val sessionBundle = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) null else getInpaintingSession(model, context)
        try {
            for ((index, file) in imageFiles.withIndex()) {
                val vResult = visionMap[file.name] ?: VisionResult(
                    objects = emptyList(),
                    imageWidth = 0,
                    imageHeight = 0,
                    pageName = file.name
                )

                val cResult = cleanImageCore(
                    imagePath = file.absolutePath,
                    segmentationData = vResult,
                    outputDir = outDir.absolutePath,
                    model = model,
                    strategy = strategy,
                    sessionBundle = sessionBundle,
                    cleanClasses = clean_classes,
                    dilationRadius = dilationRadius,
                    adaptivePadding = adaptivePadding,
                    saveMask = save_mask,
                    isolatedRegions = isolated_regions,
                    saveCropPatches = save_crop_patches,
                    splitRegions = split_regions,
                    options = options,
                    context = context,
                    hostFs = hostFs
                )
                results.add(cResult)
                progressReporter.report((index + 1).toFloat() / totalImages.toFloat())
            }
        } finally {
            sessionBundle?.close()
        }

        val cleanedPaths = results.map { it.cleanedImagePath }
        val maskPaths = results.mapNotNull { it.maskPath }
        val allCleanPatches = results.flatMap { it.clean }
        val effectiveSavePatches = save_crop_patches || split_regions

        logger.info("Chapter Cleaner complete. Cleaned $totalImages pages.")

        return ChapterCleanerResult(
            cleanedImagePaths = cleanedPaths,
            maskPaths = maskPaths,
            totalCleanedPages = totalImages,
            chapterVisionResult = chapterVisionResult,
            clean = if (effectiveSavePatches) allCleanPatches else emptyList()
        )
    }

    // Deprecated forwarders for backward compatibility:
    @Deprecated("Use cleanImage with strategy=AUTO_HYBRID instead")
    suspend fun cleanImageHybrid(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel = InpaintingModel.MANGA,
        strategy: CleaningStrategy = CleaningStrategy.AUTO_HYBRID,
        targetClasses: List<String> = listOf("text"),
        clean_classes: List<OcrCategory> = listOf(OcrCategory.speech, OcrCategory.sfx, OcrCategory.text, OcrCategory.balloon, OcrCategory.watermark, OcrCategory.non_text, OcrCategory.none),
        dilationRadius: Int = 3,
        adaptivePadding: Boolean = true,
        saveMask: Boolean = false,
        isolatedRegionsOnly: Boolean = false,
        featherRadius: Int = 2,
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        cropMargin: Int = 32,
        iterations: Int = 5,
        addV: Double = 0.0,
        mulV: Double = 1.0,
        sigma256: Double = 1.5,
        maskTh: Double = 0.85,
        objRemoval: Boolean = false,
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        val options = InpaintingOptions(
            featherRadius = featherRadius,
            blendingMode = blendingMode,
            cropMargin = cropMargin,
            iterations = iterations,
            addV = addV,
            mulV = mulV,
            sigma256 = sigma256,
            maskTh = maskTh,
            objRemoval = objRemoval,
            binaryThreshold = binaryThreshold
        )
        val sessionBundle = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) null else getInpaintingSession(model, context)
        return try {
            cleanImageCore(
                imagePath = imagePath,
                segmentationData = segmentationData,
                outputDir = outputDir,
                model = model,
                strategy = strategy,
                sessionBundle = sessionBundle,
                cleanClasses = clean_classes,
                targetClasses = targetClasses,
                dilationRadius = dilationRadius,
                adaptivePadding = adaptivePadding,
                saveMask = saveMask,
                isolatedRegions = isolatedRegionsOnly,
                saveCropPatches = false,
                options = options,
                context = context,
                hostFs = hostFs
            )
        } finally {
            sessionBundle?.close()
        }
    }

    @Deprecated("Use cleanImage with isolated_regions=true instead")
    suspend fun cleanImagePatchesOnly(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel = InpaintingModel.LAMA,
        targetClasses: List<String> = listOf("text"),
        clean_classes: List<OcrCategory> = listOf(OcrCategory.speech, OcrCategory.sfx, OcrCategory.text, OcrCategory.balloon, OcrCategory.watermark, OcrCategory.non_text, OcrCategory.none),
        dilationRadius: Int = 3,
        featherRadius: Int = 2,
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        cropMargin: Int = 32,
        iterations: Int = 5,
        addV: Double = 0.0,
        mulV: Double = 1.0,
        sigma256: Double = 1.5,
        maskTh: Double = 0.85,
        objRemoval: Boolean = false,
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        val options = InpaintingOptions(
            featherRadius = featherRadius,
            blendingMode = blendingMode,
            cropMargin = cropMargin,
            iterations = iterations,
            addV = addV,
            mulV = mulV,
            sigma256 = sigma256,
            maskTh = maskTh,
            objRemoval = objRemoval,
            binaryThreshold = binaryThreshold
        )
        val sessionBundle = getInpaintingSession(model, context)
        return try {
            cleanImageCore(
                imagePath = imagePath,
                segmentationData = segmentationData,
                outputDir = outputDir,
                model = model,
                strategy = CleaningStrategy.NEURAL_ONLY,
                sessionBundle = sessionBundle,
                cleanClasses = clean_classes,
                targetClasses = targetClasses,
                dilationRadius = dilationRadius,
                adaptivePadding = false,
                saveMask = false,
                isolatedRegions = true,
                saveCropPatches = false,
                options = options,
                context = context,
                hostFs = hostFs
            )
        } finally {
            sessionBundle?.close()
        }
    }

    @Deprecated("Use cleanImage with isolated_regions=true and strategy=AUTO_HYBRID instead")
    suspend fun cleanImagePatchesOnlyHybrid(
        imagePath: String,
        segmentationData: VisionResult,
        outputDir: String,
        model: InpaintingModel = InpaintingModel.MANGA,
        strategy: CleaningStrategy = CleaningStrategy.AUTO_HYBRID,
        targetClasses: List<String> = listOf("text"),
        dilationRadius: Int = 3,
        adaptivePadding: Boolean = true,
        featherRadius: Int = 2,
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        cropMargin: Int = 32,
        iterations: Int = 5,
        addV: Double = 0.0,
        mulV: Double = 1.0,
        sigma256: Double = 1.5,
        maskTh: Double = 0.85,
        objRemoval: Boolean = false,
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): CleanerResult {
        val options = InpaintingOptions(
            featherRadius = featherRadius,
            blendingMode = blendingMode,
            cropMargin = cropMargin,
            iterations = iterations,
            addV = addV,
            mulV = mulV,
            sigma256 = sigma256,
            maskTh = maskTh,
            objRemoval = objRemoval,
            binaryThreshold = binaryThreshold
        )
        val sessionBundle = if (strategy == CleaningStrategy.DETERMINISTIC_ONLY) null else getInpaintingSession(model, context)
        return try {
            cleanImageCore(
                imagePath = imagePath,
                segmentationData = segmentationData,
                outputDir = outputDir,
                model = model,
                strategy = strategy,
                sessionBundle = sessionBundle,
                cleanClasses = listOf(OcrCategory.speech, OcrCategory.sfx, OcrCategory.text, OcrCategory.balloon, OcrCategory.watermark, OcrCategory.non_text, OcrCategory.none),
                targetClasses = targetClasses,
                dilationRadius = dilationRadius,
                adaptivePadding = adaptivePadding,
                saveMask = false,
                isolatedRegions = true,
                saveCropPatches = false,
                options = options,
                context = context,
                hostFs = hostFs
            )
        } finally {
            sessionBundle?.close()
        }
    }

    @Deprecated("Use cleanChapter with strategy=AUTO_HYBRID instead")
    suspend fun cleanChapterHybrid(
        inputFolder: String,
        chapterVisionResult: ChapterVisionResult,
        outputDir: String,
        model: InpaintingModel = InpaintingModel.MANGA,
        strategy: CleaningStrategy = CleaningStrategy.AUTO_HYBRID,
        targetClasses: List<String> = listOf("text"),
        clean_classes: List<OcrCategory> = listOf(OcrCategory.speech, OcrCategory.sfx, OcrCategory.text, OcrCategory.balloon, OcrCategory.watermark, OcrCategory.non_text, OcrCategory.none),
        dilationRadius: Int = 3,
        adaptivePadding: Boolean = true,
        saveMasks: Boolean = false,
        isolatedRegionsOnly: Boolean = false,
        outputCropPatches: Boolean = false,
        featherRadius: Int = 2,
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        cropMargin: Int = 32,
        iterations: Int = 5,
        addV: Double = 0.0,
        mulV: Double = 1.0,
        sigma256: Double = 1.5,
        maskTh: Double = 0.85,
        objRemoval: Boolean = false,
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterCleanerResult = cleanChapter(
        inputFolder = inputFolder,
        chapterVisionResult = chapterVisionResult,
        outputDir = outputDir,
        model = model,
        strategy = strategy,
        clean_classes = clean_classes,
        dilationRadius = dilationRadius,
        isolated_regions = isolatedRegionsOnly,
        save_crop_patches = outputCropPatches,
        adaptivePadding = adaptivePadding,
        save_mask = saveMasks,
        featherRadius = featherRadius,
        blendingMode = blendingMode,
        cropMargin = cropMargin,
        iterations = iterations,
        addV = addV,
        mulV = mulV,
        sigma256 = sigma256,
        maskTh = maskTh,
        objRemoval = objRemoval,
        binaryThreshold = binaryThreshold,
        context = context,
        hostFs = hostFs
    )

    @Deprecated("Use cleanChapter with isolated_regions=true instead")
    suspend fun cleanChapterPatchesOnly(
        inputFolder: String,
        chapterVisionResult: ChapterVisionResult,
        outputDir: String,
        model: InpaintingModel = InpaintingModel.LAMA,
        targetClasses: List<String> = listOf("text"),
        dilationRadius: Int = 3,
        featherRadius: Int = 2,
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        cropMargin: Int = 32,
        iterations: Int = 5,
        addV: Double = 0.0,
        mulV: Double = 1.0,
        sigma256: Double = 1.5,
        maskTh: Double = 0.85,
        objRemoval: Boolean = false,
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterCleanerResult = cleanChapter(
        inputFolder = inputFolder,
        chapterVisionResult = chapterVisionResult,
        outputDir = outputDir,
        model = model,
        strategy = CleaningStrategy.NEURAL_ONLY,
        dilationRadius = dilationRadius,
        isolated_regions = true,
        save_crop_patches = false,
        save_mask = false,
        featherRadius = featherRadius,
        blendingMode = blendingMode,
        cropMargin = cropMargin,
        iterations = iterations,
        addV = addV,
        mulV = mulV,
        sigma256 = sigma256,
        maskTh = maskTh,
        objRemoval = objRemoval,
        binaryThreshold = binaryThreshold,
        context = context,
        hostFs = hostFs
    )

    @Deprecated("Use cleanChapter with isolated_regions=true and strategy=AUTO_HYBRID instead")
    suspend fun cleanChapterPatchesOnlyHybrid(
        inputFolder: String,
        chapterVisionResult: ChapterVisionResult,
        outputDir: String,
        model: InpaintingModel = InpaintingModel.MANGA,
        strategy: CleaningStrategy = CleaningStrategy.AUTO_HYBRID,
        targetClasses: List<String> = listOf("text"),
        dilationRadius: Int = 3,
        adaptivePadding: Boolean = true,
        featherRadius: Int = 2,
        blendingMode: BlendingMode = BlendingMode.FEATHER,
        cropMargin: Int = 32,
        iterations: Int = 5,
        addV: Double = 0.0,
        mulV: Double = 1.0,
        sigma256: Double = 1.5,
        maskTh: Double = 0.85,
        objRemoval: Boolean = false,
        binaryThreshold: Int = 50,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterCleanerResult = cleanChapter(
        inputFolder = inputFolder,
        chapterVisionResult = chapterVisionResult,
        outputDir = outputDir,
        model = model,
        strategy = strategy,
        dilationRadius = dilationRadius,
        isolated_regions = true,
        save_crop_patches = false,
        adaptivePadding = adaptivePadding,
        save_mask = false,
        featherRadius = featherRadius,
        blendingMode = blendingMode,
        cropMargin = cropMargin,
        iterations = iterations,
        addV = addV,
        mulV = mulV,
        sigma256 = sigma256,
        maskTh = maskTh,
        objRemoval = objRemoval,
        binaryThreshold = binaryThreshold,
        context = context,
        hostFs = hostFs
    )

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

