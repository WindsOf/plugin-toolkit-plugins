package com.wip.psdbuilder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginInfo
import java.io.File
import javax.imageio.ImageIO

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
    val strokeSize: Int? = null
)

@Serializable
data class PsdPayload(
    val backgroundImage: String,
    val texts: List<PsdText>
)

@PluginInfo(
    id = "com.wip.psdbuilder.native",
    name = "PSD Builder Native",
    version = "3.0.1",
    description = "A plugin that builds layered PSD files natively in Kotlin."
)
class PSDBuilderPlugin {

    @Capability(
        name = "Build PSD from Image and Texts",
        description = "Generates a layered PSD natively in Kotlin from an image, texts and bounding boxes"
    )
    suspend fun buildPsdFromInputs(
        @CapabilityParam(description = "Path to image") imagePath: String,
        @CapabilityParam(description = "Texts to add") texts: List<String>,
        @CapabilityParam(
            description = "Bounding boxes to add, (xmin, ymin, xmax, ymax)",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Font size (optional)", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Font name (optional)", defaultValue = "\"Anime Ace 2.0 BB\"") fontName: String? = "Anime Ace 2.0 BB",
        @CapabilityParam(description = "Border thickness (0 for none)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Output directory (optional)", defaultValue = "\"\"") outputDir: String? = "",
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdFromInputs for $imagePath")

        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Image file not found: $imagePath")
        }

        val baseImage = withContext(Dispatchers.IO) { ImageIO.read(inputFile) }
            ?: throw IllegalArgumentException("Failed to read image bounds for $imagePath")
        val width = baseImage.width.toDouble()
        val height = baseImage.height.toDouble()

        val psdTexts = texts.zip(bb).map { (text, box) ->
            val left = (box[0] * width).toInt()
            val top = (box[1] * height).toInt()
            val right = (box[2] * width).toInt()
            val bottom = (box[3] * height).toInt()

            PsdText(
                text = text,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                fontName = fontName,
                fontSize = fontSize,
                color = PsdColor(0, 0, 0, 255),
                strokeSize = borderSize
            )
        }

        val outDir = if (outputDir.isNullOrBlank()) inputFile.parentFile else File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outputPsdPath = File(outDir, inputFile.nameWithoutExtension + ".psd").absolutePath

        buildPsdNative(inputFile.absolutePath, psdTexts, outputPsdPath, context)
        return outputPsdPath
    }

    @Capability(
        name = "Build PSD for Chapter",
        description = "Generates layered PSDs natively for a folder of images concurrently"
    )
    suspend fun buildPsdForChapter(
        @CapabilityParam(
            description = "Path to folder of images",
            semanticTypes = ["sys/directory"]
        ) inputFolder: String,
        @CapabilityParam(description = "Texts to add") texts: List<String>,
        @CapabilityParam(
            description = "Bounding boxes to add, (xmin, ymin, xmax, ymax)",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Page names corresponding to each text") pageNames: List<String>,
        @CapabilityParam(description = "Output directory", defaultValue = "\"\"") outputDir: String? = "",
        @CapabilityParam(description = "Font size (optional)", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Font name (optional)", defaultValue = "\"Anime Ace 2.0 BB\"") fontName: String? = "Anime Ace 2.0 BB",
        @CapabilityParam(description = "Border thickness (0 for none)", defaultValue = "3") borderSize: Int? = 3,
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

        val minSize = minOf(texts.size, bb.size, pageNames.size)
        if (minSize != texts.size || minSize != bb.size || minSize != pageNames.size) {
            logger.warn("Size mismatch in Add Text to Chapter inputs: texts (${texts.size}), bb (${bb.size}), pageNames (${pageNames.size}). Truncating to $minSize.")
        }
        val groupedData = (0 until minSize).groupBy { pageNames[it] }
        val totalPages = groupedData.size
        var processedPages = 0

        val semaphore = Semaphore(4)

        coroutineScope {
            groupedData.map { (pageName, indices) ->
                async {
                    semaphore.withPermit {
                        val imageFile = File(folder, pageName)
                        if (imageFile.exists()) {
                            logger.info("Processing page natively: $pageName")
                            val outputPsdPath = File(outDir, pageName.substringBeforeLast(".") + ".psd").absolutePath
                            
                            val pageTexts = indices.map { texts[it] }
                            val pageBb = indices.map { bb[it] }

                            val baseImage = withContext(Dispatchers.IO) { ImageIO.read(imageFile) }
                            if (baseImage != null) {
                                val width = baseImage.width.toDouble()
                                val height = baseImage.height.toDouble()

                                val psdTexts = pageTexts.zip(pageBb).map { (text, box) ->
                                    val left = (box[0] * width).toInt()
                                    val top = (box[1] * height).toInt()
                                    val right = (box[2] * width).toInt()
                                    val bottom = (box[3] * height).toInt()

                                    PsdText(
                                        text = text,
                                        left = left,
                                        top = top,
                                        right = right,
                                        bottom = bottom,
                                        fontName = fontName,
                                        fontSize = fontSize,
                                        color = PsdColor(0, 0, 0, 255),
                                        strokeSize = borderSize
                                    )
                                }

                                buildPsdNative(imageFile.absolutePath, psdTexts, outputPsdPath, context)
                            } else {
                                logger.warn("Failed to read image bounds for: $pageName")
                            }
                        } else {
                            logger.warn("Image file not found for page name: $pageName")
                        }
                        
                        synchronized(this@PSDBuilderPlugin) {
                            processedPages++
                            progressReporter.report(processedPages.toFloat() / totalPages.toFloat())
                        }
                    }
                }
            }.awaitAll()
        }

        logger.info("Processed $processedPages pages natively to ${outDir.absolutePath}")
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
        buildPsdNative(payload.backgroundImage, payload.texts, outputPsdPath, context)
        return outputPsdPath
    }

    private fun wrapText(text: String, maxWidth: Int, fontSize: Int): String {
        val approxCharWidth = fontSize * 0.6
        val maxChars = maxOf(1, (maxWidth / approxCharWidth).toInt())
        
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = ""
        
        for (word in words) {
            if ((currentLine + word).length > maxChars) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.trim())
                    currentLine = "$word "
                } else {
                    lines.add(word)
                    currentLine = ""
                }
            } else {
                currentLine += "$word "
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.trim())
        }
        return lines.joinToString("\r")
    }

    private suspend fun buildPsdNative(imagePath: String, psdTexts: List<PsdText>, outputPath: String, context: PluginContext) {
        val logger = context.logger
        logger.info("Building native PSD: $imagePath -> $outputPath")

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
        val bgPixelData = com.wip.kpsd.PixelData(width, height, bgBytes)

        val bgLayer = com.wip.kpsd.Layer(
            name = "Background",
            top = 0,
            left = 0,
            bottom = height,
            right = width,
            imageData = bgPixelData
        )

        val layers = mutableListOf<com.wip.kpsd.Layer>(bgLayer)

        for ((index, t) in psdTexts.withIndex()) {
            val boxWidth = t.right - t.left
            val boxHeight = t.bottom - t.top
            val fontSize = t.fontSize ?: 24
            val fontName = if (t.fontName == "ArialMT" || t.fontName == "Anime ACE 2.0" || t.fontName == "Anime Ace 2.0 BB") "AnimeAce2.0BB" else (t.fontName ?: "AnimeAce2.0BB")

            val wrappedText = wrapText(t.text, boxWidth, fontSize)

            val hasStroke = t.strokeSize != null && t.strokeSize > 0
            val textLayer = com.wip.kpsd.Layer(
                name = "Testo $index",
                top = t.top,
                left = t.left,
                bottom = t.bottom,
                right = t.right,
                text = com.wip.kpsd.LayerTextData(
                    text = wrappedText,
                    shapeType = "box",
                    boxBounds = floatArrayOf(0f, 0f, boxHeight.toFloat(), boxWidth.toFloat()),
                    transform = doubleArrayOf(1.0, 0.0, 0.0, 1.0, t.left.toDouble(), t.top.toDouble()),
                    left = 0f,
                    top = 0f,
                    right = boxWidth.toFloat(),
                    bottom = boxHeight.toFloat(),
                    style = com.wip.kpsd.TextStyle(
                        font = com.wip.kpsd.Font(name = fontName),
                        fontSize = fontSize.toFloat(),
                        fillColor = com.wip.kpsd.Rgb(t.color?.r ?: 0, t.color?.g ?: 0, t.color?.b ?: 0),
                        strokeColor = if (hasStroke) com.wip.kpsd.Rgb(255, 255, 255) else null,
                        strokeFlag = if (hasStroke) true else null,
                        outlineWidth = if (hasStroke) t.strokeSize!!.toFloat() else null,
                        fillFlag = if (hasStroke) true else null,
                        fillFirst = if (hasStroke) true else null
                    ),
                    paragraphStyle = com.wip.kpsd.ParagraphStyle(
                        justification = "center"
                    )
                )
            )
            layers.add(textLayer)
        }

        val psd = com.wip.kpsd.Psd(
            width = width,
            height = height,
            children = layers,
            imageData = bgPixelData
        )

        val psdBytes = withContext(Dispatchers.Default) {
            com.wip.kpsd.KPsd.write(psd, compress = false)
        }

        withContext(Dispatchers.IO) {
            File(outputPath).writeBytes(psdBytes)
        }
    }
}
