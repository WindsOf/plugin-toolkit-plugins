package com.wip.psdbuilder

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
    val color: PsdColor? = null
)

@Serializable
data class PsdPayload(
    val backgroundImage: String,
    val texts: List<PsdText>
)

@PluginInfo(
    id = "com.wip.psdbuilder",
    name = "PSD Builder",
    version = "1.0.0",
    description = "A plugin that builds layered PSD files using PSD_builder.exe."
)
class PSDBuilderPlugin {

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        return try {
            val fileSystem = context.fileSystem
            val logger = context.logger
            logger.info("Starting PSD Builder setup...")

            // Extract bundled resources from the JAR to the plugin's managed file area
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
        @CapabilityParam(description = "Font name (optional)", defaultValue = "ArialMT") fontName: String? = "ArialMT",
        @CapabilityParam(description = "Output directory (optional)", defaultValue = "") outputDir: String? = "",
        context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("Starting buildPsdFromInputs for $imagePath")

        val inputFile = File(imagePath)
        if (!inputFile.exists()) {
            throw IllegalArgumentException("Image file not found: $imagePath")
        }

        val baseImage = ImageIO.read(inputFile)
            ?: throw IllegalArgumentException("Failed to read image bounds for $imagePath")
        val width = baseImage.width.toDouble()
        val height = baseImage.height.toDouble()

        val psdTexts = texts.zip(bb).map { (text, box) ->
            // Assuming box is [xmin, ymin, xmax, ymax] normalized [0..1]
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
                color = PsdColor(0, 0, 0, 255)
            )
        }

        val payload = PsdPayload(
            backgroundImage = inputFile.absolutePath,
            texts = psdTexts
        )

        // Save JSON to temp location
        val jsonPayload = Json.encodeToString(payload)
        val tempJsonFile = File(context.fileSystem.getBasePath(), "temp_payload.json")
        tempJsonFile.writeText(jsonPayload)

        val outDir = if (outputDir.isNullOrBlank()) inputFile.parentFile else File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val outputPsdPath = File(outDir, inputFile.nameWithoutExtension + ".psd").absolutePath

        // Execute PSD_builder.exe
        return executeBuilder(tempJsonFile.absolutePath, outputPsdPath, context)
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

    private fun executeBuilder(jsonPath: String, outputPath: String, context: PluginContext): String {
        val logger = context.logger
        val exe = File(context.fileSystem.getBasePath(), "PSD_builder.exe")

        logger.info("Executing: ${exe.absolutePath} $jsonPath $outputPath")

        val process = ProcessBuilder(exe.absolutePath, jsonPath, outputPath)
            .directory(File(context.fileSystem.getBasePath()))
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            reader.lines().forEach { line ->
                logger.debug(line)
                output.appendLine(line)
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorMsg = "PSD Builder exited with code $exitCode. Output:\n$output"
            logger.error(errorMsg)
            throw RuntimeException(errorMsg)
        }

        logger.info("PSD generation complete: $outputPath")
        return outputPath
    }
}
