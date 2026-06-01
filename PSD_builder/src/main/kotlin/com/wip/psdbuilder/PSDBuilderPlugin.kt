package com.wip.psdbuilder

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
import org.wip.plugintoolkit.api.annotations.PluginValidate
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi

@Serializable
data class PsdColor(val r: Int, val g: Int, val b: Int, val a: Int)

enum class PsdFont {
    ANIME_ACE_2_0_BB,
    ARIAL
}

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
    id = "com.wip.psdbuilder",
    name = "PSD Builder",
    version = "4.4.0",
    description = "A plugin that builds layered PSD files using PSD_builder.exe."
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
        return try {
            val fileSystem = context.fileSystem
            val logger = context.logger
            logger.info("Starting PSD Builder setup...")
            
            // Register WebP manually because of fat-jar SPI exclusion
            IIORegistry.getDefaultInstance().registerServiceProvider(WebPImageReaderSpi())

            val resourcesToExtract = listOf(
                "tools/PSD_builder.exe" to "PSD_builder.exe"
            )

            for ((jarResource, targetPath) in resourcesToExtract) {
                logger.info("Extracting resource: $jarResource -> $targetPath")
                val result = fileSystem.extractResource(jarResource, targetPath)
                if (result.isFailure) {
                    val error = "Failed to extract $jarResource: ${result.exceptionOrNull()?.message}"
                    logger.error(error)
                    return Result.failure(RuntimeException(error))
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeBoundingBox(box: List<Double>): List<Double> {
        if (box.size >= 4) return box.take(4)
        if (box.size == 3) {
            val w = kotlin.math.abs(box[2] - box[0])
            return listOf(box[0], box[1], box[2], box[1] + w)
        }
        return listOf(0.4, 0.4, 0.6, 0.6)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        return try {
            val fileSystem = context.fileSystem
            val logger = context.logger
            val basePath = fileSystem.getBasePath()
            val exe = File(basePath, "PSD_builder.exe")

            if (!exe.exists()) {
                val error = "PSD_builder.exe not found at ${exe.absolutePath}. Please run setup."
                logger.error(error)
                return Result.failure(Exception(error))
            }

            logger.info("PSD Builder validation passed - executable found")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Capability(
        name = "Build PSD from Image and Texts",
        description = "Generates a layered PSD from an image, texts and bounding boxes"
    )
    suspend fun buildPsdFromInputs(
        @CapabilityParam(description = "Path to the base image (JPG, PNG, WebP)") imagePath: String,
        @CapabilityParam(description = "List of text strings to render") texts: List<String>,
        @CapabilityParam(
            description = "List of bounding boxes for each text [xmin, ymin, xmax, ymax]",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Directory to save generated PSD (leave empty to use image folder)", defaultValue = "\"\"") outputDir: String? = "",
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdFromInputs for $imagePath")
        
        IIORegistry.getDefaultInstance().registerServiceProvider(WebPImageReaderSpi())

        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Image file not found: $imagePath")
        }

        val baseImage = withContext(Dispatchers.IO) { ImageIO.read(inputFile) }
            ?: throw IllegalArgumentException("Failed to read image bounds for $imagePath")
        val width = baseImage.width.toDouble()
        val height = baseImage.height.toDouble()

        val psdFontString = when (fontName) {
            PsdFont.ARIAL -> "ArialMT"
            else -> "AnimeAce2.0BB"
        }

        if (texts.size != bb.size) {
            logger.warn("Mismatch in inputs sizes: texts (${texts.size}) != bb (${bb.size}). Missing bounding boxes will be defaulted.")
        }
        
        val safeBb = List(texts.size) { i -> if (i < bb.size) bb[i] else emptyList() }
        val psdTexts = texts.zip(safeBb).map { (text, rawBox) ->
            val box = normalizeBoundingBox(rawBox)
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
                fontName = psdFontString,
                fontSize = fontSize,
                color = PsdColor(0, 0, 0, 255),
                strokeSize = borderSize
            )
        }

        val outDir = if (outputDir.isNullOrBlank()) inputFile.parentFile else File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        var targetImageFile = inputFile
        var isTempImage = false
        if (inputFile.extension.equals("webp", ignoreCase = true)) {
            targetImageFile = File(outDir, "temp_image_${inputFile.nameWithoutExtension}.png")
            withContext(Dispatchers.IO) {
                ImageIO.write(baseImage, "png", targetImageFile)
            }
            isTempImage = true
        }

        val payload = PsdPayload(
            backgroundImage = targetImageFile.absolutePath,
            texts = psdTexts
        )

        val jsonPayload = Json.encodeToString(payload)
        val tempJsonFile = File(outDir, "temp_payload_${inputFile.nameWithoutExtension}.json")
        withContext(Dispatchers.IO) { tempJsonFile.writeText(jsonPayload) }

        val outputPsdPath = File(outDir, inputFile.nameWithoutExtension + ".psd").absolutePath

        val result = try {
            executeBuilder(tempJsonFile.absolutePath, outputPsdPath, context)
        } finally {
            if (leaveIntermediateFiles != true) {
                tempJsonFile.delete()
                if (isTempImage) targetImageFile.delete()
            }
        }
        return result
    }

    @Capability(
        name = "Build PSD for Chapter",
        description = "Generates layered PSDs for a folder of images concurrently"
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
        @CapabilityParam(description = "Directory to save generated PSDs (leave empty for 'output_psds' in input folder)", defaultValue = "\"\"") outputDir: String? = "",
        @CapabilityParam(description = "Font size in pixels", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Typeface for the text", defaultValue = "\"ANIME_ACE_2_0_BB\"") fontName: PsdFont? = PsdFont.ANIME_ACE_2_0_BB,
        @CapabilityParam(description = "Thickness of the text stroke/border (0 to disable)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Keep intermediate JSON and temp image files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdForChapter for $inputFolder")
        val progressReporter = context.progress
        
        IIORegistry.getDefaultInstance().registerServiceProvider(WebPImageReaderSpi())
        
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

        val groupedData = (0 until maxTexts).groupBy { safePageNames[it] }
        
        val supportedExtensions = setOf("png", "jpg", "jpeg", "webp")
        val allImages = folder.listFiles { file -> 
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.sortedBy { it.name } ?: emptyList()

        val totalPages = allImages.size
        var processedPages = 0

        val semaphore = Semaphore(4) // Max 4 concurrent exe processes

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

                        val baseImage: java.awt.image.BufferedImage? = withContext(Dispatchers.IO) { javax.imageio.ImageIO.read(imageFile) }
                        if (baseImage != null) {
                            val width = baseImage.width.toDouble()
                            val height = baseImage.height.toDouble()

                            val psdFontString = when (fontName) {
                                PsdFont.ARIAL -> "ArialMT"
                                else -> "AnimeAce2.0BB"
                            }

                            val psdTexts = pageTexts.zip(pageBb).map { (text, rawBox) ->
                                val box = normalizeBoundingBox(rawBox)
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
                                        fontName = psdFontString,
                                        fontSize = fontSize,
                                        color = PsdColor(0, 0, 0, 255),
                                        strokeSize = borderSize
                                    )
                                }

                                var targetImageFile = imageFile
                                var isTempImage = false
                                if (imageFile.extension.equals("webp", ignoreCase = true)) {
                                    targetImageFile = File(outDir, "temp_image_${imageFile.nameWithoutExtension}.png")
                                    withContext(Dispatchers.IO) {
                                    javax.imageio.ImageIO.write(baseImage, "png", targetImageFile)
                                    }
                                    isTempImage = true
                                }

                                val payload = PsdPayload(
                                    backgroundImage = targetImageFile.absolutePath,
                                    texts = psdTexts
                                )

                                val jsonPayload = Json.encodeToString(payload)
                                val tempJsonFile = File(outDir, "temp_payload_${imageFile.nameWithoutExtension}.json")
                                withContext(Dispatchers.IO) { tempJsonFile.writeText(jsonPayload) }

                            try {
                                executeBuilder(tempJsonFile.absolutePath, outputPsdPath, context)
                            } finally {
                                if (leaveIntermediateFiles != true) {
                                    tempJsonFile.delete()
                                    if (isTempImage) targetImageFile.delete()
                                }
                            }
                        } else {
                            logger.warn("Failed to read image bounds for: $pageName")
                        }
                        
                        synchronized(this@PSDBuilderPlugin) {
                            processedPages++
                            progressReporter.report(processedPages.toFloat() / totalPages.toFloat())
                        }
                    }
                }
            }.awaitAll()
        }

        logger.info("Processed $processedPages pages to ${outDir.absolutePath}")
        return outDir.absolutePath
    }



    private suspend fun executeBuilder(jsonPath: String, outputPath: String, context: PluginContext): String {
        val logger = context.logger
        val exe = File(context.fileSystem.getBasePath(), "PSD_builder.exe")

        logger.info("Executing: ${exe.absolutePath} $jsonPath $outputPath")

        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(exe.absolutePath, jsonPath, outputPath)
                .directory(File(context.fileSystem.getBasePath()))
                .redirectErrorStream(true)
                .start()
        }

        val output = StringBuilder()
        withContext(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { reader ->
                reader.lines().forEach { line ->
                    logger.debug(line)
                    output.appendLine(line)
                }
            }
        }

        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        if (exitCode != 0) {
            val errorMsg = "PSD Builder exited with code $exitCode. Output:\n$output"
            logger.error(errorMsg)
            throw RuntimeException(errorMsg)
        }

        logger.info("PSD generation complete: $outputPath")
        return outputPath
    }
}
