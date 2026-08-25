package com.wip.slicer

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.common.models.DetectionBox
import com.wip.common.models.ExecutionDevice
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.ModelSpec
import com.wip.common.models.OnnxInferenceEngine
import com.wip.common.models.SahiConfig
import com.wip.common.models.SahiInferenceRunner
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.abs
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
    version = "1.4.0",
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
        val sortedImages = images.sortedWith(natsortComparator)
        if (sortedImages.isEmpty()) throw IllegalArgumentException("No valid images found in the specified folder.")

        val firstImage = ImageIO.read(java.io.File(sortedImages[0].toString()))
        val width = firstImage.width
        val fullBitmap = mutableListOf<BufferedImage>()

        // 1. Analyze base row pixel variances
        val usefulRowVarianceList = analyzeRowVariances(sortedImages, cutTolerance, fullBitmap, context.progress).toMutableList()
        val totalHeight = usefulRowVarianceList.size
        progressReporter.report(0.35f)

        // 2. Load ONNX model and run SAHI detection to mask forbidden rows
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
            var currentYOffset = 0
            val sahiConfig = SahiConfig(
                sliceWidth = modelSpec.inputWidth,
                sliceHeight = modelSpec.inputHeight,
                scoreThreshold = scoreThreshold,
                iouThreshold = modelSpec.iouThreshold
            )

            val totalImages = fullBitmap.size
            fullBitmap.forEachIndexed { index, img ->
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
                    yOffset = currentYOffset,
                    detectionMargin = detectionMargin
                )

                logger.info("Smart Slicer: Image [${index + 1}/$totalImages] (${img.width}x${img.height}) detected ${detections.boxes.size} objects.")
                currentYOffset += img.height
                progressReporter.report(0.35f + ((index + 1).toFloat() / totalImages) * 0.25f)
            }
        } finally {
            session.close()
        }

        progressReporter.report(0.60f)

        // 3. Find optimal cuts respecting both variance and detection exclusion zones
        val (finalCuts, totalError) = findOptimalCuts(totalHeight, usefulRowVarianceList, minHeight, desiredHeight, maxHeight, prioritizeSmallerImages, context.progress)

        if (finalCuts.isEmpty()) throw IllegalArgumentException("No valid cuts found. Please adjust the parameters.")

        saveSlices(fullBitmap, width, totalHeight, finalCuts, Path(outputFolderPath))

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
        val step = .4f/files.size
        val sortedImages = images.sortedWith(natsortComparator)
        if (sortedImages.isEmpty()) throw IllegalArgumentException("No valid images found in the specified folder.")

        // Combine images into one large buffer conceptually to handle merging small ones
        val firstImage = ImageIO.read(java.io.File(sortedImages[0].toString()))
        val width = firstImage.width
        val fullBitmap = mutableListOf<BufferedImage>()

        val usefulRowVarianceList = analyzeRowVariances(sortedImages, cutTolerance, fullBitmap, context.progress)
        val totalHeight = usefulRowVarianceList.size
        progressReporter.report(0.4f)

        val (finalCuts, totalError) = findOptimalCuts(totalHeight, usefulRowVarianceList, minHeight, desiredHeight, maxHeight, prioritizeSmallerImages, context.progress)

        if (finalCuts.isEmpty()) throw IllegalArgumentException("No valid cuts found. Please adjust the parameters.")

        saveSlices(fullBitmap, width, totalHeight, finalCuts, Path(outputFolderPath))

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
        val sortedImages = images.sortedWith(natsortComparator)
        if (sortedImages.isEmpty()) throw IllegalArgumentException("No valid images found in the specified folder.")

        val progressIncrement = 0.9f / sortedImages.size
        val validRows = mutableListOf<Int>()
        sortedImages.forEachIndexed { index, imagePath ->
            val originalImg = ImageIO.read(java.io.File(imagePath.toString()))
            val img = ensureFastImage(originalImg)
            for (i in 0 until img.height) {
                validRows.add(if (analyzeSingleRowVariance(img, i) <= cutTolerance) 1 else 0)
            }
            progressReporter.report(progressIncrement*(index+1))
        }

        var max_distance = 0
        var local_distance = 0

        val notifyInterval = validRows.size / 10
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
        //if (image.type == BufferedImage.TYPE_INT_RGB || image.type == BufferedImage.TYPE_INT_ARGB) {
        //    return image
        //}
        val newImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = newImage.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return newImage
    }

    private fun analyzeSingleRowVariance(bufferedImage: BufferedImage, y: Int): Int {
        val width = bufferedImage.width
        if (width <= 1) return 0
        val row = IntArray(width)
        bufferedImage.getRGB(0, y, width, 1, row, 0, width)
        
        var maxDiff = 0
        for (x in 0 until width - 1) {
            val pixel1 = row[x]
            val pixel2 = row[x + 1]

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
        fullBitmap: MutableList<BufferedImage>,
        progressReporter: org.wip.plugintoolkit.api.ProgressReporter
    ): List<Boolean> {
        val rowVarianceList = mutableListOf<Int>()
        val total = sortedImages.size
        sortedImages.forEachIndexed { index, imagePath ->
            val originalImg = ImageIO.read(java.io.File(imagePath.toString()))
            val bufferedImage = ensureFastImage(originalImg)
            fullBitmap.add(bufferedImage)
            for (y in 0 until bufferedImage.height) {
                rowVarianceList.add(analyzeSingleRowVariance(bufferedImage, y))
            }
            progressReporter.report(0.1f + ((index + 1).toFloat() / total) * 0.3f)
        }
        return rowVarianceList.map { it <= cutTolerance }
    }

    private fun findOptimalCuts(
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

        for (i in 0 until totalHeight) {
            if (i % notifyInterval == 0) {
                progressReporter.report(0.4f + (i.toFloat() / totalHeight) * 0.4f)
            }
            if (dp[i] == Long.MAX_VALUE) continue

            val searchStart = (i + minHeight.coerceAtLeast(1)).coerceAtMost(totalHeight)
            val searchEnd = (i + maxHeight).coerceAtMost(totalHeight)

            // Forced cut target is desiredHeight, but clamped to search range to respect min/max if possible
            val forcedJ = (i + desiredHeight).coerceIn(searchStart, searchEnd)

            for (j in searchStart..searchEnd) {
                val isUseful = (j in 1..totalHeight && usefulRowVarianceList[j - 1]) || j == totalHeight
                val isForced = (j == forcedJ)

                if (isUseful || isForced) {
                    val sliceHeight = j - i
                    val diff = sliceHeight - desiredHeight
                    val absDiff = abs(diff)

                    // Primary error: absolute difference scaled for sub-pixel penalties
                    var currentError = absDiff.toLong() * 1000L

                    // 1. "Perfect Cut" Bias: Penalty for any non-zero deviation.
                    // This ensures hitting exactly desiredHeight is preferred over balancing multiple offsets.
                    if (absDiff > 0) {
                        currentError += 500L
                    }

                    // 2. Asymmetric Penalty for prioritizeSmallerImages:
                    // Penalize "over" slices more than "under" slices.
                    if (prioritizeSmallerImages && diff > 0) {
                        currentError += absDiff.toLong() * 200L // 20% extra penalty for being over
                    }

                    // 3. Forced Cut Penalty: Apply if we are forcing a cut on a non-useful row
                    if (!isUseful && isForced) {
                        currentError += FORCED_CUT_PENALTY
                    }

                    val totalError = dp[i] + currentError

                    // We use strictly less-than. This preserves the "best early" path in case of remaining ties,
                    // since the outer loop 'i' visits earlier starting points first.
                    if (totalError < dp[j]) {
                        dp[j] = totalError
                        parent[j] = i
                    }
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
        fullBitmap: List<BufferedImage>,
        width: Int,
        totalHeight: Int,
        finalCuts: List<Int>,
        outputDir: Path
    ) {
        if (!SystemFileSystem.exists(outputDir)) SystemFileSystem.createDirectories(outputDir)

        val combinedImage = BufferedImage(width, totalHeight, BufferedImage.TYPE_INT_RGB)
        val g = combinedImage.createGraphics()
        var currentY = 0
        fullBitmap.forEach { img ->
            g.drawImage(img, 0, currentY, null)
            currentY += img.height
        }
        g.dispose()

        var prevCut = 0
        finalCuts.forEachIndexed { index, cut ->
            val sliceHeight = cut - prevCut
            val slice = combinedImage.getSubimage(0, prevCut, width, sliceHeight)
            val outputFilePath = Path("${outputDir}/${index + 1}.png")
            SystemFileSystem.sink(outputFilePath).buffered().use { sink ->
                ImageIO.write(slice, "png", sink.asOutputStream())
            }
            prevCut = cut
        }
    }

    private val natsortComparator = object : Comparator<Path> {
        override fun compare(file1: Path, file2: Path): Int {
            val s1 = file1.name
            val s2 = file2.name
            var i = 0
            var j = 0
            while (i < s1.length && j < s2.length) {
                val c1 = s1[i]
                val c2 = s2[j]
                if (c1.isDigit() && c2.isDigit()) {
                    var num1 = ""
                    while (i < s1.length && s1[i].isDigit()) num1 += s1[i++]
                    var num2 = ""
                    while (j < s2.length && s2[j].isDigit()) num2 += s2[j++]
                    val n1 = num1.toLongOrNull() ?: Long.MAX_VALUE
                    val n2 = num2.toLongOrNull() ?: Long.MAX_VALUE
                    if (n1 != n2) return n1.compareTo(n2)
                    if (num1.length != num2.length) return num1.length.compareTo(num2.length)
                } else {
                    val lc1 = c1.lowercaseChar()
                    val lc2 = c2.lowercaseChar()
                    if (lc1 != lc2) return lc1.compareTo(lc2)
                    i++
                    j++
                }
            }
            return s1.length.compareTo(s2.length)
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
