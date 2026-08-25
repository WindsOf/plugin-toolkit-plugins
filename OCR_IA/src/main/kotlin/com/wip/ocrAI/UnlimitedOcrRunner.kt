package com.wip.ocrAI

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.BalloonsResponse
import com.wip.common.models.ExecutionDevice
import com.wip.common.models.ImageTensorUtils
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.ModelSpec
import com.wip.common.models.OCRResult
import com.wip.common.models.OnnxInferenceSession
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
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

class UnlimitedOcrRunner(
    private val context: PluginContext,
    private val hostFs: HostFileSystem
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

    private suspend fun getSession(): Pair<OnnxInferenceSession, ModelSpec>? {
        val modelId = ModelCatalog.UNLIMITED_OCR_ID
        val isInstalled = ModelManager.Default.isModelInstalled(modelId, context.fileSystem, logger)
        val candidateId = if (isInstalled) {
            modelId
        } else if (ModelManager.Default.isModelInstalled(ModelCatalog.UNLIMITED_OCR_BF16_ID, context.fileSystem, logger)) {
            ModelCatalog.UNLIMITED_OCR_BF16_ID
        } else {
            logger.warn("Unlimited-OCR model '$modelId' is not installed in plugin storage. Please download it via the 'Download Model' action.")
            return null
        }

        val spec = ModelManager.Default.getModelSpec(candidateId, context.fileSystem, logger)
            ?: ModelSpec(
                name = candidateId,
                type = "deepseek_ocr_decoder",
                inputWidth = 1024,
                inputHeight = 1024
            )

        val session = ModelManager.Default.createInferenceSession(
            modelId = candidateId,
            fileSystem = context.fileSystem,
            preferredDevice = ExecutionDevice.AUTO,
            logger = logger
        )

        return if (session != null) {
            Pair(session, spec)
        } else {
            logger.warn("Failed to create ONNX session for '$candidateId'.")
            null
        }
    }

    private fun runInferenceOnImage(
        session: OnnxInferenceSession,
        spec: ModelSpec,
        image: BufferedImage
    ): String {
        val inputNames = session.session.inputNames
        val inputInfo = session.session.inputInfo
        val targetW = spec.effectiveWidth.takeIf { it > 0 } ?: 1024
        val targetH = spec.effectiveHeight.takeIf { it > 0 } ?: 1024

        logger.info("[UnlimitedOcrRunner] Model input signatures: ${inputNames.map { "$it -> ${inputInfo[it]?.info}" }}")

        val tensorMap = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in inputNames) {
                val nodeInfo = inputInfo[name]
                val tensorInfo = nodeInfo?.info as? TensorInfo
                val tensorType = tensorInfo?.type ?: OnnxJavaType.FLOAT
                val shape = tensorInfo?.shape

                when (tensorType) {
                    OnnxJavaType.FLOAT -> {
                        val isImage = name.contains("image", ignoreCase = true) ||
                                name.contains("pixel", ignoreCase = true) ||
                                name == "x" ||
                                (shape != null && shape.size == 4 && (shape[1] == 3L || shape[3] == 3L)) ||
                                (tensorMap.none { it.value.info.type == OnnxJavaType.FLOAT })

                        if (isImage) {
                            tensorMap[name] = ImageTensorUtils.createTensor(session.environment, image, targetW, targetH)
                        } else {
                            val dummy = floatArrayOf(0.0f)
                            tensorMap[name] = OnnxTensor.createTensor(
                                session.environment,
                                FloatBuffer.wrap(dummy),
                                longArrayOf(1L, 1L)
                            )
                        }
                    }
                    OnnxJavaType.INT64 -> {
                        val tensor = if (name.contains("mask", ignoreCase = true)) {
                            val mask = longArrayOf(1L)
                            OnnxTensor.createTensor(
                                session.environment,
                                LongBuffer.wrap(mask),
                                longArrayOf(1L, mask.size.toLong())
                            )
                        } else if (name.contains("grid", ignoreCase = true) || name.contains("thw", ignoreCase = true)) {
                            val grid = longArrayOf(1L, (targetH / 14).toLong(), (targetW / 14).toLong())
                            OnnxTensor.createTensor(
                                session.environment,
                                LongBuffer.wrap(grid),
                                longArrayOf(1L, 3L)
                            )
                        } else {
                            // input_ids / prompt token IDs (default 0L)
                            val promptIds = longArrayOf(0L)
                            OnnxTensor.createTensor(
                                session.environment,
                                LongBuffer.wrap(promptIds),
                                longArrayOf(1L, promptIds.size.toLong())
                            )
                        }
                        tensorMap[name] = tensor
                    }
                    OnnxJavaType.INT32 -> {
                        val dummy = intArrayOf(0)
                        tensorMap[name] = OnnxTensor.createTensor(
                            session.environment,
                            IntBuffer.wrap(dummy),
                            longArrayOf(1L, 1L)
                        )
                    }
                    else -> {
                        logger.warn("[UnlimitedOcrRunner] Unsupported input tensor type for '$name': $tensorType")
                    }
                }
            }

            if (tensorMap.isEmpty() && inputNames.isNotEmpty()) {
                val firstInput = inputNames.first()
                tensorMap[firstInput] = ImageTensorUtils.createTensor(session.environment, image, targetW, targetH)
            }

            val result = session.run(tensorMap)
            return parseSessionResult(result)
        } finally {
            tensorMap.values.forEach { tensor ->
                try {
                    tensor.close()
                } catch (e: Exception) {
                    // Ignore tensor close exception
                }
            }
        }
    }

    private fun parseSessionResult(result: OrtSession.Result): String {
        val sb = StringBuilder()
        for (output in result) {
            val value = output.value
            if (value is OnnxTensor) {
                logger.info("[UnlimitedOcrRunner] Model output '${output.key}': type=${value.info.type}, shape=${value.info.shape.contentToString()}")
                when (value.info.type) {
                    OnnxJavaType.STRING -> {
                        val stringData = value.value
                        if (stringData is Array<*>) {
                            for (item in stringData) {
                                if (item is Array<*>) {
                                    sb.append(item.filterNotNull().joinToString(" ")).append("\n")
                                } else if (item != null) {
                                    sb.append(item.toString()).append("\n")
                                }
                            }
                        } else if (stringData is String) {
                            sb.append(stringData).append("\n")
                        }
                    }
                    OnnxJavaType.INT64 -> {
                        val tensorVal = value.value
                        if (tensorVal is Array<*>) {
                            for (row in tensorVal) {
                                if (row is LongArray) {
                                    val chars = row.asSequence().mapNotNull { if (it in 32..126 || it == 10L || it == 13L) it.toInt().toChar() else null }.toList()
                                    if (chars.isNotEmpty()) {
                                        sb.append(chars.toCharArray().concatToString()).append("\n")
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        val outText = sb.toString().trim()
        logger.info("[UnlimitedOcrRunner] Raw model output: $outText")
        return outText
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
        saveThinking: Boolean
    ): OCRResult {
        val files = resolveFiles(input)
        if (files.isEmpty()) {
            return OCRResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        logger.info("[UnlimitedOcrRunner] Starting OCR for ${files.size} image(s)...")

        val sessionPair = getSession()
        val allTexts = mutableListOf<String>()
        val allBoxes = mutableListOf<List<Double>>()
        val allPageNumbers = mutableListOf<Int>()
        val allPageNames = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val total = files.size

        try {
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

                    val rawOutput = if (sessionPair != null) {
                        runInferenceOnImage(sessionPair.first, sessionPair.second, image)
                    } else {
                        throw IllegalStateException("Unlimited-OCR model session is unavailable. Make sure model is installed.")
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
        } finally {
            sessionPair?.first?.close()
        }

        return OCRResult(allTexts, allBoxes, allPageNumbers, allPageNames, failedFiles)
    }

    suspend fun performAdvancedOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        useStructuredOutput: Boolean,
        saveThinking: Boolean
    ): AdvancedOCRResult {
        val files = resolveFiles(input)
        if (files.isEmpty()) {
            return AdvancedOCRResult(
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList()
            )
        }
        logger.info("[UnlimitedOcrRunner] Starting Advanced OCR for ${files.size} image(s)...")

        val sessionPair = getSession()
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

        try {
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

                    val rawOutput = if (sessionPair != null) {
                        runInferenceOnImage(sessionPair.first, sessionPair.second, image)
                    } else {
                        throw IllegalStateException("Unlimited-OCR model session is unavailable. Make sure model is installed.")
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
        } finally {
            sessionPair?.first?.close()
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
