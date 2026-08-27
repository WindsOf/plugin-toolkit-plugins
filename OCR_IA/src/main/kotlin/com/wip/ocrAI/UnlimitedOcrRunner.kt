package com.wip.ocrAI

import com.wip.common.inference.llama.LlamaBackend
import com.wip.common.inference.llama.LlamaInferenceClient
import com.wip.common.inference.llama.LlamaServerConfig
import com.wip.common.inference.llama.LlamaServerManager
import com.wip.common.inference.llama.LlamaServerMode
import com.wip.common.inference.llama.LlamaServerSession
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.BalloonsResponse
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.OCRResult
import com.wip.ocrAI.models.OcrIASettings
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.PluginSignal

class UnlimitedOcrRunner(
    private val context: PluginContext,
    private val hostFs: HostFileSystem,
    private val settings: OcrIASettings = OcrIASettings()
) {
    private val logger: PluginLogger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

    init {
        context.signals.onSignal { signal ->
            if (signal == PluginSignal.CANCEL) {
                isCancelled = true
                logger.info("Cancellation signal received in UnlimitedOcrRunner.")
            }
        }
    }

    private fun resolveFiles(input: String): List<File> {
        val inputPath = java.nio.file.Paths.get(input)
        val files = mutableListOf<File>()
        val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")

        when {
            Files.isRegularFile(inputPath) -> {
                val f = inputPath.toFile()
                if (f.extension.lowercase() in imageExtensions) {
                    files.add(f)
                } else {
                    logger.error("File '${f.name}' is not a supported image format ($imageExtensions).")
                }
            }
            Files.isDirectory(inputPath) -> {
                inputPath.toFile()
                    .listFiles { f -> f.extension.lowercase() in imageExtensions }
                    ?.sortedBy { it.name }
                    ?.let { files.addAll(it) }
            }
            else -> {
                logger.error("Path '$input' does not exist.")
            }
        }
        return files
    }

    private suspend fun getLlamaServerSession(targetModelId: String? = null): LlamaServerSession? {
        val candidateGgufIds = listOfNotNull(
            targetModelId.takeIf { !it.isNullOrBlank() && it != ModelCatalog.UNLIMITED_OCR_ID },
            ModelCatalog.UNLIMITED_OCR_BF16_ID,
            ModelCatalog.UNLIMITED_OCR_Q8_0_ID,
            ModelCatalog.UNLIMITED_OCR_Q4_K_M_ID,
            ModelCatalog.UNLIMITED_OCR_IQ2_M_ID,
            ModelCatalog.UNLIMITED_OCR_ID
        ).distinct()

        val installedId = candidateGgufIds.firstOrNull {
            ModelManager.Default.isModelInstalled(it, context.fileSystem, logger)
        }

        if (installedId == null) {
            logger.warn("Unlimited-OCR model is not installed in plugin storage or LM Studio. Please download it via the 'Download Model' action.")
            return null
        }

        val modelPath = ModelManager.Default.getModelAbsolutePath(installedId, context.fileSystem)
        if (!File(modelPath).exists() || !modelPath.endsWith(".gguf", ignoreCase = true)) {
            logger.warn("Model path '$modelPath' is invalid or not a .gguf file.")
            return null
        }

        val mmprojPath = ModelManager.Default.getMmprojAbsolutePath(installedId, context.fileSystem)
        if (mmprojPath != null) {
            logger.info("Found multimodal projector (mmproj) for Unlimited-OCR: $mmprojPath")
        } else {
            logger.warn("No multimodal projector (mmproj) found for Unlimited-OCR. Multimodal vision parsing may fail without --mmproj.")
        }

        val llamaConfig = LlamaServerConfig(
            mode = settings.llamaServerMode ?: LlamaServerMode.AUTO,
            backend = settings.llamaServerBackend ?: LlamaBackend.AUTO,
            customPath = settings.llamaServerCustomPath?.ifBlank { null },
            gpuLayers = settings.llamaServerGpuLayers ?: 99,
            port = settings.llamaServerPort ?: 8080,
            contextSize = settings.llamaServerContextSize ?: 8192,
            mmprojPath = mmprojPath
        )

        return try {
            LlamaServerManager.Default.getOrStartServer(
                modelPath = modelPath,
                config = llamaConfig,
                fileSystem = context.fileSystem,
                logger = logger,
                progress = progressReporter
            )
        } catch (e: Exception) {
            logger.warn("Failed to start llama-server for '$modelPath': ${e.message}")
            null
        }
    }

    data class ExtractedTextRegion(
        val text: String,
        val ymin: Double,
        val xmin: Double,
        val ymax: Double,
        val xmax: Double,
        val shape: String = "oval",
        val fontStyle: String = "normal",
        val fontFamily: String = "sans-serif",
        val textAngle: Double = 0.0,
        val isSparse: Boolean = false,
        val textColor: String = "#000000",
        val hasBorder: Boolean = false,
        val borderColor: String = ""
    )

    fun parseOcrOutput(rawOutput: String, imgWidth: Double, imgHeight: Double): List<ExtractedTextRegion> {
        val regions = mutableListOf<ExtractedTextRegion>()

        // 1. Try structured JSON parsing if present
        val jsonPattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```|(\\{[\\s\\S]*\\})", Pattern.CASE_INSENSITIVE)
        val jsonMatcher = jsonPattern.matcher(rawOutput)
        if (jsonMatcher.find()) {
            val jsonText = (jsonMatcher.group(1) ?: jsonMatcher.group(2) ?: "").trim()
            try {
                val json = Json { ignoreUnknownKeys = true }
                val parsed = json.decodeFromString<BalloonsResponse>(jsonText)
                for (b in parsed.balloons) {
                    val box = scaleBox(listOf(b.ymin, b.xmin, b.ymax, b.xmax), imgWidth, imgHeight)
                    regions.add(
                        ExtractedTextRegion(
                            text = b.text,
                            ymin = box[0],
                            xmin = box[1],
                            ymax = box[2],
                            xmax = box[3]
                        )
                    )
                }
                if (regions.isNotEmpty()) return regions
            } catch (e: Exception) {
                // Fallback to regex tags
            }
        }

        // 2. Format: <|ref|>text<|/ref|><|box|>[ymin, xmin, ymax, xmax]<|/box|>
        val refBoxPattern = Pattern.compile(
            "<\\|ref\\|>(.*?)<\\|/ref\\|>\\s*<\\|box\\|>\\s*\\[?\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*\\]?\\s*<\\|/box\\|>",
            Pattern.DOTALL
        )
        val refBoxMatcher = refBoxPattern.matcher(rawOutput)
        while (refBoxMatcher.find()) {
            val text = refBoxMatcher.group(1).trim()
            val ymin = refBoxMatcher.group(2).toDoubleOrNull() ?: 0.0
            val xmin = refBoxMatcher.group(3).toDoubleOrNull() ?: 0.0
            val ymax = refBoxMatcher.group(4).toDoubleOrNull() ?: 0.0
            val xmax = refBoxMatcher.group(5).toDoubleOrNull() ?: 0.0
            val box = scaleBox(listOf(ymin, xmin, ymax, xmax), imgWidth, imgHeight)
            if (text.isNotBlank()) {
                regions.add(
                    ExtractedTextRegion(
                        text = text,
                        ymin = box[0],
                        xmin = box[1],
                        ymax = box[2],
                        xmax = box[3]
                    )
                )
            }
        }
        if (regions.isNotEmpty()) return regions

        // 3. Format: <|det|>label [ymin, xmin, ymax, xmax]<|/det|>text
        val detPattern = Pattern.compile(
            "<\\|det\\|>(?:[^<\\[]+)?\\s*\\[?\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*\\]?\\s*<\\|/det\\|>(.*)",
            Pattern.DOTALL
        )
        for (line in rawOutput.lines()) {
            val m = detPattern.matcher(line.trim())
            if (m.find()) {
                val ymin = m.group(1).toDoubleOrNull() ?: 0.0
                val xmin = m.group(2).toDoubleOrNull() ?: 0.0
                val ymax = m.group(3).toDoubleOrNull() ?: 0.0
                val xmax = m.group(4).toDoubleOrNull() ?: 0.0
                val text = m.group(5).trim()
                val box = scaleBox(listOf(ymin, xmin, ymax, xmax), imgWidth, imgHeight)
                if (text.isNotBlank()) {
                    regions.add(
                        ExtractedTextRegion(
                            text = text,
                            ymin = box[0],
                            xmin = box[1],
                            ymax = box[2],
                            xmax = box[3]
                        )
                    )
                }
            }
        }
        if (regions.isNotEmpty()) return regions

        // 4. Fallback if plain text output without explicit boxes: wrap whole image
        if (rawOutput.isNotBlank()) {
            regions.add(
                ExtractedTextRegion(
                    text = rawOutput.trim(),
                    ymin = 0.0,
                    xmin = 0.0,
                    ymax = imgHeight,
                    xmax = imgWidth
                )
            )
        }

        return regions
    }

    private fun scaleBox(box: List<Double>, width: Double, height: Double): List<Double> {
        if (box.size < 4) return box
        val is1000Scale = box.any { it > 2.0 }
        val scale = if (is1000Scale) 1000.0 else 1.0

        val ymin = (box[0] / scale) * height
        val xmin = (box[1] / scale) * width
        val ymax = (box[2] / scale) * height
        val xmax = (box[3] / scale) * width

        return listOf(
            min(ymin, ymax).coerceIn(0.0, height),
            min(xmin, xmax).coerceIn(0.0, width),
            max(ymin, ymax).coerceIn(0.0, height),
            max(xmin, xmax).coerceIn(0.0, width)
        )
    }

    private fun saveJsonResult(save: Boolean, outputDir: String, file: File, regions: List<ExtractedTextRegion>, rawOutput: String) {
        if (!save) return
        val outDir = File(outputDir)
        if (!outDir.exists()) outDir.mkdirs()

        val jsonObject = buildJsonObject {
            putJsonArray("balloons") {
                regions.forEach { r ->
                    add(buildJsonObject {
                        put("text", r.text)
                        put("ymin", r.ymin)
                        put("xmin", r.xmin)
                        put("ymax", r.ymax)
                        put("xmax", r.xmax)
                    })
                }
            }
            put("raw_response", rawOutput)
        }

        val outFile = File(outDir, "${file.nameWithoutExtension}_OCR.json")
        outFile.writeText(jsonObject.toString(), Charsets.UTF_8)
        logger.info("Saved Unlimited-OCR result to: ${outFile.absolutePath}")
    }

    suspend fun performOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        useStructuredOutput: Boolean,
        saveThinking: Boolean,
        targetModelId: String? = null
    ): OCRResult {
        val files = resolveFiles(input)
        if (files.isEmpty()) {
            return OCRResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        logger.info("[UnlimitedOcrRunner] Starting OCR for ${files.size} image(s) with model '$targetModelId'...")

        val llamaSession = getLlamaServerSession(targetModelId)

        val allTexts = mutableListOf<String>()
        val allBoxes = mutableListOf<List<Double>>()
        val allPageNumbers = mutableListOf<Int>()
        val allPageNames = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val total = files.size

        for ((index, file) in files.withIndex()) {
            if (isCancelled) {
                logger.info("[UnlimitedOcrRunner] Execution cancelled.")
                break
            }

            try {
                val image = withContext(Dispatchers.IO) {
                    ImageIO.read(file)
                } ?: throw IllegalArgumentException("Failed to decode image from path: ${file.absolutePath}")

                val imgW = image.width.toDouble()
                val imgH = image.height.toDouble()

                val rawOutput = if (llamaSession != null) {
                    val prompt = "Locate all speech bubbles and text in this image. Provide text transcriptions and bounding boxes."
                    LlamaInferenceClient.Default.executeVisionChat(
                        baseUrl = llamaSession.baseUrl,
                        imageFile = file,
                        promptInstructions = prompt,
                        logger = logger
                    )
                } else {
                    throw IllegalStateException("No Unlimited-OCR GGUF model or llama-server session is available.")
                }

                val regions = parseOcrOutput(rawOutput, imgW, imgH)
                for (r in regions) {
                    allTexts.add(r.text)
                    allBoxes.add(listOf(r.ymin, r.xmin, r.ymax, r.xmax))
                    allPageNumbers.add(index + 1)
                    allPageNames.add(file.name)
                }

                saveJsonResult(save, outputDir, file, regions, rawOutput)
            } catch (e: Throwable) {
                logger.error("[UnlimitedOcrRunner] Failed to process '${file.name}': ${e.message}")
                failedFiles.add(file.name)
                if (save) {
                    val outDir = File(outputDir)
                    if (!outDir.exists()) outDir.mkdirs()
                    val errorFile = File(outDir, "${file.name}_ERROR.txt")
                    errorFile.writeText("Error processing '${file.name}':\n${e::class.simpleName}: ${e.message}\n\n${e.stackTraceToString()}", Charsets.UTF_8)
                }
            }

            progressReporter.report((index + 1).toFloat() / total.toFloat())
        }

        return OCRResult(allTexts, allBoxes, allPageNumbers, allPageNames, failedFiles)
    }

    suspend fun performAdvancedOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        useStructuredOutput: Boolean,
        saveThinking: Boolean,
        targetModelId: String? = null
    ): AdvancedOCRResult {
        val files = resolveFiles(input)
        if (files.isEmpty()) {
            return AdvancedOCRResult(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList()
            )
        }
        logger.info("[UnlimitedOcrRunner] Starting Advanced OCR for ${files.size} image(s) with model '$targetModelId'...")

        val llamaSession = getLlamaServerSession(targetModelId)

        val allTexts = mutableListOf<String>()
        val allBalloonBoxes = mutableListOf<List<Double>>()
        val allTextBoxes = mutableListOf<List<Double>>()
        val allShapes = mutableListOf<String>()
        val allFontStyles = mutableListOf<String>()
        val allFontFamilies = mutableListOf<String>()
        val allAngles = mutableListOf<Double>()
        val allIsSparse = mutableListOf<Boolean>()
        val allTextColors = mutableListOf<String>()
        val allHasBorder = mutableListOf<Boolean>()
        val allBorderColors = mutableListOf<String>()
        val allPageNumbers = mutableListOf<Int>()
        val allPageNames = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val total = files.size

        for ((index, file) in files.withIndex()) {
            if (isCancelled) {
                logger.info("[UnlimitedOcrRunner] Execution cancelled.")
                break
            }

            try {
                val image = withContext(Dispatchers.IO) {
                    ImageIO.read(file)
                } ?: throw IllegalArgumentException("Failed to decode image from path: ${file.absolutePath}")

                val imgW = image.width.toDouble()
                val imgH = image.height.toDouble()

                val rawOutput = if (llamaSession != null) {
                    val prompt = "Locate all speech bubbles and text in this image. For each text area provide balloon_box_2d [ymin, xmin, ymax, xmax], text_box_2d [ymin, xmin, ymax, xmax], shape, and transcribed text."
                    LlamaInferenceClient.Default.executeVisionChat(
                        baseUrl = llamaSession.baseUrl,
                        imageFile = file,
                        promptInstructions = prompt,
                        logger = logger
                    )
                } else {
                    throw IllegalStateException("No Unlimited-OCR GGUF model or llama-server session is available.")
                }

                val regions = parseOcrOutput(rawOutput, imgW, imgH)
                for (r in regions) {
                    allTexts.add(r.text)
                    val box = listOf(r.ymin, r.xmin, r.ymax, r.xmax)
                    allBalloonBoxes.add(box)
                    allTextBoxes.add(box)
                    allShapes.add(r.shape)
                    allFontStyles.add(r.fontStyle)
                    allFontFamilies.add(r.fontFamily)
                    allAngles.add(r.textAngle)
                    allIsSparse.add(r.isSparse)
                    allTextColors.add(r.textColor)
                    allHasBorder.add(r.hasBorder)
                    allBorderColors.add(r.borderColor)
                    allPageNumbers.add(index + 1)
                    allPageNames.add(file.name)
                }

                saveJsonResult(save, outputDir, file, regions, rawOutput)
            } catch (e: Throwable) {
                logger.error("[UnlimitedOcrRunner] Failed to process '${file.name}': ${e.message}")
                failedFiles.add(file.name)
                if (save) {
                    val outDir = File(outputDir)
                    if (!outDir.exists()) outDir.mkdirs()
                    val errorFile = File(outDir, "${file.name}_ERROR.txt")
                    errorFile.writeText("Error processing '${file.name}':\n${e::class.simpleName}: ${e.message}\n\n${e.stackTraceToString()}", Charsets.UTF_8)
                }
            }

            progressReporter.report((index + 1).toFloat() / total.toFloat())
        }

        return AdvancedOCRResult(
            texts = allTexts,
            balloonBoxes = allBalloonBoxes,
            textBoxes = allTextBoxes,
            shapes = allShapes,
            fontStyles = allFontStyles,
            fontFamilies = allFontFamilies,
            textAngles = allAngles,
            isSparse = allIsSparse,
            textColors = allTextColors,
            hasBorder = allHasBorder,
            borderColors = allBorderColors,
            pageNumbers = allPageNumbers,
            pageNames = allPageNames,
            failedFiles = failedFiles
        )
    }
}
