package com.wip.psdbuilder

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.ChapterCleanerResult
import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.CleanerResult
import com.wip.common.models.OCRResult
import com.wip.common.models.OcrVisionMerger
import com.wip.common.models.VisionResult
import com.wip.kpsd.Justification
import com.wip.kpsd.KPsd
import com.wip.kpsd.Layer
import com.wip.kpsd.PixelData
import com.wip.kpsd.Psd
import com.wip.kpsd.TextShapeType
import com.wip.kpsd.Units
import com.wip.kpsd.psd
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.CapabilityResult
import org.wip.plugintoolkit.api.annotations.ComplexObject
import org.wip.plugintoolkit.api.annotations.PluginAction
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginLoad
import org.wip.plugintoolkit.api.annotations.PluginLocks
import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate

enum class PsdFont {
    ANIME_ACE_2_0_BB,
    ARIAL
}

@Serializable
data class PsdColor(val r: Int, val g: Int, val b: Int, val a: Int)

@Serializable
data class PsdText(
    val text: String,
    val left: Int? = null,
    val top: Int? = null,
    val right: Int? = null,
    val bottom: Int? = null,
    val fontName: String? = null,
    val fontSize: Int? = null,
    val color: PsdColor? = null,
    val strokeSize: Int? = null,
    val rotation: Double? = null,
    val balloon_box_2d: List<Double>? = null,
    val text_box_2d: List<Double>? = null,
    val shape: String? = null,
    val fontStyle: String? = null,
    val fontFamily: String? = null,
    val textAngle: Double? = null,
    val isSparse: Boolean? = null,
    val textColor: String? = null,
    val hasBorder: Boolean? = null,
    val borderColor: String? = null
)

@Serializable
data class PsdPayload(
    val backgroundImage: String? = null,
    val texts: List<PsdText> = emptyList(),
    val balloons: List<PsdText> = emptyList()
)

@ComplexObject(
    id = "com.wip.psdbuilder.ExtractedText",
    description = "Extracted text layer metadata from a PSD file",
    version = 1
)
@Serializable
data class ExtractedText(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val fontName: String? = null,
    val fontSize: Float? = null
)

@ComplexObject(
    id = "com.wip.psdbuilder.PSDBuildResult",
    description = "Result containing the path to a single generated PSD file",
    version = 1
)
@Serializable
data class PSDBuildResult(
    @CapabilityResult(
        name = "generated psd",
        description = "Path to the generated PSD file",
        semanticTypes = ["path/file"]
    )
    val psdPath: String
)

@ComplexObject(
    id = "com.wip.psdbuilder.ChapterPSDBuildResult",
    description = "Result containing the list of paths to generated chapter PSD files",
    version = 1
)
@Serializable
data class ChapterPSDBuildResult(
    @CapabilityResult(
        name = "generated psds",
        description = "List of paths to the generated PSD files"
    )
    val psdPaths: List<String>
)

data class PSDBuilderSettings(
    @PluginSetting(
        description = "Enable debug mode to draw bounding boxes on the output image",
        defaultValue = "false",
        required = true
    )
    val debugMode: Boolean = false,

    @PluginSetting(
        description = "Percentage of the bounding box size to use as padding",
        defaultValue = "0.10",
        required = true
    )
    val paddingPercentage: Float = 0.10f
)

@PluginInfo(
    id = "com.wip.psdbuilder.native",
    name = "PSD Builder Native",
    version = "5.3.1",
    description = "A plugin that builds layered PSD files natively in Kotlin.",
    supportedOs = [OS.WINDOWS, OS.LINUX, OS.MACOS]
)
class PSDBuilderPlugin(val settings: PSDBuilderSettings = PSDBuilderSettings()) {

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[PSDBuilder] onLoad: Initializing PSDBuilderPlugin...")
        return Result.success(Unit)
    }

    init {
        ImageIO.setUseCache(false)
        try {
            val registry = IIORegistry.getDefaultInstance()
            val existing = registry.getServiceProviders(javax.imageio.spi.ImageReaderSpi::class.java, true)
            while (existing.hasNext()) {
                val spi = existing.next()
                if (spi.javaClass.name == "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi") {
                    registry.deregisterServiceProvider(spi)
                }
            }
            registry.registerServiceProvider(WebPImageReaderSpi())
        } catch (_: Exception) {
            // Ignore
        }
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        context.logger.info("[PSDBuilder] setup: PSDBuilderPlugin setup complete.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[PSDBuilder] update: PSDBuilderPlugin update complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        context.logger.info("[PSDBuilder] validate: PSDBuilderPlugin validation passed.")
        return Result.success(Unit)
    }

    @Capability(
        name = "Build PSD from Image and Texts",
        description = "Generates a layered PSD natively in Kotlin from an image, texts and bounding boxes"
    )
    suspend fun buildPsdFromInputs(
        @CapabilityInput(description = "Path to the base image (JPG, PNG, WebP)", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityInput(
            description = "Optional path to the clean/inpainted image or patch layer",
            defaultValue = "",
            semanticTypes = ["path/file"]
        )
        cleanImagePath: String? = null,
        @CapabilityParam(description = "List of text strings to render") texts: List<String>,
        @CapabilityParam(
            description = "List of bounding boxes for each balloon (ymin, xmin, ymax, xmax)",
            semanticTypes = ["wom/bounding-box"]
        ) balloonBoxes: List<List<Double>>,
        @CapabilityParam(
            description = "List of bounding boxes for each text (ymin, xmin, ymax, xmax)",
            semanticTypes = ["wom/bounding-box"]
        ) textBoxes: List<List<Double>>? = null,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(
            description = "Typeface for the text",
            defaultValue = "\"ANIME_ACE_2_0_BB\""
        ) fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(
            description = "Thickness of the text stroke/border (0 to disable)",
            defaultValue = "3"
        ) borderSize: Int? = 3,
        @CapabilityOutput(
            description = "Directory to save generated PSD",
            autogeneratedPattern = "{imagePath}/build_psd_output/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "Keep intermediate JSON and temp image files for debugging",
            defaultValue = "false"
        ) leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Rotations/Angles in degrees") textAngles: List<Double>? = null,
        @CapabilityParam(description = "Text Colors") textColors: List<String>? = null,
        @CapabilityParam(description = "Has Border") hasBorder: List<Boolean>? = null,
        @CapabilityParam(description = "Border Colors") borderColors: List<String>? = null,
        @CapabilityParam(description = "Shapes of the balloons") shapes: List<String>? = null,
        @CapabilityParam(description = "Desired height of the final PSD. 0 to disable merging", defaultValue = "0") desiredHeight: Int? = 0,
        @CapabilityParam(description = "Optional Cleaner result containing the cleaned image path") cleanResult: CleanerResult? = null,
        @CapabilityParam(description = "Optional Vision segmentation result for polygon-based balloon text centering") visionResult: VisionResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): PSDBuildResult {
        val logger = context.logger
        logger.info("Starting buildPsdFromInputs for $imagePath")

        val outDir = File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outputPsdPath = File(outDir, File(imagePath).nameWithoutExtension + ".psd").absolutePath

        val psdFontString = when (fontName) {
            PsdFont.ARIAL -> "ArialMT"
            PsdFont.ANIME_ACE_2_0_BB -> "AnimeAce2.0BB"
            else -> "AnimeAce2.0BB"
        }

        val colors = List(texts.size) { PsdColor(0, 0, 0, 255) }
        val fontSizes = List(texts.size) { fontSize ?: 60 }
        val fontNames = List(texts.size) { psdFontString }
        val borderSizes = List(texts.size) { borderSize ?: 0 }

        val resolvedCleanPath = cleanImagePath?.ifBlank { null } ?: cleanResult?.cleanedImagePath
        val resolvedVisionResult = visionResult ?: cleanResult?.segmentationData

        val psd = buildPsdObject(
            imagePath = imagePath,
            cleanImagePath = resolvedCleanPath,
            texts = texts,
            balloonBoxes = balloonBoxes,
            textBoxes = textBoxes,
            fontSizes = fontSizes,
            fontNames = fontNames,
            borderSizes = borderSizes,
            context = context,
            textAngles = textAngles,
            textColors = textColors,
            hasBorder = hasBorder,
            borderColors = borderColors,
            shapes = shapes,
            visionResult = resolvedVisionResult
        )
        val psdBytes = withContext(Dispatchers.Default) {
            KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
            logger.info("PSD Generation Complete: $outputPsdPath")
        }

        return PSDBuildResult(outputPsdPath)
    }

    @Capability(
        name = "Build PSD from Image and Advanced OCR Data",
        description = "Generates a layered PSD natively from an image and advanced OCR data"
    )
    suspend fun buildPsdFromAdvancedOcrData(
        @CapabilityInput(description = "Path to the base image", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityInput(
            description = "Optional path to the clean/inpainted image or patch layer",
            defaultValue = "",
            semanticTypes = ["path/file"]
        )
        cleanImagePath: String? = null,
        @CapabilityParam(description = "Advanced OCR Result") ocrData: AdvancedOCRResult,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityOutput(
            description = "Directory to save generated PSD",
            autogeneratedPattern = "{imagePath}/build_psd_output/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Optional Cleaner result containing the cleaned image path") cleanResult: CleanerResult? = null,
        @CapabilityParam(description = "Optional Vision segmentation result for polygon-based balloon text centering") visionResult: VisionResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): PSDBuildResult {
        val resolvedCleanPath = cleanImagePath?.ifBlank { null } ?: cleanResult?.cleanedImagePath
        val resolvedVisionResult = visionResult ?: cleanResult?.segmentationData
        return buildPsdFromInputs(
            imagePath = imagePath,
            cleanImagePath = resolvedCleanPath,
            texts = ocrData.texts,
            balloonBoxes = ocrData.balloonBoxes,
            textBoxes = ocrData.textBoxes,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = borderSize,
            outputDir = outputDir,
            leaveIntermediateFiles = leaveIntermediateFiles,
            textAngles = ocrData.textAngles,
            textColors = ocrData.textColors,
            hasBorder = ocrData.hasBorder,
            borderColors = ocrData.borderColors,
            shapes = ocrData.shapes,
            cleanResult = cleanResult,
            visionResult = resolvedVisionResult,
            context = context,
            hostFs = hostFs
        )
    }

    @Capability(
        name = "Build PSD from Image and OCR Data",
        description = "Generates a layered PSD natively from an image and basic OCR data"
    )
    suspend fun buildPsdFromOcrData(
        @CapabilityInput(description = "Path to the base image", semanticTypes = ["path/file"])
        imagePath: String,
        @CapabilityInput(
            description = "Optional path to the clean/inpainted image or patch layer",
            defaultValue = "",
            semanticTypes = ["path/file"]
        )
        cleanImagePath: String? = null,
        @CapabilityParam(description = "OCR Result") ocrData: OCRResult,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "0") borderSize: Int? = 0,
        @CapabilityOutput(
            description = "Directory to save generated PSD",
            autogeneratedPattern = "{imagePath}/build_psd_output/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Optional Cleaner result containing the cleaned image path") cleanResult: CleanerResult? = null,
        @CapabilityParam(description = "Optional Vision segmentation result for polygon-based balloon text centering") visionResult: VisionResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): PSDBuildResult {
        val resolvedCleanPath = cleanImagePath?.ifBlank { null } ?: cleanResult?.cleanedImagePath
        val resolvedVisionResult = visionResult ?: cleanResult?.segmentationData
        val safeBorderSize = borderSize ?: 0
        val safeHasBorder = if (safeBorderSize > 0) null else List(ocrData.texts.size) { false }
        return buildPsdFromInputs(
            imagePath = imagePath,
            cleanImagePath = resolvedCleanPath,
            texts = ocrData.texts,
            balloonBoxes = ocrData.bb,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = safeBorderSize,
            hasBorder = safeHasBorder,
            outputDir = outputDir,
            leaveIntermediateFiles = leaveIntermediateFiles,
            cleanResult = cleanResult,
            visionResult = resolvedVisionResult,
            context = context,
            hostFs = hostFs
        )
    }

    @Capability(
        name = "Build PSD for Chapter",
        description = "Generates layered PSDs natively for a folder of images concurrently"
    )
    suspend fun buildPsdForChapter(
        @CapabilityInput(
            description = "Path to folder containing chapter images",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String,
        @CapabilityInput(
            description = "Optional path to folder containing cleaned images/patches",
            defaultValue = "",
            semanticTypes = ["path/folder"]
        )
        cleanFolder: String? = null,
        @CapabilityParam(description = "List of text strings to render across all pages") texts: List<String>,
        @CapabilityParam(
            description = "List of bounding boxes for each balloon (ymin, xmin, ymax, xmax)",
            semanticTypes = ["wom/bounding-box"]
        ) balloonBoxes: List<List<Double>>,
        @CapabilityParam(
            description = "List of bounding boxes for each text (ymin, xmin, ymax, xmax)",
            semanticTypes = ["wom/bounding-box"]
        ) textBoxes: List<List<Double>>? = null,
        @CapabilityParam(description = "List of image filenames corresponding to each text") pageNames: List<String>,
        @CapabilityOutput(
            description = "Directory to save generated PSDs",
            autogeneratedPattern = "{inputFolder}/build_psd_output/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "60") fontSize: Int? = 60,
        @CapabilityParam(
            description = "Typeface for the text",
            defaultValue = "\"ANIME_ACE_2_0_BB\""
        ) fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(
            description = "Thickness of the text stroke/border (0 to disable)",
            defaultValue = "3"
        ) borderSize: Int? = 3,
        @CapabilityParam(
            description = "Keep intermediate JSON and temp image files for debugging",
            defaultValue = "false"
        ) leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Rotations/Angles in degrees") textAngles: List<Double>? = null,
        @CapabilityParam(description = "Text Colors") textColors: List<String>? = null,
        @CapabilityParam(description = "Has Border") hasBorder: List<Boolean>? = null,
        @CapabilityParam(description = "Border Colors") borderColors: List<String>? = null,
        @CapabilityParam(description = "Shapes of the balloons") shapes: List<String>? = null,
        @CapabilityParam(description = "Desired height of the final PSD. 0 to disable merging", defaultValue = "0") desiredHeight: Int? = 0,
        @CapabilityParam(description = "Optional Chapter Cleaner result containing cleaned image paths") cleanChapterResult: ChapterCleanerResult? = null,
        @CapabilityParam(description = "Optional Chapter Vision segmentation result for polygon-based balloon text centering") chapterVisionResult: ChapterVisionResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterPSDBuildResult {
        val logger = context.logger
        logger.info("Starting buildPsdForChapter for $inputFolder")
        val progressReporter = context.progress

        val folder = File(inputFolder)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Input folder not found or is not a directory: $inputFolder")
        }

        val cleanDir = if (!cleanFolder.isNullOrBlank()) File(cleanFolder).takeIf { it.exists() && it.isDirectory } else null
        val cleanFilesFromChapterResult = cleanChapterResult?.cleanedImagePaths?.map { File(it) }?.filter { it.exists() } ?: emptyList()
        val hasCleanInput = cleanDir != null || cleanFilesFromChapterResult.isNotEmpty()

        val outDir = File(outputDir).apply { mkdirs() }

        val maxTexts = maxOf(texts.size, pageNames.size, balloonBoxes.size)
        if (maxTexts != texts.size || maxTexts != balloonBoxes.size || maxTexts != pageNames.size) {
            logger.warn("Mismatch in Add Text to Chapter inputs: texts (${texts.size}), balloonBoxes (${balloonBoxes.size}), pageNames (${pageNames.size}). Missing bounds will be defaulted.")
        }
        val safeTexts = List(maxTexts) { i -> if (i < texts.size) texts[i] else "" }
        val safePageNames = List(maxTexts) { i -> if (i < pageNames.size) pageNames[i] else "" }
        val safeBalloonBoxes = List(maxTexts) { i -> if (i < balloonBoxes.size) balloonBoxes[i] else emptyList() }
        val safeTextBoxes = textBoxes?.let { tList -> List(maxTexts) { i -> if (i < tList.size) tList[i] else emptyList() } }
        val safeTextAngles = textAngles?.let { rList -> List(maxTexts) { i -> if (i < rList.size) rList[i] else 0.0 } }
        val safeTextColors = textColors?.let { cList -> List(maxTexts) { i -> if (i < cList.size) cList[i] else "" } }
        val safeHasBorder = hasBorder?.let { bList -> List(maxTexts) { i -> if (i < bList.size) bList[i] else false } }
        val safeBorderColors = borderColors?.let { cList -> List(maxTexts) { i -> if (i < cList.size) cList[i] else "" } }

        val groupedData = (0 until maxTexts).groupBy { safePageNames[it] }

        val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")
        val allImages = folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.sortedBy { it.name } ?: emptyList()

        val processedPages = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = maxOf(1, minOf(2, Runtime.getRuntime().availableProcessors()))
        val semaphore = Semaphore(maxConcurrent)
        val psdPaths = mutableListOf<String>()

        data class ImageGroup(val files: List<File>, val mergedHeight: Int, val maxWidth: Int)

        coroutineScope {
            val imageDimensions = allImages.map { img ->
                async(Dispatchers.IO) {
                    var w = 0; var h = 0
                    val iter = ImageIO.getImageReadersBySuffix(img.extension)
                    if (iter.hasNext()) {
                        val reader = iter.next()
                        try {
                            ImageIO.createImageInputStream(img).use { stream ->
                                reader.input = stream
                                w = reader.getWidth(reader.minIndex)
                                h = reader.getHeight(reader.minIndex)
                            }
                        } catch(e: Exception) {
                            val imgBmp = ImageIO.read(img)
                            w = imgBmp?.width ?: 0
                            h = imgBmp?.height ?: 0
                            imgBmp?.flush()
                        } finally {
                            reader.dispose()
                        }
                    } else {
                        val imgBmp = ImageIO.read(img)
                        w = imgBmp?.width ?: 0
                        h = imgBmp?.height ?: 0
                        imgBmp?.flush()
                    }
                    img to Pair(w, h)
                }
            }.awaitAll().toMap()

            val imageGroups = mutableListOf<ImageGroup>()
            if (desiredHeight != null && desiredHeight > 0) {
                var currentGroup = mutableListOf<File>()
                var currentH = 0
                var currentMaxW = 0
                for (img in allImages) {
                    val dims = imageDimensions[img] ?: Pair(0, 0)
                    val w = dims.first
                    val h = dims.second
                    
                    if (currentGroup.isEmpty()) {
                        currentGroup.add(img)
                        currentH = h
                        currentMaxW = w
                    } else if (currentH + h > desiredHeight) {
                        imageGroups.add(ImageGroup(currentGroup, currentH, currentMaxW))
                        currentGroup = mutableListOf(img)
                        currentH = h
                        currentMaxW = w
                    } else {
                        currentGroup.add(img)
                        currentH += h
                        currentMaxW = maxOf(currentMaxW, w)
                    }
                }
                if (currentGroup.isNotEmpty()) {
                    imageGroups.add(ImageGroup(currentGroup, currentH, currentMaxW))
                }
            } else {
                imageGroups.addAll(allImages.map { 
                    val dims = imageDimensions[it] ?: Pair(0, 0)
                    ImageGroup(listOf(it), dims.second, dims.first) 
                })
            }

            val totalGroups = imageGroups.size

            val generatedPaths = imageGroups.mapIndexed { index, group ->
                async {
                    semaphore.withPermit {
                        val paddedIndex = (index + 1).toString().padStart(3, '0')
                        val outputPsdPath = File(outDir, "$paddedIndex.psd").absolutePath
                        logger.info("Processing group starting with: ${group.files.first().name} -> $paddedIndex.psd")

                        val pageTexts = mutableListOf<String>()
                        val pageBalloonBoxes = mutableListOf<List<Double>>()
                        val pageTextBoxes = mutableListOf<List<Double>>()
                        val pageTextAngles = mutableListOf<Double>()
                        val pageTextColors = mutableListOf<String>()
                        val pageHasBorder = mutableListOf<Boolean>()
                        val pageBorderColors = mutableListOf<String>()
                        val pageShapes = mutableListOf<String>()

                        var currentYOffset = 0
                        var mergedBmp: java.awt.image.BufferedImage? = null
                        var g2d: java.awt.Graphics2D? = null
                        var mergedCleanBmp: java.awt.image.BufferedImage? = null
                        var g2dClean: java.awt.Graphics2D? = null

                        if (desiredHeight != null && desiredHeight > 0 && group.files.size > 1) {
                            mergedBmp = java.awt.image.BufferedImage(group.maxWidth, group.mergedHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                            g2d = mergedBmp.createGraphics()
                            if (hasCleanInput) {
                                mergedCleanBmp = java.awt.image.BufferedImage(group.maxWidth, group.mergedHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                                g2dClean = mergedCleanBmp.createGraphics()
                            }
                        }

                        var singleCleanFilePath: String? = null

                        for (imageFile in group.files) {
                            val pageName = imageFile.name
                            val indices = groupedData[pageName] ?: emptyList()
                            val imgW = imageDimensions[imageFile]?.first ?: 0
                            val imgH = imageDimensions[imageFile]?.second ?: 0

                            if (mergedBmp != null && g2d != null) {
                                val bmp = withContext(Dispatchers.IO) { ImageIO.read(imageFile) }
                                if (bmp != null) {
                                    g2d.drawImage(bmp, 0, currentYOffset, null)
                                    bmp.flush()
                                }
                            }

                            val cleanMatch = cleanFilesFromChapterResult.firstOrNull { f ->
                                val cleanBase = f.nameWithoutExtension.replace("_patches", "").replace("_clean", "")
                                cleanBase.equals(imageFile.nameWithoutExtension, ignoreCase = true) ||
                                cleanBase.equals(imageFile.name, ignoreCase = true)
                            } ?: cleanDir?.listFiles { f ->
                                if (!f.isFile) return@listFiles false
                                val cleanBase = f.nameWithoutExtension.replace("_patches", "").replace("_clean", "")
                                cleanBase.equals(imageFile.nameWithoutExtension, ignoreCase = true) ||
                                cleanBase.equals(imageFile.name, ignoreCase = true)
                            }?.firstOrNull()

                            if (cleanMatch != null && cleanMatch.exists()) {
                                if (mergedCleanBmp != null && g2dClean != null) {
                                    val cBmp = withContext(Dispatchers.IO) { ImageIO.read(cleanMatch) }
                                    if (cBmp != null) {
                                        g2dClean.drawImage(cBmp, 0, currentYOffset, null)
                                        cBmp.flush()
                                    }
                                } else {
                                    singleCleanFilePath = cleanMatch.absolutePath
                                }
                            }

                            indices.forEach { idx ->
                                pageTexts.add(safeTexts[idx])
                                val bBox = safeBalloonBoxes[idx]
                                if (bBox.size >= 4) {
                                    val absYMin = (if (bBox[0] <= 1.0 && bBox[2] <= 1.0) bBox[0] * imgH else bBox[0]) + currentYOffset
                                    val absXMin = if (bBox[1] <= 1.0 && bBox[3] <= 1.0) bBox[1] * imgW else bBox[1]
                                    val absYMax = (if (bBox[2] <= 1.0 && bBox[0] <= 1.0) bBox[2] * imgH else bBox[2]) + currentYOffset
                                    val absXMax = if (bBox[3] <= 1.0 && bBox[1] <= 1.0) bBox[3] * imgW else bBox[3]
                                    val normYMin = if (mergedBmp != null) absYMin / mergedBmp.height else bBox[0]
                                    val normXMin = if (mergedBmp != null) absXMin / mergedBmp.width else bBox[1]
                                    val normYMax = if (mergedBmp != null) absYMax / mergedBmp.height else bBox[2]
                                    val normXMax = if (mergedBmp != null) absXMax / mergedBmp.width else bBox[3]
                                    pageBalloonBoxes.add(listOf(normYMin, normXMin, normYMax, normXMax))
                                } else {
                                    pageBalloonBoxes.add(bBox)
                                }

                                if (safeTextBoxes != null) {
                                    val tBox = safeTextBoxes[idx]
                                    if (tBox.size >= 4) {
                                        val absYMin = (if (tBox[0] <= 1.0 && tBox[2] <= 1.0) tBox[0] * imgH else tBox[0]) + currentYOffset
                                        val absXMin = if (tBox[1] <= 1.0 && tBox[3] <= 1.0) tBox[1] * imgW else tBox[1]
                                        val absYMax = (if (tBox[2] <= 1.0 && tBox[0] <= 1.0) tBox[2] * imgH else tBox[2]) + currentYOffset
                                        val absXMax = if (tBox[3] <= 1.0 && tBox[1] <= 1.0) tBox[3] * imgW else tBox[3]
                                        val normYMin = if (mergedBmp != null) absYMin / mergedBmp.height else tBox[0]
                                        val normXMin = if (mergedBmp != null) absXMin / mergedBmp.width else tBox[1]
                                        val normYMax = if (mergedBmp != null) absYMax / mergedBmp.height else tBox[2]
                                        val normXMax = if (mergedBmp != null) absXMax / mergedBmp.width else tBox[3]
                                        pageTextBoxes.add(listOf(normYMin, normXMin, normYMax, normXMax))
                                    } else {
                                        pageTextBoxes.add(tBox)
                                    }
                                }

                                if (safeTextAngles != null) pageTextAngles.add(safeTextAngles[idx])
                                if (safeTextColors != null) pageTextColors.add(safeTextColors[idx])
                                if (safeHasBorder != null) pageHasBorder.add(safeHasBorder[idx])
                                if (safeBorderColors != null) pageBorderColors.add(safeBorderColors[idx])
                                if (shapes != null) pageShapes.add(shapes[idx])
                            }
                            currentYOffset += imgH
                        }
                        g2d?.dispose()
                        g2dClean?.dispose()

                        val psdFontString = when (fontName) {
                            PsdFont.ARIAL -> "ArialMT"
                            else -> "AnimeAce2.0BB"
                        }

                        val fontSizes = List(pageTexts.size) { fontSize ?: 60 }
                        val fontNames = List(pageTexts.size) { psdFontString }
                        val borderSizes = List(pageTexts.size) { borderSize ?: 3 }

                        val resolvedChapterVisionResult = chapterVisionResult ?: cleanChapterResult?.chapterVisionResult
                        val pageVisionResult = resolvedChapterVisionResult?.results?.firstOrNull { r ->
                            val cleanBase = r.pageName.replace(".png", "").replace(".jpg", "").replace(".jpeg", "").replace(".webp", "")
                            cleanBase.equals(group.files.first().nameWithoutExtension, ignoreCase = true) ||
                            cleanBase.equals(group.files.first().name, ignoreCase = true) ||
                            r.pageName.equals(group.files.first().name, ignoreCase = true)
                        }

                        val psd = buildPsdObject(
                            imagePath = if (mergedBmp == null) group.files.first().absolutePath else null,
                            baseImageBmp = mergedBmp,
                            cleanImagePath = if (mergedCleanBmp == null) singleCleanFilePath else null,
                            cleanImageBmp = mergedCleanBmp,
                            texts = pageTexts,
                            balloonBoxes = pageBalloonBoxes,
                            textBoxes = if (safeTextBoxes != null) pageTextBoxes else null,
                            fontSizes = fontSizes,
                            fontNames = fontNames,
                            borderSizes = borderSizes,
                            context = context,
                            textAngles = if (safeTextAngles != null) pageTextAngles else null,
                            shapes = if (shapes != null) pageShapes else null,
                            textColors = if (safeTextColors != null) pageTextColors else null,
                            hasBorder = if (safeHasBorder != null) pageHasBorder else null,
                            borderColors = if (safeBorderColors != null) pageBorderColors else null,
                            visionResult = pageVisionResult
                        )
                        mergedBmp?.flush()
                        mergedCleanBmp?.flush()

                        val psdBytes = withContext(Dispatchers.Default) {
                            KPsd.write(psd, compress = false)
                        }
                        withContext(Dispatchers.IO) {
                            File(outputPsdPath).writeBytes(psdBytes)
                        }

                        val completed = processedPages.addAndGet(group.files.size)
                        progressReporter.report(completed.toFloat() / allImages.size.toFloat())

                        outputPsdPath
                    }
                }
            }.awaitAll()

            psdPaths.addAll(generatedPaths)
            logger.info("All ${allImages.size} images processed into $totalGroups PSDs successfully.")
        }

        return ChapterPSDBuildResult(psdPaths)
    }

    @Capability(
        name = "Build PSD for Chapter from Advanced OCR Data",
        description = "Generates layered PSDs natively for a folder of images concurrently using advanced OCR data"
    )
    suspend fun buildPsdForChapterFromAdvancedOcrData(
        @CapabilityInput(description = "Path to folder containing chapter images", semanticTypes = ["path/folder"])
        inputFolder: String,
        @CapabilityInput(
            description = "Optional path to folder containing cleaned images/patches",
            defaultValue = "",
            semanticTypes = ["path/folder"]
        )
        cleanFolder: String? = null,
        @CapabilityParam(description = "Advanced OCR Result") ocrData: AdvancedOCRResult,
        @CapabilityOutput(
            description = "Directory to save generated PSDs",
            autogeneratedPattern = "{inputFolder}/build_psd_output/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "60") fontSize: Int? = 60,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Desired height of the final PSD. 0 to disable merging", defaultValue = "0") desiredHeight: Int? = 0,
        @CapabilityParam(description = "Optional Chapter Cleaner result containing cleaned image paths") cleanChapterResult: ChapterCleanerResult? = null,
        @CapabilityParam(description = "Optional Chapter Vision segmentation result for polygon-based balloon text centering") chapterVisionResult: ChapterVisionResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterPSDBuildResult {
        val resolvedChapterVision = chapterVisionResult ?: cleanChapterResult?.chapterVisionResult
        return buildPsdForChapter(
            inputFolder = inputFolder,
            texts = ocrData.texts,
            balloonBoxes = ocrData.balloonBoxes,
            textBoxes = ocrData.textBoxes,
            pageNames = ocrData.pageNames,
            outputDir = outputDir,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = borderSize,
            leaveIntermediateFiles = leaveIntermediateFiles,
            textAngles = ocrData.textAngles,
            textColors = ocrData.textColors,
            hasBorder = ocrData.hasBorder,
            borderColors = ocrData.borderColors,
            shapes = ocrData.shapes,
            desiredHeight = desiredHeight,
            cleanFolder = cleanFolder,
            cleanChapterResult = cleanChapterResult,
            chapterVisionResult = resolvedChapterVision,
            context = context,
            hostFs = hostFs
        )
    }

    @Capability(
        name = "Build PSD for Chapter from OCR Data",
        description = "Generates layered PSDs natively for a folder of images concurrently using basic OCR data"
    )
    suspend fun buildPsdForChapterFromOcrData(
        @CapabilityInput(description = "Path to folder containing chapter images", semanticTypes = ["path/folder"])
        inputFolder: String,
        @CapabilityInput(
            description = "Optional path to folder containing cleaned images/patches",
            defaultValue = "",
            semanticTypes = ["path/folder"]
        )
        cleanFolder: String? = null,
        @CapabilityParam(description = "OCR Result") ocrData: OCRResult,
        @CapabilityOutput(
            description = "Directory to save generated PSDs",
            autogeneratedPattern = "{inputFolder}/build_psd_output/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "60") fontSize: Int? = 60,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "0") borderSize: Int? = 0,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Desired height of the final PSD. 0 to disable merging", defaultValue = "0") desiredHeight: Int? = 0,
        @CapabilityParam(description = "Optional Chapter Cleaner result containing cleaned image paths") cleanChapterResult: ChapterCleanerResult? = null,
        @CapabilityParam(description = "Optional Chapter Vision segmentation result for polygon-based balloon text centering") chapterVisionResult: ChapterVisionResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterPSDBuildResult {
        val safeBorderSize = borderSize ?: 0
        val safeHasBorder = if (safeBorderSize > 0) null else List(ocrData.texts.size) { false }
        val resolvedChapterVision = chapterVisionResult ?: cleanChapterResult?.chapterVisionResult
        return buildPsdForChapter(
            inputFolder = inputFolder,
            texts = ocrData.texts,
            balloonBoxes = ocrData.bb,
            pageNames = ocrData.pageNames,
            outputDir = outputDir,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = safeBorderSize,
            hasBorder = safeHasBorder,
            leaveIntermediateFiles = leaveIntermediateFiles,
            desiredHeight = desiredHeight,
            cleanFolder = cleanFolder,
            cleanChapterResult = cleanChapterResult,
            chapterVisionResult = resolvedChapterVision,
            context = context,
            hostFs = hostFs
        )
    }

    val json = Json { ignoreUnknownKeys = true }

    @Capability(
        name = "Build PSD from JSON",
        description = "Generates a layered PSD natively from a given JSON file"
    )
    suspend fun buildPsdFromJson(
        @CapabilityInput(description = "Path to JSON file", semanticTypes = ["path/file"])
        jsonPath: String,
        @CapabilityOutput(
            description = "Output PSD path",
            autogeneratedPattern = "{jsonPath}/build_psd_output/",
            semanticTypes = ["path/file"]
        )
        outputPsdPath: String,
        @CapabilityInput(description = "Path to base image (optional, default looks in JSON)", defaultValue = "\"\"", semanticTypes = ["path/file"])
        baseImagePath: String? = "",
        @CapabilityInput(
            description = "Optional path to clean/inpainted image or patch layer",
            defaultValue = "",
            semanticTypes = ["path/file"]
        )
        cleanImagePath: String? = null,
        @CapabilityParam(description = "Optional Cleaner result containing the cleaned image path") cleanResult: CleanerResult? = null,
        context: PluginContext,
        hostFs: HostFileSystem
    ): PSDBuildResult {
        val jsonFile = File(jsonPath)
        if (!jsonFile.exists()) {
            throw IllegalArgumentException("JSON file not found: $jsonPath")
        }
        val jsonContent = withContext(Dispatchers.IO) { jsonFile.readText() }
        val payload = json.decodeFromString<PsdPayload>(jsonContent)

        val imagePathToUse = if (!baseImagePath.isNullOrBlank()) baseImagePath else payload.backgroundImage
        if (imagePathToUse.isNullOrBlank()) {
            throw IllegalArgumentException("Background image path not provided in JSON or as parameter")
        }

        val originalBaseImage = withContext(Dispatchers.IO) { ImageIO.read(File(imagePathToUse)) }
            ?: throw IllegalArgumentException("Failed to read image: $imagePathToUse")
        val baseImage = ensureFastImage(originalBaseImage)
        if (baseImage !== originalBaseImage) {
            originalBaseImage.flush()
        }
        val width = baseImage.width.toDouble()
        val height = baseImage.height.toDouble()

        val activeTexts = payload.balloons.ifEmpty { payload.texts }
        val texts = activeTexts.map { it.text }

        val balloonBoxes = activeTexts.map {
            val box = it.balloon_box_2d
            if (box != null && box.size >= 4) {
                // Convert 0-1000 coordinates to 0.0-1.0 normalized coordinates
                val b0 = if (box[0] > 1.0) box[0] / 1000.0 else box[0]
                val b1 = if (box[1] > 1.0) box[1] / 1000.0 else box[1]
                val b2 = if (box[2] > 1.0) box[2] / 1000.0 else box[2]
                val b3 = if (box[3] > 1.0) box[3] / 1000.0 else box[3]
                listOf(b0, b1, b2, b3)
            } else if (it.top != null && it.left != null && it.bottom != null && it.right != null) {
                listOf(it.top.toDouble() / height, it.left.toDouble() / width, it.bottom.toDouble() / height, it.right.toDouble() / width)
            } else emptyList()
        }
        val textBoxes = activeTexts.map {
            val box = it.text_box_2d
            if (box != null && box.size >= 4) {
                val b0 = if (box[0] > 1.0) box[0] / 1000.0 else box[0]
                val b1 = if (box[1] > 1.0) box[1] / 1000.0 else box[1]
                val b2 = if (box[2] > 1.0) box[2] / 1000.0 else box[2]
                val b3 = if (box[3] > 1.0) box[3] / 1000.0 else box[3]
                listOf(b0, b1, b2, b3)
            } else emptyList()
        }

        val fontSizes = activeTexts.map { it.fontSize ?: 60 }
        val fontNames = activeTexts.map { it.fontFamily ?: it.fontName ?: "AnimeAce2.0BB" }
        val borderSizes = activeTexts.map { it.strokeSize ?: 3 }
        val textAngles = activeTexts.map { it.textAngle ?: it.rotation ?: 0.0 }
        val shapes = activeTexts.map { it.shape ?: "oval" }
        val textColors = activeTexts.map {
            it.textColor ?: if (it.color != null) String.format("#%02x%02x%02x", it.color.r, it.color.g, it.color.b) else "#000000"
        }
        val hasBorderList = activeTexts.map { it.hasBorder ?: (it.strokeSize != null && it.strokeSize > 0) }
        val borderColors = activeTexts.map { it.borderColor ?: "#FFFFFF" }

        val resolvedCleanPath = cleanImagePath?.ifBlank { null } ?: cleanResult?.cleanedImagePath

        val psd = buildPsdObject(
            imagePath = imagePathToUse,
            baseImageBmp = baseImage,
            cleanImagePath = resolvedCleanPath,
            texts = texts,
            balloonBoxes = balloonBoxes,
            textBoxes = textBoxes,
            fontSizes = fontSizes,
            fontNames = fontNames,
            borderSizes = borderSizes,
            context = context,
            textAngles = textAngles,
            shapes = shapes,
            textColors = textColors,
            hasBorder = hasBorderList,
            borderColors = borderColors
        )
        baseImage.flush()
        val psdBytes = withContext(Dispatchers.Default) {
            KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
        }
        return PSDBuildResult(outputPsdPath)
    }

    private fun ensureFastImage(image: java.awt.image.BufferedImage): java.awt.image.BufferedImage {
        if (image.type == java.awt.image.BufferedImage.TYPE_INT_RGB || image.type == java.awt.image.BufferedImage.TYPE_INT_ARGB) {
            return image
        }
        val newImage = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = newImage.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return newImage
    }

    private fun bufferedImageToPixelData(image: java.awt.image.BufferedImage): PixelData {
        val width = image.width
        val height = image.height
        val totalPixels = width * height
        val bytes = ByteArray(totalPixels * 4 + 64) // Pad with 64 extra bytes to prevent RLE lookahead crashes in KPsd

        val raster = image.raster
        val dataBuffer = raster.dataBuffer
        if (dataBuffer is java.awt.image.DataBufferInt && (image.type == java.awt.image.BufferedImage.TYPE_INT_ARGB || image.type == java.awt.image.BufferedImage.TYPE_INT_RGB)) {
            val intData = dataBuffer.data
            val isRgb = image.type == java.awt.image.BufferedImage.TYPE_INT_RGB
            for (i in 0 until totalPixels) {
                val argb = intData[i]
                val a = if (isRgb) 255 else ((argb shr 24) and 0xff)
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff
                val base = i * 4
                bytes[base] = r.toByte()
                bytes[base + 1] = g.toByte()
                bytes[base + 2] = b.toByte()
                bytes[base + 3] = a.toByte()
            }
        } else {
            val rowBuffer = IntArray(width)
            var base = 0
            for (y in 0 until height) {
                image.getRGB(0, y, width, 1, rowBuffer, 0, width)
                for (x in 0 until width) {
                    val argb = rowBuffer[x]
                    bytes[base] = ((argb shr 16) and 0xff).toByte()
                    bytes[base + 1] = ((argb shr 8) and 0xff).toByte()
                    bytes[base + 2] = (argb and 0xff).toByte()
                    bytes[base + 3] = ((argb shr 24) and 0xff).toByte()
                    base += 4
                }
            }
        }
        return PixelData(width, height, bytes)
    }

    private fun drawCleanerDebug(
        g2dCleaner: java.awt.Graphics2D,
        visionResult: VisionResult,
        width: Int,
        height: Int
    ) {
        for (obj in visionResult.objects) {
            val label = obj.label.trim().lowercase()
            val sYmin = (obj.box.ymin * height).toInt()
            val sXmin = (obj.box.xmin * width).toInt()
            val sYmax = (obj.box.ymax * height).toInt()
            val sXmax = (obj.box.xmax * width).toInt()
            val sW = maxOf(1, sXmax - sXmin)
            val sH = maxOf(1, sYmax - sYmin)

            val color = when {
                label.contains("balloon") -> java.awt.Color(0, 200, 255)
                label == "text" -> java.awt.Color(50, 255, 50)
                label == "watermark" -> java.awt.Color(255, 0, 255)
                else -> java.awt.Color.YELLOW
            }

            g2dCleaner.color = color
            g2dCleaner.stroke = java.awt.BasicStroke(2f)
            g2dCleaner.drawRect(sXmin, sYmin, sW, sH)

            if (obj.polygon.size >= 3) {
                val xPoints = obj.polygon.map { (it.x * width).toInt() }.toIntArray()
                val yPoints = obj.polygon.map { (it.y * height).toInt() }.toIntArray()
                g2dCleaner.stroke = java.awt.BasicStroke(1.5f)
                g2dCleaner.drawPolygon(xPoints, yPoints, obj.polygon.size)
            }

            val tag = "${obj.label} (${(obj.confidence * 100).toInt()}%)"
            g2dCleaner.color = color
            g2dCleaner.drawString(tag, sXmin + 4, maxOf(12, sYmin - 4))
        }
    }

    private fun drawOcrDebug(
        g2dOcr: java.awt.Graphics2D,
        boxDataList: List<BoxData>,
        effShapes: List<String?>
    ) {
        for ((index, box) in boxDataList.withIndex()) {
            if (box.bBox.size >= 4) {
                g2dOcr.color = java.awt.Color.MAGENTA
                g2dOcr.stroke = java.awt.BasicStroke(2f)
                g2dOcr.drawRect(
                    box.bBox[1].toInt(),
                    box.bBox[0].toInt(),
                    (box.bBox[3] - box.bBox[1]).toInt(),
                    (box.bBox[2] - box.bBox[0]).toInt()
                )
            }
            if (box.tBox.size >= 4) {
                g2dOcr.color = java.awt.Color.GREEN
                g2dOcr.stroke = java.awt.BasicStroke(3f)
                g2dOcr.drawRect(
                    box.tBox[1].toInt(),
                    box.tBox[0].toInt(),
                    (box.tBox[3] - box.tBox[1]).toInt(),
                    (box.tBox[2] - box.tBox[0]).toInt()
                )
                g2dOcr.color = java.awt.Color.RED
                g2dOcr.drawLine((box.cx - 10).toInt(), box.cy.toInt(), (box.cx + 10).toInt(), box.cy.toInt())
                g2dOcr.drawLine(box.cx.toInt(), (box.cy - 10).toInt(), box.cx.toInt(), (box.cy + 10).toInt())
            }
            g2dOcr.color = java.awt.Color.ORANGE
            g2dOcr.stroke = java.awt.BasicStroke(4f)
            g2dOcr.drawRect(box.ibx0.toInt(), box.iby0.toInt(), (box.ibx1 - box.ibx0).toInt(), (box.iby1 - box.iby0).toInt())

            g2dOcr.color = java.awt.Color.BLUE
            val shape = effShapes.getOrNull(index) ?: "oval"
            if (shape.equals("rectangular", ignoreCase = true)) {
                g2dOcr.drawRect(box.ibx0.toInt(), box.iby0.toInt(), (box.ibx1 - box.ibx0).toInt(), (box.iby1 - box.iby0).toInt())
            } else {
                g2dOcr.drawOval(box.ibx0.toInt(), box.iby0.toInt(), (box.ibx1 - box.ibx0).toInt(), (box.iby1 - box.iby0).toInt())
            }
        }
    }

    private fun drawBoundaryDebug(
        g2dBoundary: java.awt.Graphics2D,
        effTexts: List<String>,
        boxDataList: List<BoxData>,
        matchedResults: List<MatchedBalloonText>?,
        customBoundaries: List<com.wip.kpsd.TextBoundary>?,
        effShapes: List<String?>,
        paddingPercentage: Float
    ) {
        for ((index, _) in effTexts.withIndex()) {
            val box = boxDataList[index]
            val matched = matchedResults?.getOrNull(index)

            val ibTop = box.iby0.toInt()
            val ibLeft = box.ibx0.toInt()
            val ibBottom = box.iby1.toInt()
            val ibRight = box.ibx1.toInt()
            val boxWidth = maxOf(1, ibRight - ibLeft)
            val boxHeight = maxOf(1, ibBottom - ibTop)

            val bPadding = minOf(boxWidth, boxHeight) * paddingPercentage
            var boundaryShape = customBoundaries?.getOrNull(index)
            if (boundaryShape == null && matched?.polygonPixels != null) {
                boundaryShape = PolygonTextBoundary(
                    polygon = matched.polygonPixels,
                    padding = bPadding,
                    visualCenter = matched.visualCenter
                )
            }
            if (boundaryShape == null) {
                val shape = effShapes.getOrNull(index) ?: "oval"
                boundaryShape = if (shape.equals("rectangular", ignoreCase = true)) {
                    com.wip.kpsd.RectangleBoundary(padding = bPadding)
                } else {
                    com.wip.kpsd.EllipseBoundary(padding = bPadding)
                }
            }

            when (boundaryShape) {
                is PolygonTextBoundary -> {
                    val poly = boundaryShape.polygon
                    if (poly.size >= 3) {
                        val xPts = poly.map { it.x.toInt() }.toIntArray()
                        val yPts = poly.map { it.y.toInt() }.toIntArray()
                        g2dBoundary.color = java.awt.Color(255, 0, 255)
                        g2dBoundary.stroke = java.awt.BasicStroke(2.5f)
                        g2dBoundary.drawPolygon(xPts, yPts, poly.size)

                        val vcx = (boundaryShape.visualCenter?.x ?: box.cx).toInt()
                        val vcy = (boundaryShape.visualCenter?.y ?: box.cy).toInt()
                        g2dBoundary.color = java.awt.Color.RED
                        g2dBoundary.stroke = java.awt.BasicStroke(2f)
                        g2dBoundary.drawLine(vcx - 12, vcy, vcx + 12, vcy)
                        g2dBoundary.drawLine(vcx, vcy - 12, vcx, vcy + 12)

                        g2dBoundary.color = java.awt.Color(255, 100, 255, 180)
                        g2dBoundary.stroke = java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
                        g2dBoundary.drawRect(ibLeft, ibTop, boxWidth, boxHeight)

                        g2dBoundary.color = java.awt.Color(255, 0, 255)
                        g2dBoundary.drawString("Boundary #${index + 1}: Polygon (${poly.size} pts, pad=${bPadding.toInt()}px)", ibLeft + 4, maxOf(12, ibTop - 4))
                    }
                }
                is com.wip.kpsd.EllipseBoundary -> {
                    val pad = bPadding.toInt()
                    g2dBoundary.color = java.awt.Color(0, 229, 255)
                    g2dBoundary.stroke = java.awt.BasicStroke(2.5f)
                    g2dBoundary.drawOval(ibLeft + pad, ibTop + pad, maxOf(1, boxWidth - 2 * pad), maxOf(1, boxHeight - 2 * pad))

                    g2dBoundary.color = java.awt.Color(0, 229, 255, 150)
                    g2dBoundary.stroke = java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
                    g2dBoundary.drawRect(ibLeft, ibTop, boxWidth, boxHeight)

                    val cx = box.cx.toInt()
                    val cy = box.cy.toInt()
                    g2dBoundary.color = java.awt.Color.RED
                    g2dBoundary.stroke = java.awt.BasicStroke(2f)
                    g2dBoundary.drawLine(cx - 10, cy, cx + 10, cy)
                    g2dBoundary.drawLine(cx, cy - 10, cx, cy + 10)

                    g2dBoundary.color = java.awt.Color(0, 229, 255)
                    g2dBoundary.drawString("Boundary #${index + 1}: Ellipse (pad=${bPadding.toInt()}px)", ibLeft + 4, maxOf(12, ibTop - 4))
                }
                is com.wip.kpsd.RectangleBoundary -> {
                    val pad = bPadding.toInt()
                    g2dBoundary.color = java.awt.Color(0, 255, 102)
                    g2dBoundary.stroke = java.awt.BasicStroke(2.5f)
                    g2dBoundary.drawRect(ibLeft + pad, ibTop + pad, maxOf(1, boxWidth - 2 * pad), maxOf(1, boxHeight - 2 * pad))

                    val cx = box.cx.toInt()
                    val cy = box.cy.toInt()
                    g2dBoundary.color = java.awt.Color.RED
                    g2dBoundary.stroke = java.awt.BasicStroke(2f)
                    g2dBoundary.drawLine(cx - 10, cy, cx + 10, cy)
                    g2dBoundary.drawLine(cx, cy - 10, cx, cy + 10)

                    g2dBoundary.color = java.awt.Color(0, 255, 102)
                    g2dBoundary.drawString("Boundary #${index + 1}: Rectangle (pad=${bPadding.toInt()}px)", ibLeft + 4, maxOf(12, ibTop - 4))
                }
            }
        }
    }

    private fun normalizeBoundingBox(box: List<Double>): List<Double> {
        val result = if (box.size >= 4) box.take(4)
        else if (box.size == 3) {
            val w = kotlin.math.abs(box[2] - box[0])
            listOf(box[0], box[1], box[2], box[1] + w)
        } else listOf(0.4, 0.4, 0.6, 0.6)

        return result
    }

    data class BoxData(
        val ibx0: Double, val iby0: Double, val ibx1: Double, val iby1: Double,
        val cx: Double, val cy: Double,
        val bBox: DoubleArray,
        val tBox: DoubleArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BoxData

            if (ibx0 != other.ibx0) return false
            if (iby0 != other.iby0) return false
            if (ibx1 != other.ibx1) return false
            if (iby1 != other.iby1) return false
            if (cx != other.cx) return false
            if (cy != other.cy) return false
            if (!bBox.contentEquals(other.bBox)) return false
            if (!tBox.contentEquals(other.tBox)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = ibx0.hashCode()
            result = 31 * result + iby0.hashCode()
            result = 31 * result + ibx1.hashCode()
            result = 31 * result + iby1.hashCode()
            result = 31 * result + cx.hashCode()
            result = 31 * result + cy.hashCode()
            result = 31 * result + bBox.contentHashCode()
            result = 31 * result + tBox.contentHashCode()
            return result
        }
    }

    suspend fun buildPsdObject(
        imagePath: String? = null,
        baseImageBmp: java.awt.image.BufferedImage? = null,
        cleanImagePath: String? = null,
        cleanImageBmp: java.awt.image.BufferedImage? = null,
        texts: List<String>,
        balloonBoxes: List<List<Double>>,
        textBoxes: List<List<Double>>? = null,
        fontSizes: List<Int?>? = null,
        fontNames: List<String?>? = null,
        borderSizes: List<Int?>? = null,
        context: PluginContext,
        textAngles: List<Double?>? = null,
        shapes: List<String?>? = null,
        textColors: List<String?>? = null,
        hasBorder: List<Boolean?>? = null,
        borderColors: List<String?>? = null,
        customBoundaries: List<com.wip.kpsd.TextBoundary>? = null,
        visionResult: VisionResult? = null
    ): Psd {
        val originalBaseImage = if (baseImageBmp != null) {
            baseImageBmp
        } else {
            val inputFile = File(imagePath!!)
            withContext(Dispatchers.IO) { ImageIO.read(inputFile) }
                ?: throw IllegalArgumentException("Failed to read image: $imagePath")
        }
        val baseImage = ensureFastImage(originalBaseImage)
        if (baseImage !== originalBaseImage && baseImageBmp == null) {
            originalBaseImage.flush()
        }

        val width = baseImage.width
        val height = baseImage.height

        val bgPixelData = bufferedImageToPixelData(baseImage)

        val cleanBmp = if (cleanImageBmp != null) {
            cleanImageBmp
        } else if (!cleanImagePath.isNullOrBlank()) {
            val cleanFile = File(cleanImagePath)
            if (cleanFile.exists()) {
                withContext(Dispatchers.IO) { ImageIO.read(cleanFile) }
            } else {
                null
            }
        } else {
            null
        }

        val cleanPixelData: PixelData = if (cleanBmp != null) {
            val cleanFast = ensureFastImage(cleanBmp)
            val cleanFastResized = if (cleanFast.width != width || cleanFast.height != height) {
                val resized = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val gClean = resized.createGraphics()
                gClean.drawImage(cleanFast, 0, 0, width, height, null)
                gClean.dispose()
                if (cleanFast !== cleanBmp && cleanImageBmp == null) cleanFast.flush()
                resized
            } else {
                cleanFast
            }
            val pData = bufferedImageToPixelData(cleanFastResized)
            if (cleanFastResized !== cleanBmp && cleanImageBmp == null) {
                cleanFastResized.flush()
            }
            if (cleanImageBmp == null) {
                cleanBmp.flush()
            }
            pData
        } else {
            PixelData(width, height, bgPixelData.data.copyOf())
        }

        val effectiveVisionResult = visionResult

        data class ProcessedTextData(
            val texts: List<String>,
            val balloonBoxes: List<List<Double>>,
            val textBoxes: List<List<Double>>?,
            val fontSizes: List<Int?>,
            val fontNames: List<String?>,
            val borderSizes: List<Int?>,
            val textAngles: List<Double?>,
            val shapes: List<String?>,
            val textColors: List<String?>,
            val hasBorder: List<Boolean?>,
            val borderColors: List<String?>
        )

        val processedData: ProcessedTextData = if (effectiveVisionResult != null && effectiveVisionResult.objects.isNotEmpty() && texts.size > 1) {
            val tempAdv = AdvancedOCRResult(
                texts = texts,
                balloonBoxes = balloonBoxes,
                textBoxes = textBoxes ?: balloonBoxes,
                shapes = shapes?.map { it ?: "oval" } ?: List(texts.size) { "oval" },
                fontStyles = List(texts.size) { "normal" },
                fontFamilies = fontNames?.map { it ?: "AnimeAce2.0BB" } ?: List(texts.size) { "AnimeAce2.0BB" },
                textAngles = textAngles?.map { it ?: 0.0 } ?: List(texts.size) { 0.0 },
                isSparse = List(texts.size) { false },
                textColors = textColors?.map { it ?: "#000000" } ?: List(texts.size) { "#000000" },
                hasBorder = hasBorder?.map { it ?: false } ?: List(texts.size) { false },
                borderColors = borderColors?.map { it ?: "#FFFFFF" } ?: List(texts.size) { "#FFFFFF" },
                pageNumbers = List(texts.size) { 1 },
                pageNames = List(texts.size) { "" },
                failedFiles = emptyList()
            )
            val mergedAdv = com.wip.common.models.OcrVisionMerger.mergeAdvancedOcrResult(tempAdv, effectiveVisionResult, separator = " ")
            val fSizes = List(mergedAdv.texts.size) { fontSizes?.firstOrNull() ?: 60 }
            val bSizes = List(mergedAdv.texts.size) { borderSizes?.firstOrNull() ?: 0 }
            ProcessedTextData(
                texts = mergedAdv.texts,
                balloonBoxes = mergedAdv.balloonBoxes,
                textBoxes = mergedAdv.textBoxes,
                fontSizes = fSizes,
                fontNames = mergedAdv.fontFamilies,
                borderSizes = bSizes,
                textAngles = mergedAdv.textAngles,
                shapes = mergedAdv.shapes,
                textColors = mergedAdv.textColors,
                hasBorder = mergedAdv.hasBorder,
                borderColors = mergedAdv.borderColors
            )
        } else {
            ProcessedTextData(
                texts = texts,
                balloonBoxes = balloonBoxes,
                textBoxes = textBoxes,
                fontSizes = fontSizes ?: List(texts.size) { 60 },
                fontNames = fontNames ?: List(texts.size) { "AnimeAce2.0BB" },
                borderSizes = borderSizes ?: List(texts.size) { 0 },
                textAngles = textAngles ?: List(texts.size) { 0.0 },
                shapes = shapes ?: List(texts.size) { "oval" },
                textColors = textColors ?: List(texts.size) { "#000000" },
                hasBorder = hasBorder ?: List(texts.size) { false },
                borderColors = borderColors ?: List(texts.size) { "#FFFFFF" }
            )
        }

        val effTexts = processedData.texts
        val effBalloonBoxes = processedData.balloonBoxes
        val effTextBoxes = processedData.textBoxes
        val effFontSizes = processedData.fontSizes
        val effFontNames = processedData.fontNames
        val effBorderSizes = processedData.borderSizes
        val effTextAngles = processedData.textAngles
        val effShapes = processedData.shapes
        val effTextColors = processedData.textColors
        val effHasBorder = processedData.hasBorder
        val effBorderColors = processedData.borderColors

        val matchedResults = if (effectiveVisionResult != null && effectiveVisionResult.objects.isNotEmpty()) {
            VisionOcrMatcher.match(
                texts = effTexts,
                ocrBoxes = if (effTextBoxes != null && effTextBoxes.isNotEmpty()) effTextBoxes else effBalloonBoxes,
                ocrBalloonBoxes = effBalloonBoxes,
                visionObjects = effectiveVisionResult.objects,
                imageWidth = width.toDouble(),
                imageHeight = height.toDouble()
            )
        } else {
            null
        }

        val boxDataList = mutableListOf<BoxData>()
        for ((index, text) in effTexts.withIndex()) {
            val balloonBox = if (index < effBalloonBoxes.size) effBalloonBoxes[index] else emptyList()
            val textBox = effTextBoxes?.getOrNull(index) ?: emptyList()

            fun toAbs(b: List<Double>): DoubleArray {
                if (b.size < 4) return doubleArrayOf()
                val ymin = if (b[0] in 0.0..1.0 && b[2] in 0.0..1.0) b[0] * height else b[0]
                val xmin = if (b[1] in 0.0..1.0 && b[3] in 0.0..1.0) b[1] * width else b[1]
                val ymax = if (b[2] in 0.0..1.0 && b[0] in 0.0..1.0) b[2] * height else b[2]
                val xmax = if (b[3] in 0.0..1.0 && b[1] in 0.0..1.0) b[3] * width else b[3]
                return doubleArrayOf(ymin, xmin, ymax, xmax)
            }

            val bBox = toAbs(balloonBox)
            val tBox = toAbs(textBox)

            val ibx0: Double
            val iby0: Double
            val ibx1: Double
            val iby1: Double
            val cx: Double
            val cy: Double

            val matched = matchedResults?.getOrNull(index)
            if (matched?.matchedBalloon != null && matched.polygonBounds != null && matched.visualCenter != null) {
                // 1. Rely strictly on cleaner / vision segmentation boxes when available
                val segBounds = matched.polygonBounds
                ibx0 = segBounds.left.toDouble()
                iby0 = segBounds.top.toDouble()
                ibx1 = segBounds.right.toDouble()
                iby1 = segBounds.bottom.toDouble()
                cx = matched.visualCenter.x
                cy = matched.visualCenter.y
            } else if (bBox.size >= 4) {
                // 2. Drop to OCR balloon boxes when cleaner boxes are not available
                ibx0 = bBox[1]
                iby0 = bBox[0]
                ibx1 = bBox[3]
                iby1 = bBox[2]
                cx = (ibx0 + ibx1) / 2.0
                cy = (iby0 + iby1) / 2.0
            } else if (tBox.size >= 4) {
                // 3. Drop to OCR text boxes when neither cleaner nor OCR balloon box is available
                ibx0 = tBox[1]
                iby0 = tBox[0]
                ibx1 = tBox[3]
                iby1 = tBox[2]
                cx = (ibx0 + ibx1) / 2.0
                cy = (iby0 + iby1) / 2.0
            } else {
                // 4. Default fallback
                ibx0 = 0.4 * width
                iby0 = 0.4 * height
                ibx1 = 0.6 * width
                iby1 = 0.6 * height
                cx = (ibx0 + ibx1) / 2.0
                cy = (iby0 + iby1) / 2.0
            }

            boxDataList.add(BoxData(ibx0, iby0, ibx1, iby1, cx, cy, bBox, tBox))
        }

        var ocrDebugPixelData: PixelData? = null
        var cleanerDebugPixelData: PixelData? = null
        var boundaryDebugPixelData: PixelData? = null

        if (settings.debugMode) {
            // 1. OCR Debug layer
            val ocrDebugImg = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2dOcr = ocrDebugImg.createGraphics()
            g2dOcr.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            drawOcrDebug(g2dOcr, boxDataList, effShapes)
            g2dOcr.dispose()
            ocrDebugPixelData = bufferedImageToPixelData(ocrDebugImg)
            ocrDebugImg.flush()

            // 2. Cleaner / Segmentation Debug layer
            if (effectiveVisionResult != null && effectiveVisionResult.objects.isNotEmpty()) {
                val cleanerDebugImg = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g2dCleaner = cleanerDebugImg.createGraphics()
                g2dCleaner.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                drawCleanerDebug(g2dCleaner, effectiveVisionResult, width, height)
                g2dCleaner.dispose()
                cleanerDebugPixelData = bufferedImageToPixelData(cleanerDebugImg)
                cleanerDebugImg.flush()
            }

            // 3. Boundary Shapes Debug layer
            val boundaryDebugImg = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2dBoundary = boundaryDebugImg.createGraphics()
            g2dBoundary.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            drawBoundaryDebug(g2dBoundary, effTexts, boxDataList, matchedResults, customBoundaries, effShapes, settings.paddingPercentage)
            g2dBoundary.dispose()
            boundaryDebugPixelData = bufferedImageToPixelData(boundaryDebugImg)
            boundaryDebugImg.flush()

            // 4. Combined preview image written to disk
            val debugImgFile = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2dFile = debugImgFile.createGraphics()
            g2dFile.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2dFile.drawImage(baseImage, 0, 0, null)
            if (effectiveVisionResult != null && effectiveVisionResult.objects.isNotEmpty()) {
                drawCleanerDebug(g2dFile, effectiveVisionResult, width, height)
            }
            drawOcrDebug(g2dFile, boxDataList, effShapes)
            drawBoundaryDebug(g2dFile, effTexts, boxDataList, matchedResults, customBoundaries, effShapes, settings.paddingPercentage)
            g2dFile.dispose()

            val outDir = File("build/tmp")
            if (!outDir.exists()) outDir.mkdirs()
            val baseName = imagePath?.let { File(it).nameWithoutExtension } ?: "merged_psd"
            withContext(Dispatchers.IO) {
                try {
                    ImageIO.write(debugImgFile, "png", File(outDir, "${baseName}_psd_builder.png"))
                } catch (e: Exception) {
                    context.logger.warn("Failed to write debug image: ${e.message}")
                } finally {
                    debugImgFile.flush()
                }
            }
        }

        return psd(width = width, height = height) {
            imageData = bgPixelData

            // 1. Raw layer at the base (untouched original art)
            layer(name = "raw") {
                top = 0
                left = 0
                bottom = height
                right = width
                imageData = bgPixelData
            }

            // 2. Clean folder containing the clean image / inpainting patches
            group(name = "clean") {
                layer(name = "clean_image") {
                    top = 0
                    left = 0
                    bottom = height
                    right = width
                    imageData = cleanPixelData
                }
            }

            // 3. Debug folder if enabled
            if (ocrDebugPixelData != null || cleanerDebugPixelData != null || boundaryDebugPixelData != null) {
                group(name = "debug_boxes") {
                    if (cleanerDebugPixelData != null) {
                        layer(name = "cleaner_boxes") {
                            hidden = true
                            top = 0
                            left = 0
                            bottom = height
                            right = width
                            imageData = cleanerDebugPixelData
                        }
                    }
                    if (ocrDebugPixelData != null) {
                        layer(name = "ocr_boxes") {
                            hidden = true
                            top = 0
                            left = 0
                            bottom = height
                            right = width
                            imageData = ocrDebugPixelData
                        }
                    }
                    if (boundaryDebugPixelData != null) {
                        layer(name = "boundary_shapes") {
                            hidden = true
                            top = 0
                            left = 0
                            bottom = height
                            right = width
                            imageData = boundaryDebugPixelData
                        }
                    }
                }
            }

            // 4. Translation folder containing all formatted text layers
            if (effTexts.isNotEmpty()) {
                group(name = "translation") {
                    for ((index, text) in effTexts.withIndex()) {
                        val box = boxDataList[index]
                        val matched = matchedResults?.getOrNull(index)

                        val ibTop = box.iby0.toInt()
                        val ibLeft = box.ibx0.toInt()
                        val ibBottom = box.iby1.toInt()
                        val ibRight = box.ibx1.toInt()

                        val boxWidth = maxOf(1, ibRight - ibLeft)
                        val boxHeight = maxOf(1, ibBottom - ibTop)

                        val initialFontSize = effFontSizes.getOrNull(index) ?: 60
                        val fNameInput = effFontNames.getOrNull(index) ?: "AnimeAce2.0BB"
                        val fName = if (fNameInput == "ArialMT" || fNameInput == "Arial") "ArialMT" else "AnimeAce2.0BB"

                        val borderSize = effBorderSizes.getOrNull(index) ?: 0
                        val hasStroke = effHasBorder.getOrNull(index) ?: (borderSize > 0)
                        val hexBorderColor = effBorderColors.getOrNull(index)
                        val parsedBorderColor = if (hexBorderColor != null && hexBorderColor.length >= 7) {
                            java.awt.Color.decode(hexBorderColor.take(7))
                        } else {
                            java.awt.Color(0, 0, 0)
                        }

                        val rot = effTextAngles.getOrNull(index) ?: 0.0
                        val theta = Math.toRadians(rot)
                        val cos = cos(theta)
                        val sin = sin(theta)

                        val hexColor = effTextColors.getOrNull(index)
                        val textColor = if (hexColor != null && hexColor.length >= 7) {
                            val c = java.awt.Color.decode(hexColor.take(7))
                            PsdColor(c.red, c.green, c.blue, 255)
                        } else {
                            PsdColor(0, 0, 0, 255)
                        }

                        val shape = effShapes.getOrNull(index) ?: "oval"

                        // Use first 20 characters of the text as the layer name
                        val textName = if (text.length > 20) text.substring(0, 20) else text.ifEmpty { "Testo $index" }

                        textLayer(name = textName, textValue = text) {
                            top = ibTop
                            left = ibLeft
                            bottom = ibBottom
                            right = ibRight
                            shapeType = TextShapeType.BOX
                            boxBounds = floatArrayOf(0f, 0f, boxWidth.toFloat(), boxHeight.toFloat())
                            transform(cos, sin, -sin, cos, ibLeft.toDouble(), ibTop.toDouble())

                            val bPadding = minOf(boxWidth, boxHeight) * settings.paddingPercentage
                            var boundaryShape = customBoundaries?.getOrNull(index)
                            if (boundaryShape == null && matched?.polygonPixels != null) {
                                boundaryShape = PolygonTextBoundary(
                                    polygon = matched.polygonPixels,
                                    padding = bPadding,
                                    visualCenter = matched.visualCenter
                                )
                            }
                            if (boundaryShape == null) {
                                boundaryShape = if (shape.equals("rectangular", ignoreCase = true)) {
                                    com.wip.kpsd.RectangleBoundary(padding = bPadding)
                                } else {
                                    com.wip.kpsd.EllipseBoundary(padding = bPadding)
                                }
                            }
                            this.boundaryShape = boundaryShape

                            wordBreak = com.wip.kpsd.WordBreak.NONE
                            verticalAlignment = com.wip.kpsd.VerticalAlignment.CENTER

                            style {
                                font(fName)
                                this.fontSize = initialFontSize.toFloat()
                                autoFit = com.wip.kpsd.AutoFit(minSize = 8f, maxSize = initialFontSize.toFloat())
                                fillColor(textColor.r, textColor.g, textColor.b)
                            }

                            paragraphStyle {
                                justification = Justification.CENTER
                            }

                            if (hasStroke && borderSize > 0) {
                                effectsOpen = true
                                effects {
                                    stroke {
                                        size = com.wip.kpsd.UnitsValue(Units.PIXELS, borderSize.toFloat())
                                        rgb(parsedBorderColor.red, parsedBorderColor.green, parsedBorderColor.blue)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findTextLayers(layers: List<Layer>?): List<Layer> {
        if (layers == null) return emptyList()
        val result = mutableListOf<Layer>()
        for (l in layers) {
            if (l.text != null) {
                result.add(l)
            }
            if (l.children != null) {
                result.addAll(findTextLayers(l.children))
            }
        }
        return result
    }

    @Capability(
        name = "Extract Text Layers from PSD",
        description = "Reads a PSD file and returns a list of text layers with their text, position, and font info."
    )
    suspend fun extractTextLayers(
        @CapabilityParam(description = "Path to PSD file") psdPath: String,
        context: PluginContext
    ): List<ExtractedText> {
        val file = File(psdPath)
        if (!file.exists()) {
            throw IllegalArgumentException("PSD file not found: $psdPath")
        }
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val psd = withContext(Dispatchers.Default) { KPsd.read(bytes) }

        val textLayers = findTextLayers(psd.children)
        return textLayers.map { l ->
            val tData = l.text!!
            val fontName = tData.style?.font?.name ?: tData.styleRuns?.firstOrNull()?.style?.font?.name
            val fontSize = tData.style?.fontSize ?: tData.styleRuns?.firstOrNull()?.style?.fontSize

            val isCollapsed = l.left == l.right || l.top == l.bottom
            val left = if (isCollapsed && tData.transform != null) {
                tData.transform!![4].toInt()
            } else {
                l.left
            }
            val top = if (isCollapsed && tData.transform != null) {
                tData.transform!![5].toInt()
            } else {
                l.top
            }
            val right = if (isCollapsed && tData.transform != null) {
                (tData.transform!![4] + (tData.right ?: 0f) - (tData.left ?: 0f)).toInt()
            } else {
                l.right
            }
            val bottom = if (isCollapsed && tData.transform != null) {
                (tData.transform!![5] + (tData.bottom ?: 0f) - (tData.top ?: 0f)).toInt()
            } else {
                l.bottom
            }

            ExtractedText(
                text = tData.text.replace("\r", "\n"),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                fontName = fontName,
                fontSize = fontSize
            )
        }
    }
}
