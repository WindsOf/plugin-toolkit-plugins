package com.wip.vision

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.DetectionBox
import com.wip.common.models.DetectionResult
import com.wip.common.models.ExecutionDevice
import com.wip.common.models.ImageTensorUtils
import com.wip.common.models.InpaintingUtils
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.ModelSpec
import com.wip.common.models.OnnxInferenceEngine
import com.wip.common.models.OnnxInferenceSession
import com.wip.common.models.RfDetrPostprocessor
import com.wip.common.models.SahiConfig
import com.wip.common.models.SahiInferenceRunner
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.max
import kotlin.math.min
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
    id = "com.wip.vision",
    name = "Vision",
    version = "1.0.0",
    description = "Object detection and instance segmentation plugin using YOLO and RF-DETR.",
    supportedOs = [OS.WINDOWS, OS.LINUX, OS.MACOS]
)
class VisionPlugin {

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
        logger.info("[Vision] onLoad: Initializing Vision Plugin...")
        return Result.success(Unit)
    }

    @PluginLocks
    suspend fun checkLocks(context: PluginContext): Map<String, Boolean> {
        val logger = context.logger
        logger.info("[Vision] checkLocks: Checking vision model locks...")
        val locks = mutableMapOf<String, Boolean>()
        for (model in VisionModel.entries) {
            val installed = ModelManager.Default.isModelInstalled(model.modelId, context.fileSystem, logger)
            logger.info("[Vision] checkLocks: VisionModel ${model.name} (${model.modelId}) installed: $installed")
            locks["model:${model.modelId}"] = installed
            locks[model.modelId] = installed
        }
        logger.info("[Vision] checkLocks: Completed vision locks check: $locks")
        return locks
    }

    @PluginAction(
        name = "Download Model",
        description = "Downloads a specific ONNX model and descriptor to local plugin storage"
    )
    suspend fun downloadModel(
        @CapabilityParam(description = "Select vision model to download", defaultValue = "\"YOLO_DET_X\"")
        model: VisionDownloadModel? = VisionDownloadModel.YOLO_DET_X,
        context: PluginContext
    ) {
        val logger = context.logger
        val targetModel = model ?: VisionDownloadModel.YOLO_DET_X
        logger.info("[Vision] downloadModel action triggered for: ${targetModel.name} (${targetModel.modelId})")
        val result = ModelManager.Default.downloadModel(targetModel.modelId, context)
        if (result.isFailure) {
            logger.error("[Vision] downloadModel action failed for ${targetModel.modelId}: ${result.exceptionOrNull()?.message}")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download model ${targetModel.modelId}")
        }
        logger.info("[Vision] downloadModel action succeeded for: ${targetModel.modelId}")
    }

    @PluginAction(
        name = "Download All Models",
        description = "Downloads all required ONNX vision models and descriptors to local plugin storage"
    )
    suspend fun downloadAllModels(context: PluginContext) {
        val logger = context.logger
        logger.info("[Vision] downloadAllModels action triggered")
        val visionIds = VisionModel.entries.map { it.modelId }
        val result = ModelManager.Default.downloadModels(visionIds, context)
        if (result.isFailure) {
            logger.error("[Vision] downloadAllModels action failed: ${result.exceptionOrNull()?.message}")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download all vision models")
        }
        logger.info("[Vision] downloadAllModels action succeeded")
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[Vision] setup: Starting Vision Plugin setup...")
        return try {
            val yoloInstalled = ModelManager.Default.isModelInstalled(ModelCatalog.YOLO_DET_X_ID, context.fileSystem, logger)
            val rfdetrInstalled = ModelManager.Default.isModelInstalled(ModelCatalog.RFDETR_SEG_2XLARGE_ID, context.fileSystem, logger)
            logger.info("[Vision] setup: YOLO installed: $yoloInstalled, RF-DETR installed: $rfdetrInstalled")

            if (!yoloInstalled) {
                logger.info("[Vision] setup: Downloading YOLO vision model...")
                val res = ModelManager.Default.downloadModel(ModelCatalog.YOLO_DET_X_ID, context)
                if (res.isFailure) {
                    logger.warn("[Vision] setup: YOLO download during setup did not complete: ${res.exceptionOrNull()?.message}")
                } else {
                    logger.info("[Vision] setup: YOLO vision model downloaded successfully.")
                }
            }
            if (!rfdetrInstalled) {
                logger.info("[Vision] setup: Downloading RF-DETR vision model...")
                val res = ModelManager.Default.downloadModel(ModelCatalog.RFDETR_SEG_2XLARGE_ID, context)
                if (res.isFailure) {
                    logger.warn("[Vision] setup: RF-DETR download during setup did not complete: ${res.exceptionOrNull()?.message}")
                } else {
                    logger.info("[Vision] setup: RF-DETR vision model downloaded successfully.")
                }
            }
            logger.info("[Vision] setup: Vision Plugin setup complete.")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.warn("[Vision] setup: Setup encountered non-fatal error: ${e.message}")
            Result.success(Unit)
        }
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[Vision] validate: Validating Vision Plugin requirements...")
        val yoloInstalled = ModelManager.Default.isModelInstalled(ModelCatalog.YOLO_DET_X_ID, context.fileSystem, logger)
        val rfdetrInstalled = ModelManager.Default.isModelInstalled(ModelCatalog.RFDETR_SEG_2XLARGE_ID, context.fileSystem, logger)
        logger.info("[Vision] validate: YOLO installed: $yoloInstalled, RF-DETR installed: $rfdetrInstalled")

        if (!yoloInstalled || !rfdetrInstalled) {
            val msg = "Vision models not yet downloaded (YOLO: $yoloInstalled, RF-DETR: $rfdetrInstalled). Please run setup or download actions."
            logger.warn("[Vision] validate: Validation failed: $msg")
            return Result.failure(IllegalStateException(msg))
        }

        logger.info("[Vision] validate: Vision Plugin validation passed - all required models installed.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[Vision] update: Vision Plugin update complete.")
        return Result.success(Unit)
    }

    @Capability(
        name = "Detect and Segment",
        description = "Runs two-stage detection (YOLO) and instance segmentation (RF-DETR) on an image"
    )
    @RequiresLock(locks = ["model:yolo-det-x-best-v3", "model:rfdetr-seg-2xlarge-ema-v3"])
    suspend fun detectAndSegment(
        @CapabilityInput(description = "Path to input image (JPG, PNG, WebP)", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityParam(description = "Detection confidence threshold", defaultValue = "0.25")
        detectionScoreThreshold: Double = 0.25,
        @CapabilityParam(description = "Segmentation confidence threshold", defaultValue = "0.25")
        segmentationScoreThreshold: Double = 0.25,
        @CapabilityParam(description = "IoU NMS threshold", defaultValue = "0.45")
        iouThreshold: Double = 0.45,
        @CapabilityParam(description = "Detection tile resolution scale factor (e.g. 1.0 = 640px, 2.0 = 1280px downscaled)", defaultValue = "1.0")
        detectScale: Double = 1.0,
        @CapabilityParam(description = "Detection sliding window overlap ratio (0.0 to 0.8)", defaultValue = "0.25")
        detectOverlap: Double = 0.25,
        @CapabilityParam(description = "Segmentation tile/ROI resolution scale factor (e.g. 1.0 = 640px, 2.0 = 1280px)", defaultValue = "1.0")
        segmentScale: Double = 1.0,
        @CapabilityParam(description = "Segmentation sliding window overlap ratio (0.0 to 0.8)", defaultValue = "0.25")
        segmentOverlap: Double = 0.25,
        @CapabilityParam(description = "Save binary mask image for detected text", defaultValue = "false")
        saveMask: Boolean = false,
        @CapabilityParam(description = "Save visual debug image showing all detected/segmented bounding boxes and contours", defaultValue = "false")
        saveDebugImage: Boolean = false,
        @CapabilityOutput(description = "Optional output directory to save mask/debug image", defaultValue = "", semanticTypes = ["path/folder"])
        outputDir: String = "",
        context: PluginContext,
        hostFs: HostFileSystem
    ): VisionResult {
        val logger = context.logger
        logger.info("Starting Detect and Segment for $imagePath (detectScale=$detectScale, segmentScale=$segmentScale, detectOverlap=$detectOverlap, segmentOverlap=$segmentOverlap)")

        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Input image file does not exist: $imagePath")
        }

        val baseImage = withContext(Dispatchers.IO) {
            ImageIO.read(inputFile)
        } ?: throw IllegalArgumentException("Failed to decode image from path: $imagePath")

        val imgW = baseImage.width
        val imgH = baseImage.height

        // 1. Stage 1: YOLO ROI Detection with Multi-Scale SAHI
        val yoloModelId = ModelCatalog.YOLO_DET_X_ID
        val yoloSpec = ModelManager.Default.getModelSpec(yoloModelId, context.fileSystem)
            ?: ModelSpec(
                name = yoloModelId,
                type = "yolo_v10",
                modelPath = "$yoloModelId.onnx",
                inputWidth = 640,
                inputHeight = 640,
                scoreThreshold = detectionScoreThreshold,
                iouThreshold = iouThreshold,
                classes = listOf("balloon", "text", "watermark")
            )

        val yoloSession = ModelManager.Default.createInferenceSession(yoloModelId, context.fileSystem, ExecutionDevice.AUTO, logger)
        val candidateBoxes = if (yoloSession != null) {
            try {
                val sahiConfig = SahiConfig(
                    sliceWidth = yoloSpec.inputWidth,
                    sliceHeight = yoloSpec.inputHeight,
                    overlapWidthRatio = detectOverlap.toFloat().coerceIn(0.0f, 0.8f),
                    overlapHeightRatio = detectOverlap.toFloat().coerceIn(0.0f, 0.8f),
                    scoreThreshold = detectionScoreThreshold,
                    iouThreshold = iouThreshold,
                    tileScale = detectScale.coerceAtLeast(0.5)
                )
                val detResult = SahiInferenceRunner.runSlicedInference(
                    image = baseImage,
                    modelSpec = yoloSpec,
                    session = yoloSession,
                    config = sahiConfig,
                    logger = logger
                )
                detResult.boxes
            } finally {
                yoloSession.close()
            }
        } else {
            logger.warn("YOLO model '$yoloModelId' not installed. Proceeding with full-image fallback.")
            emptyList()
        }

        // 2. Stage 2: RF-DETR Segmentation on ROIs or SAHI Slices
        val rfdetrModelId = ModelCatalog.RFDETR_SEG_2XLARGE_ID
        val rfdetrSpec = ModelManager.Default.getModelSpec(rfdetrModelId, context.fileSystem)
            ?: ModelSpec(
                name = rfdetrModelId,
                type = "rfdetr_seg",
                modelPath = "$rfdetrModelId.onnx",
                inputWidth = 640,
                inputHeight = 640,
                scoreThreshold = segmentationScoreThreshold,
                iouThreshold = iouThreshold,
                classes = listOf("balloon", "text", "watermark")
            )

        val finalObjects = mutableListOf<SegmentedObject>()

        val rfdetrSession = ModelManager.Default.createInferenceSession(rfdetrModelId, context.fileSystem, ExecutionDevice.AUTO, logger)
        if (rfdetrSession != null) {
            try {
                if (candidateBoxes.isNotEmpty()) {
                    val inputName = rfdetrSession.session.inputNames.iterator().next()

                    // Expand candidate boxes by context margin proportional to each box's OWN width and height
                    val expandedBoxes = candidateBoxes.map { box ->
                        val bw = box.xmax - box.xmin
                        val bh = box.ymax - box.ymin
                        val mx = (bw * 0.15 * segmentScale).coerceIn(0.005, 0.08)
                        val my = (bh * 0.15 * segmentScale).coerceIn(0.005, 0.08)
                        DetectionBox(
                            label = box.label,
                            confidence = box.confidence,
                            ymin = (box.ymin - my).coerceIn(0.0, 1.0),
                            xmin = (box.xmin - mx).coerceIn(0.0, 1.0),
                            ymax = (box.ymax + my).coerceIn(0.0, 1.0),
                            xmax = (box.xmax + mx).coerceIn(0.0, 1.0)
                        )
                    }

                    // Merge only ROIs that have significant intersection (> 15% overlap area of smaller box)
                    val mergedRois = mutableListOf<DetectionBox>()
                    var remaining = expandedBoxes.toMutableList()
                    while (remaining.isNotEmpty()) {
                        var curr = remaining.removeAt(0)
                        var mergedAny: Boolean
                        do {
                            mergedAny = false
                            val nextRemaining = mutableListOf<DetectionBox>()
                            for (other in remaining) {
                                val interXmin = maxOf(curr.xmin, other.xmin)
                                val interYmin = maxOf(curr.ymin, other.ymin)
                                val interXmax = minOf(curr.xmax, other.xmax)
                                val interYmax = minOf(curr.ymax, other.ymax)
                                val interW = maxOf(0.0, interXmax - interXmin)
                                val interH = maxOf(0.0, interYmax - interYmin)
                                val interArea = interW * interH

                                val areaCurr = (curr.xmax - curr.xmin) * (curr.ymax - curr.ymin)
                                val areaOther = (other.xmax - other.xmin) * (other.ymax - other.ymin)
                                val minArea = minOf(areaCurr, areaOther)

                                val overlapsSignificantly = minArea > 0.0 && (interArea / minArea) > 0.15
                                if (overlapsSignificantly) {
                                    curr = DetectionBox(
                                        label = curr.label,
                                        confidence = maxOf(curr.confidence, other.confidence),
                                        ymin = minOf(curr.ymin, other.ymin),
                                        xmin = minOf(curr.xmin, other.xmin),
                                        ymax = maxOf(curr.ymax, other.ymax),
                                        xmax = maxOf(curr.xmax, other.xmax)
                                    )
                                    mergedAny = true
                                } else {
                                    nextRemaining.add(other)
                                }
                            }
                            remaining = nextRemaining
                        } while (mergedAny)
                        mergedRois.add(curr)
                    }

                    for (box in mergedRois) {
                        val pxX = (box.xmin * imgW).toInt().coerceIn(0, imgW - 1)
                        val pxY = (box.ymin * imgH).toInt().coerceIn(0, imgH - 1)
                        val rawW = ((box.xmax - box.xmin) * imgW).toInt()
                        val rawH = ((box.ymax - box.ymin) * imgH).toInt()
                        val pxW = max(1, rawW).coerceIn(1, imgW - pxX)
                        val pxH = max(1, rawH).coerceIn(1, imgH - pxY)

                        val actualRoiXmin = pxX.toDouble() / imgW.toDouble()
                        val actualRoiYmin = pxY.toDouble() / imgH.toDouble()
                        val actualRoiXmax = (pxX + pxW).toDouble() / imgW.toDouble()
                        val actualRoiYmax = (pxY + pxH).toDouble() / imgH.toDouble()

                        val roiSubImage = baseImage.getSubimage(pxX, pxY, pxW, pxH)
                        val tensor = ImageTensorUtils.createTensor(
                            rfdetrSession.environment,
                            roiSubImage,
                            rfdetrSpec.inputWidth,
                            rfdetrSpec.inputHeight
                        )

                        try {
                            val sessionResult = rfdetrSession.session.run(mapOf(inputName to tensor))
                            val localSegs = RfDetrPostprocessor.decodeOutputs(
                                sessionResult,
                                rfdetrSpec,
                                segmentationScoreThreshold
                            )
                            sessionResult.close()

                            val roiBox = DetectionBox(
                                label = box.label,
                                confidence = box.confidence,
                                ymin = actualRoiYmin,
                                xmin = actualRoiXmin,
                                ymax = actualRoiYmax,
                                xmax = actualRoiXmax
                            )

                            if (localSegs.isNotEmpty()) {
                                for (local in localSegs) {
                                    finalObjects.add(RfDetrPostprocessor.remapRoiToGlobal(local, roiBox))
                                }
                            } else {
                                // Fallback to bounding box contour if RF-DETR produced no local sub-segments
                                val polygon = RfDetrPostprocessor.generateBoxPolygon(box.xmin, box.ymin, box.xmax, box.ymax)
                                val area = (box.xmax - box.xmin) * (box.ymax - box.ymin)
                                finalObjects.add(
                                    SegmentedObject(
                                        label = box.label,
                                        confidence = box.confidence,
                                        box = box,
                                        polygon = polygon,
                                        shape = "rectangular",
                                        area = area
                                    )
                                )
                            }
                        } finally {
                            tensor.close()
                        }
                    }
                } else {
                    // Fallback to direct SAHI multi-scale sliced segmentation across image
                    val segSahiConfig = SahiConfig(
                        sliceWidth = rfdetrSpec.inputWidth,
                        sliceHeight = rfdetrSpec.inputHeight,
                        overlapWidthRatio = segmentOverlap.toFloat().coerceIn(0.0f, 0.8f),
                        overlapHeightRatio = segmentOverlap.toFloat().coerceIn(0.0f, 0.8f),
                        scoreThreshold = segmentationScoreThreshold,
                        iouThreshold = iouThreshold,
                        tileScale = segmentScale.coerceAtLeast(0.5)
                    )
                    val segs = SahiInferenceRunner.runSlicedSegmentation(
                        image = baseImage,
                        modelSpec = rfdetrSpec,
                        session = rfdetrSession,
                        config = segSahiConfig,
                        logger = logger
                    )
                    finalObjects.addAll(segs)
                }
            } finally {
                rfdetrSession.close()
            }
        } else {
            // If RF-DETR model not downloaded, convert YOLO detection boxes to SegmentedObjects
            for (box in candidateBoxes) {
                val polygon = RfDetrPostprocessor.generateBoxPolygon(box.xmin, box.ymin, box.xmax, box.ymax)
                val area = (box.xmax - box.xmin) * (box.ymax - box.ymin)
                finalObjects.add(
                    SegmentedObject(
                        label = box.label,
                        confidence = box.confidence,
                        box = box,
                        polygon = polygon,
                        shape = "rectangular",
                        area = area
                    )
                )
            }
        }

        var savedMaskPath: String? = null
        if (saveMask && outputDir.isNotBlank()) {
            val outFolder = File(outputDir)
            if (!outFolder.exists()) outFolder.mkdirs()

            val maskName = "${inputFile.nameWithoutExtension}_mask.png"
            val maskFile = File(outFolder, maskName)
            val maskImg = InpaintingUtils.renderMaskFromObjects(
                objects = finalObjects,
                imageWidth = imgW,
                imageHeight = imgH,
                targetClasses = setOf("text"),
                dilationPx = 3
            )
            withContext(Dispatchers.IO) {
                ImageIO.write(maskImg, "png", maskFile)
            }
            savedMaskPath = maskFile.absolutePath
            logger.info("Saved segmentation mask to: $savedMaskPath")
        }

        var savedDebugPath: String? = null
        if (saveDebugImage && outputDir.isNotBlank()) {
            val outFolder = File(outputDir)
            if (!outFolder.exists()) outFolder.mkdirs()

            val debugName = "${inputFile.nameWithoutExtension}_vision_debug.png"
            val debugFile = File(outFolder, debugName)
            val debugImg = InpaintingUtils.renderDebugVisualization(
                baseImage = baseImage,
                objects = finalObjects,
                candidateBoxes = candidateBoxes
            )
            withContext(Dispatchers.IO) {
                ImageIO.write(debugImg, "png", debugFile)
            }
            savedDebugPath = debugFile.absolutePath
            logger.info("Saved visual debug image to: $savedDebugPath")
        }

        logger.info("Vision complete for $imagePath. Detected ${finalObjects.size} segmented objects.")

        return VisionResult(
            objects = finalObjects,
            imageWidth = imgW,
            imageHeight = imgH,
            pageName = inputFile.name,
            maskPath = savedMaskPath,
            debugImagePath = savedDebugPath
        )
    }

    @Capability(
        name = "Detect and Segment Chapter",
        description = "Runs concurrent detection and segmentation for all images in a folder"
    )
    @RequiresLock(locks = ["model:yolo-det-x-best-v3", "model:rfdetr-seg-2xlarge-ema-v3"])
    suspend fun detectAndSegmentChapter(
        @CapabilityInput(description = "Path to folder containing chapter images", semanticTypes = ["path/folder"])
        folderPath: String,
        @CapabilityParam(description = "Detection confidence threshold", defaultValue = "0.25")
        detectionScoreThreshold: Double = 0.25,
        @CapabilityParam(description = "Segmentation confidence threshold", defaultValue = "0.25")
        segmentationScoreThreshold: Double = 0.25,
        @CapabilityParam(description = "IoU threshold", defaultValue = "0.45")
        iouThreshold: Double = 0.45,
        @CapabilityParam(description = "Detection tile resolution scale factor (e.g. 1.0 = 640px, 2.0 = 1280px downscaled)", defaultValue = "1.0")
        detectScale: Double = 1.0,
        @CapabilityParam(description = "Detection sliding window overlap ratio (0.0 to 0.8)", defaultValue = "0.25")
        detectOverlap: Double = 0.25,
        @CapabilityParam(description = "Segmentation tile/ROI resolution scale factor (e.g. 1.0 = 640px, 2.0 = 1280px)", defaultValue = "1.0")
        segmentScale: Double = 1.0,
        @CapabilityParam(description = "Segmentation sliding window overlap ratio (0.0 to 0.8)", defaultValue = "0.25")
        segmentOverlap: Double = 0.25,
        @CapabilityParam(description = "Save binary masks for each page", defaultValue = "false")
        saveMasks: Boolean = false,
        @CapabilityParam(description = "Save visual debug images for each page", defaultValue = "false")
        saveDebugImages: Boolean = false,
        @CapabilityOutput(description = "Output directory for masks/debug images", defaultValue = "", semanticTypes = ["path/folder"])
        outputDir: String = "",
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterVisionResult {
        val logger = context.logger
        val progressReporter = context.progress

        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Input folder not found or is not a directory: $folderPath")
        }

        val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")
        val imageFiles = folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.sortedBy { it.name } ?: emptyList()

        if (imageFiles.isEmpty()) {
            throw IllegalArgumentException("No valid images found in folder: $folderPath")
        }

        logger.info("Starting Chapter Vision for ${imageFiles.size} images in $folderPath")

        val totalImages = imageFiles.size
        val results = mutableListOf<VisionResult>()

        for ((index, file) in imageFiles.withIndex()) {
            val result = detectAndSegment(
                imagePath = file.absolutePath,
                detectionScoreThreshold = detectionScoreThreshold,
                segmentationScoreThreshold = segmentationScoreThreshold,
                iouThreshold = iouThreshold,
                detectScale = detectScale,
                detectOverlap = detectOverlap,
                segmentScale = segmentScale,
                segmentOverlap = segmentOverlap,
                saveMask = saveMasks,
                saveDebugImage = saveDebugImages,
                outputDir = outputDir,
                context = context,
                hostFs = hostFs
            )
            results.add(result)
            progressReporter.report((index + 1).toFloat() / totalImages.toFloat())
        }

        val totalObjects = results.sumOf { it.objects.size }
        logger.info("Chapter Vision complete. Processed $totalImages images, detected $totalObjects objects.")

        return ChapterVisionResult(
            results = results,
            totalObjectsDetected = totalObjects
        )
    }

    @Capability(
        name = "Detect",
        description = "Runs YOLO object detection on an image"
    )
    @RequiresLock(locks = ["model:yolo-det-x-best-v3"])
    suspend fun detect(
        @CapabilityInput(description = "Path to input image", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityParam(description = "Confidence threshold", defaultValue = "0.25")
        scoreThreshold: Double = 0.25,
        @CapabilityParam(description = "IoU threshold", defaultValue = "0.45")
        iouThreshold: Double = 0.45,
        @CapabilityParam(description = "Detection tile resolution scale factor (e.g. 1.0 = 640px, 2.0 = 1280px downscaled)", defaultValue = "1.0")
        detectScale: Double = 1.0,
        @CapabilityParam(description = "Detection sliding window overlap ratio (0.0 to 0.8)", defaultValue = "0.25")
        detectOverlap: Double = 0.25,
        @CapabilityParam(description = "Save visual debug image showing all detected bounding boxes", defaultValue = "false")
        saveDebugImage: Boolean = false,
        @CapabilityOutput(description = "Optional output directory to save debug image", defaultValue = "", semanticTypes = ["path/folder"])
        outputDir: String = "",
        context: PluginContext,
        hostFs: HostFileSystem
    ): DetectionResult {
        val inputFile = File(imagePath)
        if (!inputFile.exists()) throw IllegalArgumentException("Image not found: $imagePath")

        val img = withContext(Dispatchers.IO) { ImageIO.read(inputFile) }
            ?: throw IllegalArgumentException("Could not decode image: $imagePath")

        val yoloModelId = ModelCatalog.YOLO_DET_X_ID
        val yoloSpec = ModelManager.Default.getModelSpec(yoloModelId, context.fileSystem)
            ?: ModelSpec(
                name = yoloModelId,
                type = "yolo_v10",
                modelPath = "$yoloModelId.onnx",
                inputWidth = 640,
                inputHeight = 640,
                scoreThreshold = scoreThreshold,
                iouThreshold = iouThreshold,
                classes = listOf("balloon", "text", "watermark")
            )

        val session = ModelManager.Default.createInferenceSession(yoloModelId, context.fileSystem, ExecutionDevice.AUTO, context.logger)
            ?: throw IllegalStateException("Model '$yoloModelId' is not installed or could not be loaded.")
        return try {
            val sahiConfig = SahiConfig(
                sliceWidth = yoloSpec.inputWidth,
                sliceHeight = yoloSpec.inputHeight,
                overlapWidthRatio = detectOverlap.toFloat().coerceIn(0.0f, 0.8f),
                overlapHeightRatio = detectOverlap.toFloat().coerceIn(0.0f, 0.8f),
                scoreThreshold = scoreThreshold,
                iouThreshold = iouThreshold,
                tileScale = detectScale.coerceAtLeast(0.5)
            )
            val detResult = SahiInferenceRunner.runSlicedInference(
                image = img,
                modelSpec = yoloSpec,
                session = session,
                config = sahiConfig,
                logger = context.logger
            )

            if (saveDebugImage && outputDir.isNotBlank()) {
                val outFolder = File(outputDir)
                if (!outFolder.exists()) outFolder.mkdirs()
                val debugName = "${inputFile.nameWithoutExtension}_detect_debug.png"
                val debugFile = File(outFolder, debugName)
                val objects = detResult.boxes.map { box ->
                    SegmentedObject(
                        label = box.label,
                        confidence = box.confidence,
                        box = box,
                        polygon = RfDetrPostprocessor.generateBoxPolygon(box.xmin, box.ymin, box.xmax, box.ymax)
                    )
                }
                val debugImg = InpaintingUtils.renderDebugVisualization(
                    baseImage = img,
                    objects = objects
                )
                withContext(Dispatchers.IO) {
                    ImageIO.write(debugImg, "png", debugFile)
                }
            }

            detResult
        } finally {
            session.close()
        }
    }
}
