package com.wip.psdbuilder

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import com.wip.kpsd.Justification
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
import org.wip.plugintoolkit.api.annotations.PluginInfo
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
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val fontName: String? = null,
    val fontSize: Int? = null,
    val color: PsdColor? = null,
    val strokeSize: Int? = null,
    val rotation: Double? = null
)

@Serializable
data class PsdPayload(
    val backgroundImage: String,
    val texts: List<PsdText>
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

@PluginInfo(
    id = "com.wip.psdbuilder.native",
    name = "PSD Builder Native",
    version = "4.4.2",
    description = "A plugin that builds layered PSD files natively in Kotlin."
)
class PSDBuilderPlugin {
    init {
        try {
            IIORegistry.getDefaultInstance().registerServiceProvider(WebPImageReaderSpi())
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Capability(
        name = "Build PSD from Image and Texts",
        description = "Generates a layered PSD natively in Kotlin from an image, texts and bounding boxes"
    )
    suspend fun buildPsdFromInputs(
        @CapabilityParam(description = "Path to the base image (JPG, PNG, WebP)") imagePath: String,
        @CapabilityParam(description = "List of text strings to render") texts: List<String>,
        @CapabilityParam(
            description = "List of bounding boxes for each text [xmin, ymin, xmax, ymax]",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(
            description = "Typeface for the text",
            defaultValue = "\"ANIME_ACE_2_0_BB\""
        ) fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(
            description = "Thickness of the text stroke/border (0 to disable)",
            defaultValue = "3"
        ) borderSize: Int? = 3,
        @CapabilityParam(
            description = "Directory to save generated PSD (leave empty to use image folder)",
            defaultValue = "\"\""
        ) outputDir: String? = "",
        @CapabilityParam(
            description = "Keep intermediate JSON and temp image files for debugging",
            defaultValue = "false"
        ) leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Rotations in degrees (optional)") rotations: List<Double>? = null,
        @CapabilityParam(description = "Shapes of the balloons") shapes: List<String>? = null,
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdFromInputs for $imagePath")

        val outDir = if (outputDir.isNullOrBlank()) File(imagePath).parentFile else File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outputPsdPath = File(outDir, File(imagePath).nameWithoutExtension + ".psd").absolutePath

        val psdFontString = when (fontName) {
            PsdFont.ARIAL -> "ArialMT"
            PsdFont.ANIME_ACE_2_0_BB -> "AnimeAce2.0BB"
            else -> "AnimeAce2.0BB"
        }

        val colors = List(texts.size) { PsdColor(0, 0, 0, 255) }
        val fontSizes = List(texts.size) { fontSize ?: 24 }
        val fontNames = List(texts.size) { psdFontString }
        val borderSizes = List(texts.size) { borderSize ?: 3 }

        val psd = buildPsdObject(
            imagePath = imagePath,
            texts = texts,
            bb = bb,
            fontSizes = fontSizes,
            fontNames = fontNames,
            borderSizes = borderSizes,
            colors = colors,
            context = context,
            rotations = rotations,
            shapes = shapes
        )
        val psdBytes = withContext(Dispatchers.Default) {
            com.wip.kpsd.KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
        }

        return outputPsdPath
    }

    @Capability(
        name = "Build PSD for Chapter",
        description = "Generates layered PSDs natively for a folder of images concurrently"
    )
    suspend fun buildPsdForChapter(
        @CapabilityParam(
            description = "Path to folder containing chapter images",
            semanticTypes = ["sys/directory"]
        ) inputFolder: String,
        @CapabilityParam(description = "List of text strings to render across all pages") texts: List<String>,
        @CapabilityParam(
            description = "List of bounding boxes for each text [xmin, ymin, xmax, ymax]",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "List of image filenames corresponding to each text") pageNames: List<String>,
        @CapabilityParam(
            description = "Directory to save generated PSDs (leave empty for 'output_psds' in input folder)",
            defaultValue = "\"\""
        ) outputDir: String? = "",
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
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
        @CapabilityParam(description = "Rotations in degrees (optional)") rotations: List<Double>? = null,
        @CapabilityParam(description = "Shapes of the balloons") shapes: List<String>? = null,
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdForChapter for $inputFolder")
        val progressReporter = context.progress

        val folder = File(inputFolder)
        if (!folder.exists() || !folder.isDirectory) {
            throw IllegalArgumentException("Input folder not found or is not a directory: $inputFolder")
        }

        val outDir = if (outputDir.isNullOrBlank()) {
            File(folder, "output_psds").apply { mkdirs() }
        } else {
            File(outputDir).apply { mkdirs() }
        }

        val maxTexts = maxOf(texts.size, pageNames.size, bb.size)
        if (maxTexts != texts.size || maxTexts != bb.size || maxTexts != pageNames.size) {
            logger.warn("Mismatch in Add Text to Chapter inputs: texts (${texts.size}), bb (${bb.size}), pageNames (${pageNames.size}). Missing bounds will be defaulted.")
        }

        val safeTexts = List(maxTexts) { i -> if (i < texts.size) texts[i] else "" }
        val safePageNames = List(maxTexts) { i -> if (i < pageNames.size) pageNames[i] else "" }
        val safeBb = List(maxTexts) { i -> if (i < bb.size) bb[i] else emptyList() }
        val safeRotations = rotations?.let { rList -> List(maxTexts) { i -> if (i < rList.size) rList[i] else 0.0 } }

        val groupedData = (0 until maxTexts).groupBy { safePageNames[it] }

        val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")
        val allImages = folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.sortedBy { it.name } ?: emptyList()

        val totalPages = allImages.size
        val processedPages = java.util.concurrent.atomic.AtomicInteger(0)

        val semaphore = Semaphore(4)

        coroutineScope {
            allImages.map { imageFile ->
                async {
                    semaphore.withPermit {
                        val pageName = imageFile.name
                        logger.info("Processing page: $pageName")
                        val outputPsdPath = File(outDir, imageFile.nameWithoutExtension + ".psd").absolutePath

                        val indices = groupedData[pageName] ?: emptyList()
                        val pageTexts = indices.map { safeTexts[it] }
                        val pageBb = indices.map { safeBb[it] }
                        val pageRotations = safeRotations?.let { rList -> indices.map { rList[it] } }
                        val pageShapes = shapes?.let { sList -> indices.map { sList[it] } }
                        val pageColors = List(pageTexts.size) { PsdColor(0, 0, 0, 255) }

                        val psdFontString = when (fontName) {
                            PsdFont.ARIAL -> "ArialMT"
                            else -> "AnimeAce2.0BB"
                        }

                        val fontSizes = List(pageTexts.size) { fontSize ?: 24 }
                        val fontNames = List(pageTexts.size) { psdFontString }
                        val borderSizes = List(pageTexts.size) { borderSize ?: 3 }

                        val psd = buildPsdObject(
                            imagePath = imageFile.absolutePath,
                            texts = pageTexts,
                            bb = pageBb,
                            fontSizes = fontSizes,
                            fontNames = fontNames,
                            borderSizes = borderSizes,
                            colors = pageColors,
                            context = context,
                            rotations = pageRotations,
                            shapes = pageShapes
                        )
                        val psdBytes = withContext(Dispatchers.Default) {
                            com.wip.kpsd.KPsd.write(psd, compress = false)
                        }
                        withContext(Dispatchers.IO) {
                            File(outputPsdPath).writeBytes(psdBytes)
                        }

                        val completed = processedPages.incrementAndGet()
                        progressReporter.report(completed.toFloat() / totalPages.toFloat())
                    }
                }
            }.awaitAll()
        }

        return outDir.absolutePath
    }

    @Capability(
        name = "Build PSD from JSON",
        description = "Generates a layered PSD natively from a given JSON file"
    )
    suspend fun buildPsdFromJson(
        @CapabilityParam(description = "Path to JSON file") jsonPath: String,
        @CapabilityParam(description = "Output PSD path") outputPsdPath: String,
        context: PluginContext
    ): String {
        val jsonFile = File(jsonPath)
        if (!jsonFile.exists()) {
            throw IllegalArgumentException("JSON file not found: $jsonPath")
        }
        val jsonContent = withContext(Dispatchers.IO) { jsonFile.readText() }
        val payload = Json.decodeFromString<PsdPayload>(jsonContent)

        val texts = payload.texts.map { it.text }
        val baseImage = withContext(Dispatchers.IO) { ImageIO.read(File(payload.backgroundImage)) }
        val width = baseImage.width.toDouble()
        val height = baseImage.height.toDouble()
        val bb = payload.texts.map { listOf(it.left / width, it.top / height, it.right / width, it.bottom / height) }
        val rotations = payload.texts.map { it.rotation }
        val colors = payload.texts.map { it.color }
        val fontSizes = payload.texts.map { it.fontSize }
        val fontNames = payload.texts.map { it.fontName }
        val borderSizes = payload.texts.map { it.strokeSize }

        val psd = buildPsdObject(
            imagePath = payload.backgroundImage,
            texts = texts,
            bb = bb,
            fontSizes = fontSizes,
            fontNames = fontNames,
            borderSizes = borderSizes,
            colors = colors,
            context = context,
            rotations = rotations
        )
        val psdBytes = withContext(Dispatchers.Default) {
            com.wip.kpsd.KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
        }
        return outputPsdPath
    }

    private fun normalizeBoundingBox(box: List<Double>): List<Double> {
        if (box.size >= 4) return box.take(4)
        if (box.size == 3) {
            val w = kotlin.math.abs(box[2] - box[0])
            return listOf(box[0], box[1], box[2], box[1] + w)
        }
        return listOf(0.4, 0.4, 0.6, 0.6)
    }

    suspend fun buildPsdObject(
        imagePath: String,
        texts: List<String>,
        bb: List<List<Double>>,
        fontSizes: List<Int?>? = null,
        fontNames: List<String?>? = null,
        borderSizes: List<Int?>? = null,
        colors: List<PsdColor?>? = null,
        context: PluginContext,
        rotations: List<Double?>? = null,
        shapes: List<String?>? = null
    ): Psd {
        val inputFile = File(imagePath)
        val baseImage = withContext(Dispatchers.IO) { ImageIO.read(inputFile) }
            ?: throw IllegalArgumentException("Failed to read image: $imagePath")

        val width = baseImage.width
        val height = baseImage.height

        val rgbData = IntArray(width * height)
        baseImage.getRGB(0, 0, width, height, rgbData, 0, width)
        val bgBytes = ByteArray(width * height * 4)
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
                imageData = bgPixelData
            }

            // Add the "Clean" layer above the "Background" layer
            layer(name = "Clean") {
                top = 0
                left = 0
                bottom = height
                right = width
                imageData = bgPixelData
            }

            // Group all text layers inside "Testi" folder
            group(name = "Testi") {
                for ((index, text) in texts.withIndex()) {
                    val box = if (index < bb.size) bb[index] else emptyList()
                    val safeBox = normalizeBoundingBox(box)
                    val tLeft = (safeBox[0] * width).toInt()
                    val tTop = (safeBox[1] * height).toInt()
                    val tRight = (safeBox[2] * width).toInt()
                    val tBottom = (safeBox[3] * height).toInt()

                    val boxWidth = tRight - tLeft
                    val boxHeight = tBottom - tTop
                    val initialFontSize = fontSizes?.getOrNull(index) ?: 24
                    val fNameInput = fontNames?.getOrNull(index) ?: "AnimeAce2.0BB"
                    val fName = if (fNameInput == "ArialMT" || fNameInput == "Arial") "ArialMT" else "AnimeAce2.0BB"

                    val borderSize = borderSizes?.getOrNull(index) ?: 3
                    val hasStroke = borderSize > 0
                    val rot = rotations?.getOrNull(index) ?: 0.0
                    val theta = Math.toRadians(rot)
                    val cos = cos(theta)
                    val sin = sin(theta)
                    val textColor = colors?.getOrNull(index) ?: PsdColor(255, 255, 255, 255)

                    val shape = shapes?.getOrNull(index) ?: "oval"

                    // Use first 20 characters of the text as the layer name
                    val textName = if (text.length > 20) text.substring(0, 20) else text.ifEmpty { "Testo $index" }

                    textLayer(name = textName, textValue = text) {
                        top = tTop
                        left = tLeft
                        bottom = tBottom
                        right = tRight
                        shapeType = TextShapeType.BOX
                        boxBounds = floatArrayOf(0f, 0f, boxWidth.toFloat(), boxHeight.toFloat())
                        transform(cos, sin, -sin, cos, tLeft.toDouble(), tTop.toDouble())

                        val bPadding = minOf(boxWidth, boxHeight) * 0.05f
                        boundaryShape = if (shape.equals("rectangular", ignoreCase = true)) {
                            com.wip.kpsd.RectangleBoundary(padding = bPadding)
                        } else {
                            com.wip.kpsd.EllipseBoundary(padding = bPadding)
                        }
                        wordBreak = com.wip.kpsd.WordBreak.BREAK_WORD
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
                                    rgb(0, 0, 0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findTextLayers(layers: List<com.wip.kpsd.Layer>?): List<com.wip.kpsd.Layer> {
        if (layers == null) return emptyList()
        val result = mutableListOf<com.wip.kpsd.Layer>()
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
        val psd = withContext(Dispatchers.Default) { com.wip.kpsd.KPsd.read(bytes) }

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
