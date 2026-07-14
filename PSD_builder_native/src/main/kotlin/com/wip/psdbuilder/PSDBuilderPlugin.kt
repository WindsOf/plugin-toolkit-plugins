package com.wip.psdbuilder

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.kpsd.Justification
import com.wip.kpsd.KPsd
import com.wip.kpsd.Layer
import com.wip.kpsd.PixelData
import com.wip.kpsd.Psd
import com.wip.kpsd.TextShapeType
import com.wip.kpsd.Units
import com.wip.kpsd.psd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.CapabilityResult
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.annotations.PluginSetting
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.cos
import kotlin.math.sin

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

@Serializable
data class PSDBuildResult(
    @CapabilityResult(
        name = "generated psd",
        description = "Path to the generated PSD file",
        semanticTypes = ["path/file"]
    )
    val psdPath: String
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
        required = false
    )
    val debugMode: Boolean = false,

    @PluginSetting(
        description = "Percentage of the bounding box size to use as padding",
        defaultValue = "0.10",
        required = false
    )
    val paddingPercentage: Float = 0.10f
)

@PluginInfo(
    id = "com.wip.psdbuilder.native",
    name = "PSD Builder Native",
    version = "5.1.3",
    description = "A plugin that builds layered PSD files natively in Kotlin."
)
class PSDBuilderPlugin(val settings: PSDBuilderSettings = PSDBuilderSettings()) {
    init {
        javax.imageio.ImageIO.setUseCache(false)
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
        context.logger.info("PSDBuilderPlugin setup complete.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("PSDBuilderPlugin update complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        context.logger.info("PSDBuilderPlugin validation passed.")
        return Result.success(Unit)
    }

    @Capability(
        name = "Build PSD from Image and Texts",
        description = "Generates a layered PSD natively in Kotlin from an image, texts and bounding boxes"
    )
    suspend fun buildPsdFromInputs(
        @CapabilityInput(description = "Path to the base image (JPG, PNG, WebP)", semanticTypes = ["path/file"])
        imagePath: String,
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
        val borderSizes = List(texts.size) { borderSize ?: 3 }

        val psd = buildPsdObject(
            imagePath = imagePath,
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
            shapes = shapes
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
        @CapabilityParam(description = "Advanced OCR Result") ocrData: AdvancedOCRResult,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityOutput(description = "Directory to save generated PSD", semanticTypes = ["path/folder"])
        outputDir: String,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): PSDBuildResult {
        return buildPsdFromInputs(
            imagePath = imagePath,
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
        @CapabilityParam(description = "OCR Result") ocrData: OCRResult,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityOutput(description = "Directory to save generated PSD", semanticTypes = ["path/folder"])
        outputDir: String,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): PSDBuildResult {
        return buildPsdFromInputs(
            imagePath = imagePath,
            texts = ocrData.texts,
            balloonBoxes = ocrData.bb,
            fontSize = fontSize,
            borderSize = borderSize,
            outputDir = outputDir,
            leaveIntermediateFiles = leaveIntermediateFiles,
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
        val semaphore = Semaphore(4)
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
                        } finally {
                            reader.dispose()
                        }
                    } else {
                        val imgBmp = ImageIO.read(img)
                        w = imgBmp?.width ?: 0
                        h = imgBmp?.height ?: 0
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

                        if (desiredHeight != null && desiredHeight > 0 && group.files.size > 1) {
                            mergedBmp = java.awt.image.BufferedImage(group.maxWidth, group.mergedHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                            g2d = mergedBmp.createGraphics()
                        }

                        for (imageFile in group.files) {
                            val pageName = imageFile.name
                            val indices = groupedData[pageName] ?: emptyList()
                            val imgW = imageDimensions[imageFile]?.first ?: 0
                            val imgH = imageDimensions[imageFile]?.second ?: 0

                            if (mergedBmp != null && g2d != null) {
                                val bmp = withContext(Dispatchers.IO) { ImageIO.read(imageFile) }
                                g2d.drawImage(bmp, 0, currentYOffset, null)
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

                        val psdFontString = when (fontName) {
                            PsdFont.ARIAL -> "ArialMT"
                            else -> "AnimeAce2.0BB"
                        }

                        val fontSizes = List(pageTexts.size) { fontSize ?: 60 }
                        val fontNames = List(pageTexts.size) { psdFontString }
                        val borderSizes = List(pageTexts.size) { borderSize ?: 3 }

                        val psd = buildPsdObject(
                            imagePath = if (mergedBmp == null) group.files.first().absolutePath else null,
                            baseImageBmp = mergedBmp,
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
                            borderColors = if (safeBorderColors != null) pageBorderColors else null
                        )
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
        @CapabilityParam(description = "Advanced OCR Result") ocrData: AdvancedOCRResult,
        @CapabilityOutput(description = "Directory to save generated PSDs", semanticTypes = ["path/folder"])
        outputDir: String,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "60") fontSize: Int? = 60,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Desired height of the final PSD. 0 to disable merging", defaultValue = "0") desiredHeight: Int? = 0,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterPSDBuildResult {
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
        @CapabilityParam(description = "OCR Result") ocrData: OCRResult,
        @CapabilityOutput(description = "Directory to save generated PSDs", semanticTypes = ["path/folder"])
        outputDir: String,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "60") fontSize: Int? = 60,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Desired height of the final PSD. 0 to disable merging", defaultValue = "0") desiredHeight: Int? = 0,
        context: PluginContext,
        hostFs: HostFileSystem
    ): ChapterPSDBuildResult {
        return buildPsdForChapter(
            inputFolder = inputFolder,
            texts = ocrData.texts,
            balloonBoxes = ocrData.bb,
            pageNames = ocrData.pageNames,
            outputDir = outputDir,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = borderSize,
            leaveIntermediateFiles = leaveIntermediateFiles,
            desiredHeight = desiredHeight,
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
        @CapabilityOutput(description = "Output PSD path", semanticTypes = ["path/file"])
        outputPsdPath: String,
        @CapabilityInput(description = "Path to base image (optional, default looks in JSON)", defaultValue = "\"\"", semanticTypes = ["path/file"])
        baseImagePath: String? = "",
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
        val baseImage = ensureFastImage(originalBaseImage)
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
        val textAngles = activeTexts.map { it.textAngle ?: it.rotation }
        val textColors = activeTexts.map {
            it.textColor ?: if (it.color != null) String.format("#%02x%02x%02x", it.color.r, it.color.g, it.color.b) else null
        }
        val fontSizes = activeTexts.map { it.fontSize }
        val fontNames = activeTexts.map { it.fontFamily ?: it.fontName }
        val borderSizes = activeTexts.map { it.strokeSize }
        val shapes = activeTexts.map { it.shape }
        val hasBorderList = activeTexts.map { it.hasBorder ?: (it.strokeSize != null && it.strokeSize > 0) }
        val borderColors = activeTexts.map { it.borderColor }

        val psd = buildPsdObject(
            imagePath = imagePathToUse,
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
        customBoundaries: List<com.wip.kpsd.TextBoundary>? = null
    ): Psd {
        val originalBaseImage = if (baseImageBmp != null) {
            baseImageBmp
        } else {
            val inputFile = File(imagePath!!)
            withContext(Dispatchers.IO) { ImageIO.read(inputFile) }
                ?: throw IllegalArgumentException("Failed to read image: $imagePath")
        }
        val baseImage = ensureFastImage(originalBaseImage)

        val width = baseImage.width
        val height = baseImage.height

        val boxDataList = mutableListOf<BoxData>()
        for ((index, text) in texts.withIndex()) {
            val balloonBox = if (index < balloonBoxes.size) balloonBoxes[index] else emptyList()
            val textBox = textBoxes?.getOrNull(index) ?: emptyList()

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
            var cx: Double
            var cy: Double

            if (tBox.size >= 4 && bBox.size >= 4) {
                val t_ymin = tBox[0]
                val t_xmin = tBox[1]
                val t_ymax = tBox[2]
                val t_xmax = tBox[3]

                val b_ymin = bBox[0]
                val b_xmin = bBox[1]
                val b_ymax = bBox[2]
                val b_xmax = bBox[3]

                cx = (t_xmin + t_xmax) / 2.0
                cy = (t_ymin + t_ymax) / 2.0

                val tw = t_xmax - t_xmin
                val th = t_ymax - t_ymin

                var marginLeft = maxOf(0.0, t_xmin - b_xmin)
                var marginRight = maxOf(0.0, b_xmax - t_xmax)
                var marginTop = maxOf(0.0, t_ymin - b_ymin)
                var marginBottom = maxOf(0.0, b_ymax - t_ymax)

                val thresholdX = tw * 0.5
                val thresholdY = th * 0.5
                val fallbackX = tw * 0.4
                val fallbackY = th * 0.4

                val isHallucinating = (
                    marginLeft > thresholdX ||
                    marginRight > thresholdX ||
                    marginTop > thresholdY ||
                    marginBottom > thresholdY
                )

                if (isHallucinating) {
                    if (marginLeft > thresholdX) marginLeft = fallbackX
                    if (marginRight > thresholdX) marginRight = fallbackX
                    if (marginTop > thresholdY) marginTop = fallbackY
                    if (marginBottom > thresholdY) marginBottom = fallbackY

                    ibx0 = t_xmin - marginLeft
                    ibx1 = t_xmax + marginRight
                    iby0 = t_ymin - marginTop
                    iby1 = t_ymax + marginBottom

                    cx = (ibx0 + ibx1) / 2.0
                    cy = (iby0 + iby1) / 2.0
                } else {
                    val bw = b_xmax - b_xmin
                    val bh = b_ymax - b_ymin

                    ibx0 = cx - bw / 2.0
                    ibx1 = cx + bw / 2.0
                    iby0 = cy - bh / 2.0
                    iby1 = cy + bh / 2.0
                }
            } else if (bBox.size >= 4) {
                ibx0 = bBox[1]; iby0 = bBox[0]; ibx1 = bBox[3]; iby1 = bBox[2]
                cx = (ibx0 + ibx1) / 2.0; cy = (iby0 + iby1) / 2.0
            } else if (tBox.size >= 4) {
                ibx0 = tBox[1]; iby0 = tBox[0]; ibx1 = tBox[3]; iby1 = tBox[2]
                cx = (ibx0 + ibx1) / 2.0; cy = (iby0 + iby1) / 2.0
            } else {
                ibx0 = 0.4 * width; iby0 = 0.4 * height; ibx1 = 0.6 * width; iby1 = 0.6 * height
                cx = (ibx0 + ibx1) / 2.0; cy = (iby0 + iby1) / 2.0
            }

            boxDataList.add(BoxData(ibx0, iby0, ibx1, iby1, cx, cy, bBox, tBox))
        }

        var debugPixelData: PixelData? = null
        if (settings.debugMode) {
            val debugImg = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2d = debugImg.createGraphics()
            for ((index, box) in boxDataList.withIndex()) {
                if (box.bBox.size >= 4) {
                    g2d.color = java.awt.Color.MAGENTA
                    g2d.stroke = java.awt.BasicStroke(2f)
                    g2d.drawRect(
                        box.bBox[1].toInt(),
                        box.bBox[0].toInt(),
                        (box.bBox[3]-box.bBox[1]).toInt(),
                        (box.bBox[2]-box.bBox[0]).toInt()
                    )
                }
                if (box.tBox.size >= 4) {
                    g2d.color = java.awt.Color.GREEN
                    g2d.stroke = java.awt.BasicStroke(3f)
                    g2d.drawRect(box.tBox[1].toInt(), box.tBox[0].toInt(), (box.tBox[3]-box.tBox[1]).toInt(), (box.tBox[2]-box.tBox[0]).toInt())
                    g2d.color = java.awt.Color.RED
                    g2d.drawLine((box.cx - 10).toInt(), box.cy.toInt(), (box.cx + 10).toInt(), box.cy.toInt())
                    g2d.drawLine(box.cx.toInt(), (box.cy - 10).toInt(), box.cx.toInt(), (box.cy + 10).toInt())
                }
                g2d.color = java.awt.Color.ORANGE
                g2d.stroke = java.awt.BasicStroke(4f)
                g2d.drawRect(box.ibx0.toInt(), box.iby0.toInt(), (box.ibx1-box.ibx0).toInt(), (box.iby1-box.iby0).toInt())

                g2d.color = java.awt.Color.BLUE
                val shape = shapes?.getOrNull(index) ?: "oval"
                if (shape.equals("rectangular", ignoreCase = true)) {
                    g2d.drawRect(box.ibx0.toInt(), box.iby0.toInt(), (box.ibx1 - box.ibx0).toInt(), (box.iby1 - box.iby0).toInt())
                } else {
                    g2d.drawOval(box.ibx0.toInt(), box.iby0.toInt(), (box.ibx1 - box.ibx0).toInt(), (box.iby1 - box.iby0).toInt())
                }
            }
            g2d.dispose()

            val debugImgFile = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g2dFile = debugImgFile.createGraphics()
            g2dFile.drawImage(baseImage, 0, 0, null)
            g2dFile.drawImage(debugImg, 0, 0, null)
            g2dFile.dispose()

            val outDir = File("build/tmp")
            if (!outDir.exists()) outDir.mkdirs()
            val baseName = imagePath?.let { File(it).nameWithoutExtension } ?: "merged_psd"
            withContext(Dispatchers.IO) {
                try {
                    ImageIO.write(debugImgFile, "png", File(outDir, "${baseName}_psd_builder.png"))
                } catch (e: Exception) {
                    context.logger.warn("Failed to write debug image: ${e.message}")
                }
            }

            val debugRgbData = IntArray(width * height)
            debugImg.getRGB(0, 0, width, height, debugRgbData, 0, width)
            val debugBytes = ByteArray(width * height * 4 + 64)
            for (i in 0 until width * height) {
                val argb = debugRgbData[i]
                val a = (argb shr 24) and 0xff
                val r = (argb shr 16) and 0xff
                val g = (argb shr 8) and 0xff
                val b = argb and 0xff
                val base = i * 4
                debugBytes[base] = r.toByte()
                debugBytes[base + 1] = g.toByte()
                debugBytes[base + 2] = b.toByte()
                debugBytes[base + 3] = a.toByte()
            }
            debugPixelData = PixelData(width, height, debugBytes)
        }

        val rgbData = IntArray(width * height)
        baseImage.getRGB(0, 0, width, height, rgbData, 0, width)
        val bgBytes = ByteArray(width * height * 4 + 64) // Pad with 64 extra bytes to prevent RLE lookahead crashes in KPsd
        for (i in 0 until width * height) {
            val argb = rgbData[i]
            val a = (argb shr 24) and 0xff
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val base = i * 4
            bgBytes[base] = r.toByte()
            bgBytes[base + 1] = g.toByte()
            bgBytes[base + 2] = b.toByte()
            bgBytes[base + 3] = a.toByte()
        }
        val bgPixelData = PixelData(width, height, bgBytes)

        return psd(width = width, height = height) {
            imageData = bgPixelData

            layer(name = "Background") {
                top = 0
                left = 0
                bottom = height
                right = width
                // Create defensive copies to avoid shared state mutations that lead to IndexOutOfBoundsException
                imageData = PixelData(width, height, bgBytes.copyOf())
            }

            // Add the "Clean" layer above the "Background" layer
            layer(name = "Clean") {
                top = 0
                left = 0
                bottom = height
                right = width
                imageData = PixelData(width, height, bgBytes.copyOf())
            }

            if (debugPixelData != null) {
                layer(name = "Debug Boxes") {
                    hidden = true
                    top = 0
                    left = 0
                    bottom = height
                    right = width
                    imageData = debugPixelData
                }
            }

            // Group all text layers inside "Testi" folder
            if (texts.isNotEmpty()) {
                group(name = "Testi") {
                    for ((index, text) in texts.withIndex()) {
                        val box = boxDataList[index]

                        val ibTop = box.iby0.toInt()
                        val ibLeft = box.ibx0.toInt()
                        val ibBottom = box.iby1.toInt()
                        val ibRight = box.ibx1.toInt()

                        val boxWidth = maxOf(1, ibRight - ibLeft)
                        val boxHeight = maxOf(1, ibBottom - ibTop)

                    val initialFontSize = fontSizes?.getOrNull(index) ?: 60
                    val fNameInput = fontNames?.getOrNull(index) ?: "AnimeAce2.0BB"
                    val fName = if (fNameInput == "ArialMT" || fNameInput == "Arial") "ArialMT" else "AnimeAce2.0BB"

                    val borderSize = borderSizes?.getOrNull(index) ?: 3
                    val hasStroke = hasBorder?.getOrNull(index) ?: (borderSize > 0)
                    val hexBorderColor = borderColors?.getOrNull(index)
                    val parsedBorderColor = if (hexBorderColor != null && hexBorderColor.length >= 7) {
                        java.awt.Color.decode(hexBorderColor.take(7))
                    } else {
                        java.awt.Color(0, 0, 0)
                    }

                    val rot = textAngles?.getOrNull(index) ?: 0.0
                    val theta = Math.toRadians(rot)
                    val cos = cos(theta)
                    val sin = sin(theta)

                    val hexColor = textColors?.getOrNull(index)
                    val textColor = if (hexColor != null && hexColor.length >= 7) {
                        val c = java.awt.Color.decode(hexColor.take(7))
                        PsdColor(c.red, c.green, c.blue, 255)
                    } else {
                        PsdColor(0, 0, 0, 255)
                    }

                    val shape = shapes?.getOrNull(index) ?: "oval"

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

                        if (hasStroke) {
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
