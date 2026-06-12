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
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
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

@Serializable
data class PSDBuildResult(
    @CapabilityOutput(
        name = "generated psd",
        description = "Path to the generated PSD file",
        semanticTypes = ["sys/file"]
    )
    val psdPath: String
)

@PluginInfo(
    id = "com.wip.psdbuilder.native",
    name = "PSD Builder Native",
    version = "4.4.4",
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
        @CapabilityParam(description = "Path to the base image (JPG, PNG, WebP)") imagePath: String,
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
        @CapabilityParam(
            description = "Directory to save generated PSD (leave empty to use image folder)",
            defaultValue = "\"\""
        ) outputDir: String? = "",
        @CapabilityParam(
            description = "Keep intermediate JSON and temp image files for debugging",
            defaultValue = "false"
        ) leaveIntermediateFiles: Boolean? = false,
        @CapabilityParam(description = "Rotations/Angles in degrees") textAngles: List<Double>? = null,
        @CapabilityParam(description = "Text Colors") textColors: List<String>? = null,
        @CapabilityParam(description = "Has Border") hasBorder: List<Boolean>? = null,
        @CapabilityParam(description = "Border Colors") borderColors: List<String>? = null,
        @CapabilityParam(description = "Shapes of the balloons") shapes: List<String>? = null,
        context: PluginContext
    ): PSDBuildResult {
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
            com.wip.kpsd.KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
            logger.info("PSD Generation Complete: $outputPsdPath")
        }

        return PSDBuildResult(outputPsdPath)
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
            description = "List of bounding boxes for each balloon (ymin, xmin, ymax, xmax)",
            semanticTypes = ["wom/bounding-box"]
        ) balloonBoxes: List<List<Double>>,
        @CapabilityParam(
            description = "List of bounding boxes for each text (ymin, xmin, ymax, xmax)",
            semanticTypes = ["wom/bounding-box"]
        ) textBoxes: List<List<Double>>? = null,
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
        @CapabilityParam(description = "Rotations/Angles in degrees") textAngles: List<Double>? = null,
        @CapabilityParam(description = "Text Colors") textColors: List<String>? = null,
        @CapabilityParam(description = "Has Border") hasBorder: List<Boolean>? = null,
        @CapabilityParam(description = "Border Colors") borderColors: List<String>? = null,
        @CapabilityParam(description = "Shapes of the balloons") shapes: List<String>? = null,
        context: PluginContext
    ): PSDBuildResult {
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
                        val pageBalloonBoxes = indices.map { safeBalloonBoxes[it] }
                        val pageTextBoxes = safeTextBoxes?.let { tList -> indices.map { tList[it] } }
                        val pageTextAngles = safeTextAngles?.let { rList -> indices.map { rList[it] } }
                        val pageTextColors = safeTextColors?.let { cList -> indices.map { cList[it] } }
                        val pageHasBorder = safeHasBorder?.let { bList -> indices.map { bList[it] } }
                        val pageBorderColors = safeBorderColors?.let { cList -> indices.map { cList[it] } }
                        val pageShapes = shapes?.let { sList -> indices.map { sList[it] } }

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
                            balloonBoxes = pageBalloonBoxes,
                            textBoxes = pageTextBoxes,
                            fontSizes = fontSizes,
                            fontNames = fontNames,
                            borderSizes = borderSizes,
                            context = context,
                            textAngles = pageTextAngles,
                            shapes = pageShapes,
                            textColors = pageTextColors,
                            hasBorder = pageHasBorder,
                            borderColors = pageBorderColors
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
            logger.info("All $totalPages PSDs generated successfully.")
        }

        return PSDBuildResult(outDir.absolutePath)
    }

    @Capability(
        name = "Build PSD from JSON",
        description = "Generates a layered PSD natively from a given JSON file"
    )
    suspend fun buildPsdFromJson(
        @CapabilityParam(description = "Path to JSON file") jsonPath: String,
        @CapabilityParam(description = "Output PSD path") outputPsdPath: String,
        context: PluginContext
    ): PSDBuildResult {
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
        val balloonBoxes = payload.texts.map { listOf(it.top / height, it.left / width, it.bottom / height, it.right / width) }
        val textAngles = payload.texts.map { it.rotation }
        val colors = payload.texts.map { it.color }
        val textColors = colors.map { 
            if (it != null) String.format("#%02x%02x%02x", it.r, it.g, it.b) else null
        }
        val fontSizes = payload.texts.map { it.fontSize }
        val fontNames = payload.texts.map { it.fontName }
        val borderSizes = payload.texts.map { it.strokeSize }

        val psd = buildPsdObject(
            imagePath = payload.backgroundImage,
            texts = texts,
            balloonBoxes = balloonBoxes,
            fontSizes = fontSizes,
            fontNames = fontNames,
            borderSizes = borderSizes,
            context = context,
            textAngles = textAngles,
            textColors = textColors
        )
        val psdBytes = withContext(Dispatchers.Default) {
            com.wip.kpsd.KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
        }
        return PSDBuildResult(outputPsdPath)
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
        borderColors: List<String?>? = null
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
                    val balloonBox = if (index < balloonBoxes.size) balloonBoxes[index] else emptyList()
                    val textBox = textBoxes?.getOrNull(index) ?: emptyList()
                    val safeBalloonBox = normalizeBoundingBox(balloonBox)
                    
                    // The inputs are ymin, xmin, ymax, xmax
                    val bTop = (safeBalloonBox[0] * height).toInt()
                    val bLeft = (safeBalloonBox[1] * width).toInt()
                    val bBottom = (safeBalloonBox[2] * height).toInt()
                    val bRight = (safeBalloonBox[3] * width).toInt()

                    val tTop: Int
                    val tLeft: Int
                    val tBottom: Int
                    val tRight: Int
                    
                    if (textBox.size >= 4) {
                        tTop = (textBox[0] * height).toInt()
                        tLeft = (textBox[1] * width).toInt()
                        tBottom = (textBox[2] * height).toInt()
                        tRight = (textBox[3] * width).toInt()
                    } else {
                        tTop = bTop
                        tLeft = bLeft
                        tBottom = bBottom
                        tRight = bRight
                    }

                    val cx = (tLeft + tRight) / 2.0
                    val cy = (tTop + tBottom) / 2.0

                    var bw = (bRight - bLeft).toDouble()
                    var bh = (bBottom - bTop).toDouble()

                    val tw = (tRight - tLeft).toDouble()
                    val th = (tBottom - tTop).toDouble()

                    if (bw >= 2 * tw || bh >= 2 * th) {
                        bw = tw * 1.25
                        bh = th * 1.25
                    }

                    val ibLeft = (cx - bw / 2).toInt()
                    val ibTop = (cy - bh / 2).toInt()
                    val ibRight = (cx + bw / 2).toInt()
                    val ibBottom = (cy + bh / 2).toInt()

                    val boxWidth = ibRight - ibLeft
                    val boxHeight = ibBottom - ibTop

                    val initialFontSize = fontSizes?.getOrNull(index) ?: 24
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
                                    rgb(parsedBorderColor.red, parsedBorderColor.green, parsedBorderColor.blue)
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
