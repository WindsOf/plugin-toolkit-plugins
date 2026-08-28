package com.wip.slicer

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.common.models.DetectionBox
import com.wip.common.models.ExecutionDevice
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.ModelSpec
import com.wip.common.models.NaturalOrderComparator
import com.wip.common.models.OnnxInferenceEngine
import com.wip.common.models.SahiConfig
import com.wip.common.models.SahiInferenceRunner
import com.wip.common.models.sortedNaturally
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.io.asOutputStream
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
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
    id = "com.wip.slicer",
    name = "Slicer",
    version = "1.4.2",
    description = "A plugin that provides vertical images sliding capabilities for manhwa.",
    supportedOs = [OS.WINDOWS]
)
class Slicer {
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
            // Ignore
        }
    }

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[Slicer] onLoad: Initializing Slicer...")
        return Result.success(Unit)
    }

    @PluginLocks
    suspend fun checkLocks(context: PluginContext): Map<String, Boolean> {
        val logger = context.logger
        logger.info("[Slicer] checkLocks: Checking model locks...")
        val locks = ModelManager.Default.getLocksState(context.fileSystem, logger)
        logger.info("[Slicer] checkLocks: Locks check completed: $locks")
        return locks
    }

    @PluginAction(
        name = "Download Model",
        description = "Downloads a specific ONNX model and descriptor to local plugin storage"
    )
    suspend fun downloadModel(modelName: String, context: PluginContext) {
        val logger = context.logger
        logger.info("[Slicer] downloadModel action triggered for: $modelName")
        val result = ModelManager.Default.downloadModel(modelName, context)
        if (result.isFailure) {
            logger.error("[Slicer] downloadModel action failed for $modelName: ${result.exceptionOrNull()?.message}")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download model $modelName")
        }
        logger.info("[Slicer] downloadModel action succeeded for: $modelName")
    }

    @PluginAction(
        name = "Download All Models",
        description = "Downloads all required ONNX models and descriptors to local plugin storage"
    )
    suspend fun downloadAllModels(context: PluginContext) {
        val logger = context.logger
        logger.info("[Slicer] downloadAllModels action triggered")
        val result = ModelManager.Default.downloadAllModels(context)
        if (result.isFailure) {
            logger.error("[Slicer] downloadAllModels action failed: ${result.exceptionOrNull()?.message}")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download all models")
        }
        logger.info("[Slicer] downloadAllModels action succeeded")
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        context.logger.info("[Slicer] setup: Slicer setup complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        context.logger.info("[Slicer] validate: Slicer validation passed.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[Slicer] update: Slicer update complete.")
        return Result.success(Unit)
    }

    @Capability(
        name = "Smart Slicer",
        description = "Slices images while intelligently avoiding cutting through detected speech balloons, text, and panels using YOLO detection"
    )
    @RequiresLock(locks = ["model:yolo-det-x-best-v3"])
    suspend fun smartSlicer(
        @CapabilityInput(
            description = "List of items to slice",
            semanticTypes = ["path/folder"]
        )
        folderPath: String,
        @CapabilityOutput(
            description = "Output folder path",
            autogeneratedPattern = "{folderPath}/sliced/",
            isDestructive = true,
            semanticTypes = ["path/folder"]
        )
        outputFolderPath: String,
        @CapabilityParam(description = "Minimum height", defaultValue = "1000") minHeight: Int,
        @CapabilityParam(description = "Desired Height", defaultValue = "10000") desiredHeight: Int,
        @CapabilityParam(description = "Maximum Height", defaultValue = "10000") maxHeight: Int,
        @CapabilityParam(description = "Prioritize smaller", defaultValue = "true") prioritizeSmallerImages: Boolean,
        @CapabilityParam(description = "Cut tolerance", defaultValue = "5") cutTolerance: Int,
        @CapabilityParam(description = "Safety margin in pixels around detected objects where cuts are forbidden", defaultValue = "15") detectionMargin: Int,
        @CapabilityParam(description = "Confidence threshold for object detections", defaultValue = "0.25") scoreThreshold: Double,
        context: PluginContext,
        hostFs: HostFileSystem
    ) {
        val logger = context.logger
        val progressReporter = context.progress

        logger.info("Starting Smart Slicer with YOLO detection for folder: $folderPath")
        progressReporter.report(0.05f)

        val folder = Path(folderPath)
        val files = SystemFileSystem.list(folder).toList()

        if (files.isEmpty()) throw IllegalArgumentException("No files found in the specified folder.")
        val images = files.filter { path ->
            val metadata = SystemFileSystem.metadataOrNull(path)
            metadata?.isRegularFile == true && path.name.lowercase().let { name ->
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
            }
        }
        val sortedImages = images.sortedNaturally()
        if (sortedImages.isEmpty()) throw IllegalArgumentException("No valid images found in the specified folder.")

        val imageSources = mutableListOf<ImageSliceSource>()

        // 1. Analyze base row pixel variances (without retaining full bitmaps in memory)
        val usefulRowVarianceList = analyzeRowVariances(sortedImages, cutTolerance, imageSources, context.progress).toMutableList()
        val totalHeight = usefulRowVarianceList.size
        val width = imageSources.firstOrNull()?.width ?: 0
        progressReporter.report(0.35f)

        // 2. Load ONNX model and run SAHI detection on individual images to mask forbidden rows
        val modelSpec = ModelManager.Default.getModelSpec(ModelCatalog.YOLO_DET_X_ID, context.fileSystem)
            ?: ModelSpec(
                name = ModelCatalog.YOLO_DET_X_ID,
                type = "yolo_v10",
                modelPath = "${ModelCatalog.YOLO_DET_X_ID}.onnx",
                inputWidth = 640,
                inputHeight = 640,
                scoreThreshold = scoreThreshold,
                iouThreshold = 0.45,
                classes = listOf("balloon", "text", "watermark")
            )

        val session = ModelManager.Default.createInferenceSession(ModelCatalog.YOLO_DET_X_ID, context.fileSystem, ExecutionDevice.AUTO, logger)
            ?: throw IllegalStateException("Detection model '${ModelCatalog.YOLO_DET_X_ID}' is not installed. Please run the download action.")
        try {
            val sahiConfig = SahiConfig(
                sliceWidth = modelSpec.inputWidth,
                sliceHeight = modelSpec.inputHeight,
                scoreThreshold = scoreThreshold,
                iouThreshold = modelSpec.iouThreshold
            )

            val totalImages = imageSources.size
            imageSources.forEachIndexed { index, imgSource ->
                val imgFile = java.io.File(imgSource.path.toString())
                val originalImg = ImageIO.read(imgFile)
                    ?: throw IllegalArgumentException("Failed to decode image at ${imgSource.path}")
                val img = ensureFastImage(originalImg)

                val detections = SahiInferenceRunner.runSlicedInference(
                    image = img,
                    modelSpec = modelSpec,
                    session = session,
                    config = sahiConfig,
                    logger = logger
                )

                maskForbiddenDetectionRows(
                    usefulRowVarianceList = usefulRowVarianceList,
                    detections = detections.boxes,
                    imageHeight = img.height,
                    yOffset = imgSource.globalYStart,
                    detectionMargin = detectionMargin
                )

                logger.info("Smart Slicer: Image [${index + 1}/$totalImages] (${img.width}x${img.height}) detected ${detections.boxes.size} objects.")
                progressReporter.report(0.35f + ((index + 1).toFloat() / totalImages) * 0.25f)
            }
        } finally {
            session.close()
        }

        progressReporter.report(0.60f)

        // 3. Find optimal cuts using accelerated candidate-based DP respecting variance and detection zones
        val (finalCuts, totalError) = findOptimalCuts(totalHeight, usefulRowVarianceList, minHeight, desiredHeight, maxHeight, prioritizeSmallerImages, context.progress)

        if (finalCuts.isEmpty()) throw IllegalArgumentException("No valid cuts found. Please adjust the parameters.")

        // 4. Save slices on demand without allocating monolithic heap images
        saveSlices(imageSources, width, totalHeight, finalCuts, Path(outputFolderPath))

        val displayError = totalError.toDouble() / 1000.0
        progressReporter.report(1.0f)
        logger.info("Smart Slicing completed. Error: $displayError. Output directory: $outputFolderPath")
    }

    @Capability(
        name = "slicer",
        description = "Slices a list of images"
    )
    fun slicer(
        @CapabilityInput(
            description = "List of items to slice",
            semanticTypes = ["path/folder"]
        )
        folderPath: String,
        @CapabilityOutput(
            description = "Output folder path",
            autogeneratedPattern = "{folderPath}/sliced/",
            isDestructive = true,
            semanticTypes = ["path/folder"]
        )
        outputFolderPath: String,
        @CapabilityParam(description = "Minimum height", defaultValue = "1000") minHeight: Int,
        @CapabilityParam(description = "Desired Height", defaultValue = "10000") desiredHeight: Int,
        @CapabilityParam(description = "Maximum Height", defaultValue = "10000") maxHeight: Int,
        @CapabilityParam(description = "Prioritize smaller", defaultValue = "true") prioritizeSmallerImages: Boolean,
        @CapabilityParam(description = "Cut tolerance", defaultValue = "5") cutTolerance: Int,
        context: PluginContext,
        hostFs: HostFileSystem
    ) {
        val logger = context.logger
        val progressReporter = context.progress

        logger.log("Starting slicer for folder: $folderPath")
        progressReporter.report(0.1f)
        
        val folder = Path(folderPath)
        val files = SystemFileSystem.list(folder).toList()
        
        if (files.isEmpty()) throw IllegalArgumentException("No files found in the specified folder.")
        val images = files.filter { path ->
            val metadata = SystemFileSystem.metadataOrNull(path)
            metadata?.isRegularFile == true && path.name.lowercase().let { name ->
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
            }
        }
        val sortedImages = images.sortedNaturally()
        if (sortedImages.isEmpty()) throw IllegalArgumentException("No valid images found in the specified folder.")

        val imageSources = mutableListOf<ImageSliceSource>()
        val usefulRowVarianceList = analyzeRowVariances(sortedImages, cutTolerance, imageSources, context.progress)
        val totalHeight = usefulRowVarianceList.size
        val width = imageSources.firstOrNull()?.width ?: 0
        progressReporter.report(0.4f)

        val (finalCuts, totalError) = findOptimalCuts(totalHeight, usefulRowVarianceList, minHeight, desiredHeight, maxHeight, prioritizeSmallerImages, context.progress)

        if (finalCuts.isEmpty()) throw IllegalArgumentException("No valid cuts found. Please adjust the parameters.")

        saveSlices(imageSources, width, totalHeight, finalCuts, Path(outputFolderPath))

        val displayError = totalError.toDouble() / 1000.0
        progressReporter.report(1.0f)
        logger.log("Slicing completed. Error: $displayError. Output directory: $outputFolderPath")
    }

    @Capability(
        name = "Max distance for cuts",
        description = "Analyzes row variances and determines the biggest section without cuts"
    )
    fun maxDistanceForCuts(
        @CapabilityInput(description = "List of items to slice", semanticTypes = ["path/folder"])
        folderPath: String,
        @CapabilityParam(description = "Cut tolerance", defaultValue = "5") cutTolerance: Int,
        context: PluginContext,
        hostFs: HostFileSystem
    ): Int {
        val logger = context.logger
        val progressReporter = context.progress

        logger.log("Starting slicer for folder: $folderPath")

        val folder = Path(folderPath)
        val files = SystemFileSystem.list(folder).toList()

        if (files.isEmpty()) throw IllegalArgumentException("No files found in the specified folder.")
        val images = files.filter { path ->
            val metadata = SystemFileSystem.metadataOrNull(path)
            metadata?.isRegularFile == true && path.name.lowercase().let { name ->
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
            }
        }
        val sortedImages = images.sortedNaturally()
        if (sortedImages.isEmpty()) throw IllegalArgumentException("No valid images found in the specified folder.")

        val progressIncrement = 0.9f / sortedImages.size
        val validRows = mutableListOf<Int>()
        sortedImages.forEachIndexed { index, imagePath ->
            val originalImg = ImageIO.read(java.io.File(imagePath.toString()))
            val img = ensureFastImage(originalImg)
            val rowBuffer = IntArray(img.width)
            for (i in 0 until img.height) {
                validRows.add(if (analyzeSingleRowVariance(img, i, rowBuffer) <= cutTolerance) 1 else 0)
            }
            progressReporter.report(progressIncrement*(index+1))
        }

        var max_distance = 0
        var local_distance = 0

        val notifyInterval = (validRows.size / 10).coerceAtLeast(1)
        validRows.forEachIndexed { index, it ->
            if (index % notifyInterval == 0) {
                progressReporter.report(0.9f+(index/notifyInterval)*0.01f)
            }
            if (it == 0) {
                if (local_distance > max_distance)
                    max_distance = local_distance
                local_distance = 0
            } else {
                local_distance++
            }
        }

        return max_distance
    }

    private fun ensureFastImage(image: BufferedImage): BufferedImage {        
        if (image.type == BufferedImage.TYPE_INT_RGB || image.type == BufferedImage.TYPE_INT_ARGB) {
            return image
        }
        val newImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = newImage.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return newImage
    }

    private fun analyzeSingleRowVariance(bufferedImage: BufferedImage, y: Int, rowBuffer: IntArray): Int {
        val width = bufferedImage.width
        if (width <= 1) return 0
        bufferedImage.getRGB(0, y, width, 1, rowBuffer, 0, width)
        
        var maxDiff = 0
        for (x in 0 until width - 1) {
            val pixel1 = rowBuffer[x]
            val pixel2 = rowBuffer[x + 1]

            val rDiff = abs((pixel1 shr 16 and 0xFF) - (pixel2 shr 16 and 0xFF))
            val gDiff = abs((pixel1 shr 8 and 0xFF) - (pixel2 shr 8 and 0xFF))
            val bDiff = abs((pixel1 and 0xFF) - (pixel2 and 0xFF))

            val currentMax = rDiff.coerceAtLeast(gDiff.coerceAtLeast(bDiff))
            if (currentMax > maxDiff) maxDiff = currentMax
        }
        return maxDiff
    }

    private fun analyzeRowVariances(
        sortedImages: List<Path>,
        cutTolerance: Int,
        imageSources: MutableList<ImageSliceSource>,
        progressReporter: org.wip.plugintoolkit.api.ProgressReporter
    ): List<Boolean> {
        val rowVarianceList = mutableListOf<Boolean>()
        val total = sortedImages.size
        var currentY = 0
        sortedImages.forEachIndexed { index, imagePath ->
            val imgFile = java.io.File(imagePath.toString())
            val originalImg = ImageIO.read(imgFile)
                ?: throw IllegalArgumentException("Failed to decode image at $imagePath")
            val bufferedImage = ensureFastImage(originalImg)
            imageSources.add(ImageSliceSource(imagePath, bufferedImage.width, bufferedImage.height, currentY))
            currentY += bufferedImage.height

            val rowBuffer = IntArray(bufferedImage.width)
            for (y in 0 until bufferedImage.height) {
                rowVarianceList.add(analyzeSingleRowVariance(bufferedImage, y, rowBuffer) <= cutTolerance)
            }
            progressReporter.report(0.1f + ((index + 1).toFloat() / total) * 0.25f)
        }
        return rowVarianceList
    }

    internal fun findOptimalCuts(
        totalHeight: Int,
        usefulRowVarianceList: List<Boolean>,
        minHeight: Int,
        desiredHeight: Int,
        maxHeight: Int,
        prioritizeSmallerImages: Boolean,
        progressReporter: org.wip.plugintoolkit.api.ProgressReporter
    ): Pair<List<Int>, Long> {
        val dp = LongArray(totalHeight + 1) { Long.MAX_VALUE }
        val parent = IntArray(totalHeight + 1) { -1 }
        dp[0] = 0

        val FORCED_CUT_PENALTY = 1_000_000_000_000L
        val notifyInterval = (totalHeight / 20).coerceAtLeast(1)

        // Precompute sorted array of candidate useful cut positions (1-indexed cut positions)
        val usefulIndices = mutableListOf<Int>()
        for (y in 0 until totalHeight) {
            if (usefulRowVarianceList[y]) {
                usefulIndices.add(y + 1)
            }
        }
        if (usefulIndices.isEmpty() || usefulIndices.last() != totalHeight) {
            usefulIndices.add(totalHeight)
        }
        val usefulArray = usefulIndices.toIntArray()

        for (i in 0 until totalHeight) {
            if (i % notifyInterval == 0) {
                progressReporter.report(0.4f + (i.toFloat() / totalHeight) * 0.4f)
            }
            if (dp[i] == Long.MAX_VALUE) continue

            val searchStart = (i + minHeight.coerceAtLeast(1)).coerceAtMost(totalHeight)
            val searchEnd = (i + maxHeight).coerceAtMost(totalHeight)
            if (searchStart > searchEnd) continue

            // Forced cut target is desiredHeight, but clamped to search range to respect min/max if possible
            val forcedJ = (i + desiredHeight).coerceIn(searchStart, searchEnd)

            var startIdx = usefulArray.binarySearch(searchStart)
            if (startIdx < 0) startIdx = -(startIdx + 1)

            var idx = startIdx
            var forcedJEvaluated = false

            while (idx < usefulArray.size) {
                val j = usefulArray[idx]
                if (j > searchEnd) break
                val isForced = (j == forcedJ)
                if (isForced) forcedJEvaluated = true

                val sliceHeight = j - i
                val diff = sliceHeight - desiredHeight
                val absDiff = abs(diff)

                // Primary error: absolute difference scaled for sub-pixel penalties
                var currentError = absDiff.toLong() * 1000L

                // 1. "Perfect Cut" Bias: Penalty for any non-zero deviation.
                if (absDiff > 0) {
                    currentError += 500L
                }

                // 2. Asymmetric Penalty for prioritizeSmallerImages:
                if (prioritizeSmallerImages && diff > 0) {
                    currentError += absDiff.toLong() * 200L // 20% extra penalty for being over
                }

                val totalError = dp[i] + currentError
                if (totalError < dp[j]) {
                    dp[j] = totalError
                    parent[j] = i
                }

                idx++
            }

            // 3. Forced Cut Penalty: Apply if forced cut is not on a useful cut row
            if (!forcedJEvaluated) {
                val j = forcedJ
                val sliceHeight = j - i
                val diff = sliceHeight - desiredHeight
                val absDiff = abs(diff)

                var currentError = absDiff.toLong() * 1000L
                if (absDiff > 0) {
                    currentError += 500L
                }
                if (prioritizeSmallerImages && diff > 0) {
                    currentError += absDiff.toLong() * 200L
                }
                currentError += FORCED_CUT_PENALTY

                val totalError = dp[i] + currentError
                if (totalError < dp[j]) {
                    dp[j] = totalError
                    parent[j] = i
                }
            }
        }

        if (dp[totalHeight] == Long.MAX_VALUE) return emptyList<Int>() to -1L

        val cuts = mutableListOf<Int>()
        var curr = totalHeight
        while (curr > 0) {
            cuts.add(curr)
            curr = parent[curr]
        }
        return cuts.reversed() to dp[totalHeight]
    }

    private fun saveSlices(
        imageSources: List<ImageSliceSource>,
        width: Int,
        totalHeight: Int,
        finalCuts: List<Int>,
        outputDir: Path
    ) {
        if (!SystemFileSystem.exists(outputDir)) SystemFileSystem.createDirectories(outputDir)

        var prevCut = 0
        finalCuts.forEachIndexed { index, cut ->
            val sliceHeight = cut - prevCut
            if (sliceHeight <= 0) return@forEachIndexed

            val sliceImage = BufferedImage(width, sliceHeight, BufferedImage.TYPE_INT_RGB)
            val g = sliceImage.createGraphics()

            val overlappingSources = imageSources.filter { img ->
                val imgStart = img.globalYStart
                val imgEnd = img.globalYStart + img.height
                imgStart < cut && imgEnd > prevCut
            }

            for (src in overlappingSources) {
                val imgFile = java.io.File(src.path.toString())
                val sourceImg = ImageIO.read(imgFile) ?: continue
                val imgStart = src.globalYStart
                val imgEnd = src.globalYStart + src.height

                val overlapStart = max(prevCut, imgStart)
                val overlapEnd = min(cut, imgEnd)
                val copyHeight = overlapEnd - overlapStart
                if (copyHeight <= 0) continue

                val dstY = overlapStart - prevCut
                val srcY = overlapStart - imgStart

                g.drawImage(
                    sourceImg,
                    0, dstY, width, dstY + copyHeight,
                    0, srcY, min(width, sourceImg.width), srcY + copyHeight,
                    null
                )
            }
            g.dispose()

            val paddedIndex = (index + 1).toString().padStart(4, '0')
            val outputFilePath = Path("${outputDir}/${paddedIndex}.png")
            SystemFileSystem.sink(outputFilePath).buffered().use { sink ->
                ImageIO.write(sliceImage, "png", sink.asOutputStream())
            }
            sliceImage.flush()

            prevCut = cut
        }
    }

    /**
     * Masks out any row intervals overlapping detected objects (with added margin) from being cut.
     */
    fun maskForbiddenDetectionRows(
        usefulRowVarianceList: MutableList<Boolean>,
        detections: List<DetectionBox>,
        imageHeight: Int,
        yOffset: Int,
        detectionMargin: Int
    ) {
        val totalHeight = usefulRowVarianceList.size
        for (box in detections) {
            val boxYminPx = (box.ymin * imageHeight).toInt()
            val boxYmaxPx = (box.ymax * imageHeight).toInt()

            val forbiddenStart = (yOffset + boxYminPx - detectionMargin).coerceIn(0, totalHeight - 1)
            val forbiddenEnd = (yOffset + boxYmaxPx + detectionMargin).coerceIn(0, totalHeight - 1)

            for (y in forbiddenStart..forbiddenEnd) {
                usefulRowVarianceList[y] = false
            }
        }
    }
}

data class ImageSliceSource(
    val path: Path,
    val width: Int,
    val height: Int,
    val globalYStart: Int
)
