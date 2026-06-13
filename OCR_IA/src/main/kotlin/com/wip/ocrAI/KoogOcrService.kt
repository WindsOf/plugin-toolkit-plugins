package com.wip.ocrAI

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.dsl.Prompt
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.wip.ocrAI.models.*
import kotlinx.io.files.Path
import kotlinx.serialization.json.*
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginSignal
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.imageio.ImageIO

class KoogOcrService(private val context: PluginContext, private val settings: OcrIASettings) {
    private val logger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

    private suspend fun <T> retryWithBackoff(block: suspend () -> T): T {
        val delaysMs = listOf(10000L, 10000L, 10000L, 10000L, 10000L, 10000L, 10000L) // Max 7 retries
        val maxAttempts = delaysMs.size + 1
        var lastException: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxAttempts) {
                    val currentDelay = delaysMs[attempt - 1]
                    logger.warn("Attempt $attempt failed: ${e::class.simpleName}: ${e.message}. Retrying in ${currentDelay}ms...")
                    delay(currentDelay)
                } else {
                    logger.error("Attempt $attempt failed: ${e::class.simpleName}: ${e.message}. Max retries reached.")
                }
            }
        }
        throw lastException ?: RuntimeException("Failed after $maxAttempts attempts")
    }

    init {
        context.signals.onSignal { signal ->
            if (signal == PluginSignal.CANCEL) {
                isCancelled = true
                logger.info("Cancellation signal received.")
            }
        }
    }

    private suspend fun getImageDimensions(file: File): Pair<Double, Double> = withContext(Dispatchers.IO) {
        try {
            ImageIO.createImageInputStream(file).use { iis ->
                val readers = ImageIO.getImageReaders(iis)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    reader.input = iis
                    val w = reader.getWidth(0).toDouble()
                    val h = reader.getHeight(0).toDouble()
                    reader.dispose()
                    return@withContext w to h
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to read image dimensions for ${file.name}: ${e.message}")
        }
        1000.0 to 1000.0 // fallback
    }

    private fun scaleBoxToPixels(box: List<Double>, width: Double, height: Double): List<Double> {
        if (box.size < 4) return box
        // if the coordinates are larger than 2, they are likely on the 0-1000 scale
        val is1000Scale = box.any { it > 2.0 }
        val scale = if (is1000Scale) 1000.0 else 1.0
        
        // Assuming box is [ymin, xmin, ymax, xmax]
        val ymin = (box[0] / scale) * height
        val xmin = (box[1] / scale) * width
        val ymax = (box[2] / scale) * height
        val xmax = (box[3] / scale) * width
        
        return listOf(ymin, xmin, ymax, xmax)
    }

    private fun getExecutor(model: AIModel) = when (model) {
            AIModel.GEMMA_26B, AIModel.GEMMA_31B, AIModel.GEMINI_1_5_PRO, AIModel.GEMINI_2_5_PRO, AIModel.GEMINI_3_1_FLASH_LITE -> {
                val key = settings.googleApiKey.ifBlank { System.getenv("API_KEY") ?: "" }
                if (key.isBlank()) throw IllegalArgumentException("Google API Key not found.")
                simpleGoogleAIExecutor(key)
            }
            AIModel.CLAUDE_3_5_SONNET -> {
                val key = settings.anthropicApiKey.ifBlank { System.getenv("ANTHROPIC_API_KEY") ?: "" }
                if (key.isBlank()) throw IllegalArgumentException("Anthropic API Key not found.")
                // Note: Assuming simpleAnthropicExecutor exists in Koog
                simpleAnthropicExecutor(key)
            }
            AIModel.GPT_4O -> {
                val key = settings.openAIApiKey.ifBlank { System.getenv("OPENAI_API_KEY") ?: "" }
                if (key.isBlank()) throw IllegalArgumentException("OpenAI API Key not found.")
                simpleOpenAIExecutor(apiToken = key)
            }
            AIModel.LM_STUDIO -> {
                val key = settings.lmStudioApiKey.ifBlank { "lm-studio" }
                val baseUrl = settings.lmStudioUrl.ifBlank { "http://localhost:1234/v1" }.removeSuffix("/v1").removeSuffix("/")
                val customModelId = settings.lmStudioModelName.ifBlank { "default-model" }
                
                val wrapperClient = object : OpenAILLMClient(key, OpenAIClientSettings(baseUrl = baseUrl)) {
                    private val fullCapabilities = ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4o.capabilities
                    
                    private fun injectCapabilities(model: LLModel): LLModel {
                        return if (model.capabilities.isNullOrEmpty()) {
                            LLModel(
                                provider = model.provider,
                                id = model.id,
                                capabilities = fullCapabilities,
                                contextLength = 128000,
                                maxOutputTokens = 16384
                            )
                        } else model
                    }

                    override suspend fun execute(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): List<Message.Response> {
                        return super.execute(prompt, injectCapabilities(model), tools)
                    }

                    override fun executeStreaming(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Flow<StreamFrame> {
                        return super.executeStreaming(prompt, injectCapabilities(model), tools)
                    }

                    override suspend fun executeMultipleChoices(
                        prompt: Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): List<LLMChoice> {
                        return super.executeMultipleChoices(prompt, injectCapabilities(model), tools)
                    }
                }
                SingleLLMPromptExecutor(wrapperClient)
            }
        }

    private fun getProvider(model: AIModel): LLMProvider {
        return when (model) {
            AIModel.GEMMA_26B, AIModel.GEMMA_31B, AIModel.GEMINI_1_5_PRO, AIModel.GEMINI_2_5_PRO, AIModel.GEMINI_3_1_FLASH_LITE -> LLMProvider.Google
            AIModel.CLAUDE_3_5_SONNET -> LLMProvider.Anthropic
            AIModel.GPT_4O, AIModel.LM_STUDIO -> LLMProvider.OpenAI
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

    suspend fun performOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        useStructuredOutput: Boolean,
        saveThinking: Boolean,
        aiModel: AIModel
    ): OcrServiceResult {
        val files = resolveFiles(input)
        if (files.isEmpty()) {
            return OcrServiceResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        logger.info("Found ${files.size} image(s) to process.")

        val executor = try {
            getExecutor(aiModel)
        } catch (e: Throwable) {
            logger.error("Failed to initialize executor: ${e::class.simpleName}: ${e.message}")
            throw e
        }

        val modelId = if (aiModel == AIModel.LM_STUDIO) settings.lmStudioModelName.ifBlank { "default-model" } else aiModel.id
        logger.info("Defining LLModel: $modelId with provider ${getProvider(aiModel)}")
        val model = LLModel(
            provider = getProvider(aiModel),
            id = modelId,
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Temperature, LLMCapability.Vision.Image),
            contextLength = 100000,
        )

        val promptInstructions =
            "Analyze this comic panel. Locate ALL areas containing text (speech bubbles, captions, and text boxes). " +
            "Do NOT transcribe sound effects (SFX) or onomatopoeia that appear OUTSIDE of speech bubbles. " +
            "For each text area provide:\n" +
            " 1. The bounding box of the TEXT ITSELF (not the balloon outline).\n" +
            " Express coordinates as FRACTIONS of the image dimensions, between 0.0 and 1.0:\n" +
            " xmin = left edge / image_width, ymin = top edge / image_height,\n" +
            " xmax = right edge / image_width, ymax = bottom edge / image_height.\n" +
            " 2. The exact text transcribed from that area."

        val balloonSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("balloons") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("xmin") { put("type", "number") }
                            putJsonObject("ymin") { put("type", "number") }
                            putJsonObject("xmax") { put("type", "number") }
                            putJsonObject("ymax") { put("type", "number") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") { add("xmin"); add("ymin"); add("xmax"); add("ymax"); add("text") }
                    }
                }
            }
            putJsonArray("required") { add("balloons") }
        }

        val effectivePromptInstructions = if (useStructuredOutput) {
            promptInstructions
        } else {
            promptInstructions + "\n\nIMPORTANT: Your output MUST be a valid JSON object matching the following schema:\n" + balloonSchema.toString()
        }

        val allTexts = mutableListOf<String>()
        val allBoxes = mutableListOf<List<Double>>()
        val allPageNumbers = mutableListOf<Int>()
        val allPageNames = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val total = files.size

        val resultsMutex = Mutex()
        var processedFilesCount = 0
        val semaphore = Semaphore(5)

        coroutineScope {
            files.mapIndexed { index, file ->
                async {
                    if (isCancelled) return@async
                    semaphore.withPermit {
                        if (isCancelled) return@async
                        try {
                            val ocrPrompt = prompt(
                                id = "ocr-task",
                                params = LLMParams(
                                    schema = if (useStructuredOutput) LLMParams.Schema.JSON.Basic("BalloonsResponse", balloonSchema) else null
                                )
                            ) {
                                user {
                                    text(effectivePromptInstructions)
                                    image(Path(file.absolutePath))
                                }
                            }

                            val (balloonsResponse, rawResponse) = retryWithBackoff {
                                // Calling execute directly, relying on Koog's shared interface
                                val responses = executor.execute(ocrPrompt, model)
                                
                                var originalText = ""
                                // Handling list of responses from Koog
                                for (res in responses) {
                                    if (res != null) {
                                        originalText += "${res.content}\n"
                                    }
                                }
                                originalText = originalText.trim()
                                val rawText = originalText.replace(Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "").trim()

                                val jsonToParse = if (!useStructuredOutput) extractJsonFromText(rawText) else rawText
                                val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<BalloonsResponse>(jsonToParse)
                                val finalRawResponse = if (saveThinking) originalText else rawText
                                Pair(parsed, finalRawResponse)
                            }

                            val (imgWidth, imgHeight) = getImageDimensions(file)

                            resultsMutex.withLock {
                                balloonsResponse.balloons.forEach { balloon ->
                                    allTexts.add(balloon.text)
                                    val originalBox = listOf(balloon.ymin, balloon.xmin, balloon.ymax, balloon.xmax)
                                    allBoxes.add(scaleBoxToPixels(originalBox, imgWidth, imgHeight))
                                    allPageNumbers.add(index + 1)
                                    allPageNames.add(file.name)
                                }
                                processedFilesCount++
                                progressReporter.report(processedFilesCount.toFloat() / total.toFloat())
                            }
                            saveResult(save, outputDir, file, rawResponse)
                        } catch (e: Throwable) {
                            handleError(e, file, save, outputDir, resultsMutex, failedFiles, processedFilesCount, total)
                        }
                    }
                }
            }.awaitAll()
        }
        return OcrServiceResult(allTexts, allBoxes, allPageNumbers, allPageNames, failedFiles)
    }

    suspend fun performAdvancedOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        useStructuredOutput: Boolean,
        saveThinking: Boolean,
        aiModel: AIModel
    ): AdvancedOcrServiceResult {
        val files = resolveFiles(input)
        if (files.isEmpty()) {
            return AdvancedOcrServiceResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        
        val isGemma = aiModel == AIModel.GEMMA_26B || aiModel == AIModel.GEMMA_31B
        val modelId = if (aiModel == AIModel.LM_STUDIO) settings.lmStudioModelName.ifBlank { "default-model" } else aiModel.id
        logger.info("Found ${files.size} image(s). Advanced OCR with model: $modelId (isGemma: $isGemma)")

        val executor = try {
            getExecutor(aiModel)
        } catch (e: Throwable) {
            logger.error("Failed to initialize executor: ${e::class.simpleName}: ${e.message}")
            throw e
        }

        val model = LLModel(
            provider = getProvider(aiModel),
            id = modelId,
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Temperature, LLMCapability.Vision.Image),
            contextLength = 100000,
        )

        val coordFormat = if (isGemma) {
            "Express coordinates as EXACT ABSOLUTE PIXELS based on a 1000x1000 dimension image (width: 1000px, height: 1000px)."
        } else {
            "Express coordinates as FRACTIONS of the image dimensions, between 0.0 and 1.0."
        }

        val promptInstructions =
            "Analyze this comic panel. Locate ALL areas containing text (speech bubbles, captions, and text boxes).\n" +
            "Do NOT transcribe sound effects (SFX) or onomatopoeia that appear OUTSIDE of speech bubbles.\n" +
            "For each text area provide:\n" +
            " 1. The bounding box of the SPEECH BUBBLE / BALLOON enclosing the text (exclude the tail).\n" +
            " 2. The bounding box of the TEXT ITSELF (the tightest box around the transcribed words).\n" +
            " $coordFormat\n" +
            " Provide a 'balloon_box_2d' array and a 'text_box_2d' array containing exactly 4 numbers in this STRICT ORDER: [ymin, xmin, ymax, xmax].\n" +
            " 3. The 'shape' of the bubble: Choose EXACTLY ONE from: 'oval' or 'rectangular'.\n" +
            " 4. The 'fontStyle': Choose EXACTLY ONE from: 'normal', 'italic', 'bold', 'bold-italic'.\n" +
            " 5. The 'fontFamily': A string describing the font type, e.g. 'sans-serif', 'serif', 'handwritten', 'screaming'.\n" +
            " 6. The 'textAngle': Rotation angle of the text in degrees (e.g. 0.0 for horizontal, 90.0 for vertical).\n" +
            " 7. 'isSparse': Boolean, true if the text is sparsely spread inside the bounding box.\n" +
            " 8. 'textColor': The dominant color of the text (e.g. 'black', 'white', '#FF0000').\n" +
            " 9. 'hasBorder': Boolean, true if the text has an outline or stroke.\n" +
            " 10. 'borderColor': The color of the border/stroke if present, or an empty string if none.\n" +
            " 11. The exact 'text' transcribed from that area."

        val balloonSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("balloons") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("balloon_box_2d") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                            putJsonObject("text_box_2d") { put("type", "array"); putJsonObject("items") { put("type", "number") } }
                            putJsonObject("shape") { put("type", "string") }
                            putJsonObject("fontStyle") { put("type", "string") }
                            putJsonObject("fontFamily") { put("type", "string") }
                            putJsonObject("textAngle") { put("type", "number") }
                            putJsonObject("isSparse") { put("type", "boolean") }
                            putJsonObject("textColor") { put("type", "string") }
                            putJsonObject("hasBorder") { put("type", "boolean") }
                            putJsonObject("borderColor") { put("type", "string") }
                            putJsonObject("text") { put("type", "string") }
                        }
                        putJsonArray("required") { 
                            add("balloon_box_2d"); add("text_box_2d"); add("shape"); add("fontStyle"); add("fontFamily"); add("textAngle")
                            add("isSparse"); add("textColor"); add("hasBorder"); add("borderColor"); add("text")
                        }
                    }
                }
            }
            putJsonArray("required") { add("balloons") }
        }

        val effectivePromptInstructions = if (useStructuredOutput) promptInstructions else promptInstructions + "\n\nIMPORTANT: Your output MUST be a valid JSON object matching the following schema:\n" + balloonSchema.toString()

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

        val resultsMutex = Mutex()
        var processedFilesCount = 0
        val semaphore = Semaphore(5)

        coroutineScope {
            files.mapIndexed { index, file ->
                async {
                    if (isCancelled) return@async
                    semaphore.withPermit {
                        if (isCancelled) return@async
                        try {
                            val ocrPrompt = prompt(
                                id = "advanced-ocr-task",
                                params = LLMParams(
                                    schema = if (useStructuredOutput) LLMParams.Schema.JSON.Basic("AdvancedBalloonsResponse", balloonSchema) else null
                                )
                            ) {
                                user {
                                    text(effectivePromptInstructions)
                                    image(Path(file.absolutePath))
                                }
                            }

                            val (balloonsResponse, rawResponse) = retryWithBackoff {
                                val responses = executor.execute(ocrPrompt, model)
                                var originalText = ""
                                for (res in responses) {
                                    if (res != null) {
                                        originalText += "${res.content}\n"
                                    }
                                }
                                originalText = originalText.trim()
                                val rawText = originalText.replace(Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "").trim()

                                val jsonToParse = if (!useStructuredOutput) extractJsonFromText(rawText) else rawText
                                val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<AdvancedBalloonsResponse>(jsonToParse)
                                val finalRawResponse = if (saveThinking) originalText else rawText
                                Pair(parsed, finalRawResponse)
                            }

                            val (imgWidth, imgHeight) = getImageDimensions(file)

                            resultsMutex.withLock {
                                balloonsResponse.balloons.forEach { balloon ->
                                    allTexts.add(balloon.text)
                                    allBalloonBoxes.add(scaleBoxToPixels(balloon.balloon_box_2d, imgWidth, imgHeight))
                                    allTextBoxes.add(scaleBoxToPixels(balloon.text_box_2d, imgWidth, imgHeight))
                                    allShapes.add(balloon.shape)
                                    allFontStyles.add(balloon.fontStyle)
                                    allFontFamilies.add(balloon.fontFamily)
                                    allAngles.add(balloon.textAngle)
                                    allIsSparse.add(balloon.isSparse)
                                    allTextColors.add(balloon.textColor)
                                    allHasBorder.add(balloon.hasBorder)
                                    allBorderColors.add(balloon.borderColor)
                                    allPageNumbers.add(index + 1)
                                    allPageNames.add(file.name)
                                }
                                processedFilesCount++
                                progressReporter.report(processedFilesCount.toFloat() / total.toFloat())
                            }
                            saveResult(save, outputDir, file, rawResponse)
                        } catch (e: Throwable) {
                            handleError(e, file, save, outputDir, resultsMutex, failedFiles, processedFilesCount, total)
                        }
                    }
                }
            }.awaitAll()
        }
        return AdvancedOcrServiceResult(allTexts, allBalloonBoxes, allTextBoxes, allShapes, allFontStyles, allFontFamilies, allAngles, allIsSparse, allTextColors, allHasBorder, allBorderColors, allPageNumbers, allPageNames, failedFiles)
    }

    private fun extractJsonFromText(text: String): String {
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val match = codeBlockRegex.find(text)
        if (match != null) return match.groupValues[1].trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) return text.substring(start, end + 1).trim()
        return text.trim()
    }

    private fun saveResult(save: Boolean, outputDir: String, file: File, rawResponse: String) {
        if (!save) return
        val outDir = if (outputDir.isNotBlank()) File(outputDir) else file.parentFile ?: File(".")
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "${file.nameWithoutExtension}_OCR.json")
        outFile.writeText(rawResponse, Charsets.UTF_8)
        logger.info("Saved OCR JSON result to: ${outFile.absolutePath}")
    }

    private suspend fun handleError(e: Throwable, file: File, save: Boolean, outputDir: String, mutex: Mutex, failedFiles: MutableList<String>, processedFilesCount: Int, total: Int) {
        logger.error("Error processing '${file.name}': ${e::class.simpleName}: ${e.message}")
        mutex.withLock {
            failedFiles.add(file.name)
            progressReporter.report((processedFilesCount + 1).toFloat() / total.toFloat())
        }
        if (save) {
            val outDir = if (outputDir.isNotBlank()) File(outputDir) else file.parentFile ?: File(".")
            if (!outDir.exists()) outDir.mkdirs()
            val errorFile = File(outDir, "${file.name}_ERROR.txt")
            errorFile.writeText("Error processing '${file.name}':\n${e::class.simpleName}: ${e.message}\n\n${e.stackTraceToString()}", Charsets.UTF_8)
            logger.info("Saved error details to: ${errorFile.absolutePath}")
        }
    }
}
