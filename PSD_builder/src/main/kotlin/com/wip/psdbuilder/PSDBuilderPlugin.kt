package com.wip.psdbuilder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginValidate
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
    id = "com.wip.psdbuilder",
    name = "PSD Builder",
    version = "3.0.0",
    description = "A plugin that builds layered PSD files using PSD_builder.exe."
)
class PSDBuilderPlugin {

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        return try {
            val fileSystem = context.fileSystem
            val logger = context.logger
            logger.info("Starting PSD Builder setup...")

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
        @CapabilityParam(description = "Path to image") imagePath: String,
        @CapabilityParam(description = "Texts to add") texts: List<String>,
        @CapabilityParam(
            description = "Bounding boxes to add, (xmin, ymin, xmax, ymax)",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Font size (optional)", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Font name (optional)", defaultValue = "Anime Ace 2.0 BB") fontName: String? = "Anime Ace 2.0 BB",
        @CapabilityParam(description = "Border thickness (0 for none)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Output directory (optional)", defaultValue = "") outputDir: String? = "",
        @CapabilityParam(description = "Leave intermediate JSON files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
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

        val payload = PsdPayload(
            backgroundImage = inputFile.absolutePath,
            texts = psdTexts
        )

        val jsonPayload = Json.encodeToString(payload)
        val tempJsonFile = File(context.fileSystem.getBasePath(), "temp_payload_${inputFile.nameWithoutExtension}.json")
        withContext(Dispatchers.IO) { tempJsonFile.writeText(jsonPayload) }

        val outDir = if (outputDir.isNullOrBlank()) inputFile.parentFile else File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outputPsdPath = File(outDir, inputFile.nameWithoutExtension + ".psd").absolutePath

        val result = try {
            executeBuilder(tempJsonFile.absolutePath, outputPsdPath, context)
        } finally {
            if (leaveIntermediateFiles != true) {
                tempJsonFile.delete()
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
            description = "Path to folder of images",
            semanticTypes = ["sys/directory"]
        ) inputFolder: String,
        @CapabilityParam(description = "Texts to add") texts: List<String>,
        @CapabilityParam(
            description = "Bounding boxes to add, (xmin, ymin, xmax, ymax)",
            semanticTypes = ["wom/bounding-box"]
        ) bb: List<List<Double>>,
        @CapabilityParam(description = "Page names corresponding to each text") pageNames: List<String>,
        @CapabilityParam(description = "Output directory", defaultValue = "") outputDir: String? = "",
        @CapabilityParam(description = "Font size (optional)", defaultValue = "24") fontSize: Int? = 24,
        @CapabilityParam(description = "Font name (optional)", defaultValue = "Anime Ace 2.0 BB") fontName: String? = "Anime Ace 2.0 BB",
        @CapabilityParam(description = "Border thickness (0 for none)", defaultValue = "3") borderSize: Int? = 3,
        @CapabilityParam(description = "Leave intermediate JSON files for debugging", defaultValue = "false") leaveIntermediateFiles: Boolean? = false,
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

        val semaphore = Semaphore(4) // Max 4 concurrent exe processes

        coroutineScope {
            groupedData.map { (pageName, indices) ->
                async {
                    semaphore.withPermit {
                        val imageFile = File(folder, pageName)
                        if (imageFile.exists()) {
                            logger.info("Processing page: $pageName")
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

                                val payload = PsdPayload(
                                    backgroundImage = imageFile.absolutePath,
                                    texts = psdTexts
                                )

                                val jsonPayload = Json.encodeToString(payload)
                                val tempJsonFile = File(context.fileSystem.getBasePath(), "temp_payload_${imageFile.nameWithoutExtension}.json")
                                withContext(Dispatchers.IO) { tempJsonFile.writeText(jsonPayload) }

                                try {
                                    executeBuilder(tempJsonFile.absolutePath, outputPsdPath, context)
                                } finally {
                                    if (leaveIntermediateFiles != true) {
                                        tempJsonFile.delete()
                                    }
                                }
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

        logger.info("Processed $processedPages pages to ${outDir.absolutePath}")
        return outDir.absolutePath
    }

    @Capability(
        name = "Build PSD from JSON",
        description = "Generates a layered PSD from a given JSON file"
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
        return executeBuilder(jsonFile.absolutePath, outputPsdPath, context)
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
