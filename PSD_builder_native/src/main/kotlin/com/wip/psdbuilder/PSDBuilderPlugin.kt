package com.wip.psdbuilder

import com.wip.kpsd.Justification
import com.wip.kpsd.TextShapeType
import com.wip.kpsd.psd
import com.wip.kpsd.Units
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
        @CapabilityParam(description = "Rotations in degrees (optional)") rotations: List<Double>? = null,
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdFromInputs for $imagePath")

        val outDir = if (outputDir.isNullOrBlank()) File(imagePath).parentFile else File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outputPsdPath = File(outDir, File(imagePath).nameWithoutExtension + ".psd").absolutePath

        val psd = buildPsdObject(imagePath, texts, bb, fontSize, fontName, borderSize, context, rotations)
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
        @CapabilityParam(description = "Rotations in degrees (optional)") rotations: List<Double>? = null,
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
                            val outputPsdPath = File(outDir, pageName.substringBeforeLast(".") + ".psd").absolutePath
                            
                            val pageTexts = indices.map { texts[it] }
                            val pageBb = indices.map { bb[it] }
                            val pageRotations = rotations?.let { rList -> indices.map { rList[it] } }

                            val psd = buildPsdObject(imageFile.absolutePath, pageTexts, pageBb, fontSize, fontName, borderSize, context, pageRotations)
                            val psdBytes = withContext(Dispatchers.Default) {
                                com.wip.kpsd.KPsd.write(psd, compress = false)
                            }
                            withContext(Dispatchers.IO) {
                                File(outputPsdPath).writeBytes(psdBytes)
                            }
                        }
                        
                        synchronized(this@PSDBuilderPlugin) {
                            processedPages++
                            progressReporter.report(processedPages.toFloat() / totalPages.toFloat())
                        }
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
        // buildPsdObject wants normalized BBs, but payload has raw coords.
        // Let's create a specialized object builder or handle coords correctly.
        val baseImage = withContext(Dispatchers.IO) { ImageIO.read(File(payload.backgroundImage)) }
        val width = baseImage.width.toDouble()
        val height = baseImage.height.toDouble()
        val bb = payload.texts.map { listOf(it.left / width, it.top / height, it.right / width, it.bottom / height) }
        val rotations = payload.texts.map { it.rotation }
        
        val psd = buildPsdObject(
            payload.backgroundImage,
            texts,
            bb,
            payload.texts.firstOrNull()?.fontSize,
            payload.texts.firstOrNull()?.fontName,
            payload.texts.firstOrNull()?.strokeSize,
            context,
            rotations
        )
        val psdBytes = withContext(Dispatchers.Default) {
            com.wip.kpsd.KPsd.write(psd, compress = false)
        }
        withContext(Dispatchers.IO) {
            File(outputPsdPath).writeBytes(psdBytes)
        }
        return outputPsdPath
    }

    suspend fun buildPsdObject(
        imagePath: String,
        texts: List<String>,
        bb: List<List<Double>>,
        fontSize: Int? = 24,
        fontName: String? = "Anime Ace 2.0 BB",
        borderSize: Int? = 3,
        context: PluginContext,
        rotations: List<Double?>? = null
    ): com.wip.kpsd.Psd {
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

        return psd(width = width, height = height) {
            imageData = bgPixelData

            layer(name = "Background") {
                top = 0
                left = 0
                bottom = height
                right = width
                imageData = bgPixelData
            }

            for ((index, text) in texts.withIndex()) {
                val box = bb[index]
                val tLeft = (box[0] * width).toInt()
                val tTop = (box[1] * height).toInt()
                val tRight = (box[2] * width).toInt()
                val tBottom = (box[3] * height).toInt()
                
                val boxWidth = tRight - tLeft
                val boxHeight = tBottom - tTop
                val fSize = fontSize ?: 24
                val fName = if (fontName == "ArialMT" || fontName == "Anime ACE 2.0" || fontName == "Anime Ace 2.0 BB") "AnimeAce2.0BB" else (fontName ?: "AnimeAce2.0BB")

                val wrappedText = wrapText(text, boxWidth, fSize)

                val hasStroke = borderSize != null && borderSize > 0
                val rot = rotations?.getOrNull(index) ?: 0.0
                val theta = Math.toRadians(rot)
                val cos = Math.cos(theta)
                val sin = Math.sin(theta)

                textLayer(name = text, textValue = wrappedText) {
                    top = tTop
                    left = tLeft
                    bottom = tBottom
                    right = tRight
                    shapeType = TextShapeType.BOX
                    boxBounds = floatArrayOf(0f, 0f, boxWidth.toFloat(), boxHeight.toFloat())
                    transform(cos, sin, -sin, cos, tLeft.toDouble(), tTop.toDouble())
                    
                    style {
                        font(fName)
                        this.fontSize = fSize.toFloat()
                        fillColor(255, 255, 255)
                    }
                    
                    paragraphStyle {
                        justification = Justification.LEFT
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
                text = tData.text.replace("\r", "").replace("\n", ""),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                fontName = fontName,
                fontSize = fontSize
            )
        }
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
}
