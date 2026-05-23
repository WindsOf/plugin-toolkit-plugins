package com.wip.ocrAI

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
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

@Serializable
data class Balloon(
    val xmin: Double,
    val ymin: Double,
    val xmax: Double,
    val ymax: Double,
    val text: String
)

@Serializable
data class BalloonsResponse(
    val balloons: List<Balloon>
)

data class OcrServiceResult(
    val texts: List<String>,
    val bb: List<List<Double>>,
    val pageNumbers: List<Int>,
    val pageNames: List<String>,
    val failedFiles: List<String>
)

/**
 * Kotlin-native OCR service using Koog + Google Gemma-4-31b-it.
 * Handles single image files or entire directories.
 */
class KoogOcrService(private val context: PluginContext) {
    private val logger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

    private suspend fun <T> retryWithBackoff(
        block: suspend () -> T
    ): T {
        val delaysMs = listOf(5000L, 10000L, 10000L, 10000L, 120000L) // 5s, 10s, 10s, 10s, 2m
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

    suspend fun performOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        apiKey: String,
        useStructuredOutput: Boolean,
        modelId: String
    ): OcrServiceResult {
        // ── Resolve files ──────────────────────────────────────────────
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
                    return OcrServiceResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
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
                return OcrServiceResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
        }

        if (files.isEmpty()) {
            logger.warn("No supported images found at '$input'.")
            return OcrServiceResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        logger.info("Found ${files.size} image(s) to process.")

        // ── API Key ────────────────────────────────────────────────────
        logger.info("Resolving API key...")
        val effectiveApiKey = try {
            apiKey.ifBlank { System.getenv("API_KEY") ?: "" }
        } catch (e: Exception) {
            logger.error("Error accessing environment variables: ${e.message}")
            throw e
        }

        if (effectiveApiKey.isBlank()) {
            val msg = "API Key not found. Pass it via 'apiKey' parameter or set the API_KEY environment variable."
            logger.error(msg)
            throw IllegalArgumentException(msg)
        }
        logger.info("API key resolved (length: ${effectiveApiKey.length}).")

        // ── Executor + Model ───────────────────────────────────────────
        logger.info("Initializing Koog executor (simpleGoogleAIExecutor)...")
        val executor = try {
            simpleGoogleAIExecutor(effectiveApiKey)
        } catch (e: Throwable) {
            logger.error("Failed to initialize executor: ${e::class.simpleName}: ${e.message}")
            if (e is NoClassDefFoundError || e is ClassNotFoundException) {
                logger.error("Missing dependency: ${e.message}. Ensure all required libraries are included in the plugin JAR.")
            }
            throw e
        }
        logger.info("Executor initialized.")

        logger.info("Defining LLModel: $modelId")
        val model = LLModel(
            provider = LLMProvider.Google,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Vision.Image,
            )
        )
        logger.info("Model definition complete: ${model.id}")

        // ── Prompt ─────────────────────────────────────────────────────
        val promptInstructions =
            "Analyze this comic panel. Locate ALL areas containing text (speech bubbles, captions, etc.). " +
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
                        putJsonArray("required") {
                            add("xmin")
                            add("ymin")
                            add("xmax")
                            add("ymax")
                            add("text")
                        }
                    }
                }
            }
            putJsonArray("required") {
                add("balloons")
            }
        }

        val effectivePromptInstructions = if (useStructuredOutput) {
            promptInstructions
        } else {
            promptInstructions + "\n\nIMPORTANT: Your output MUST be a valid JSON object matching the following schema:\n" + balloonSchema.toString()
        }

        // ── Process each image ─────────────────────────────────────────
        val allTexts = mutableListOf<String>()
        val allBoxes = mutableListOf<List<Double>>()
        val allPageNumbers = mutableListOf<Int>()
        val allPageNames = mutableListOf<String>()
        val failedFiles = mutableListOf<String>()
        val total = files.size

        logger.info("Starting processing loop for $total image(s)...")

        val resultsMutex = Mutex()
        var processedFilesCount = 0

        val semaphore = Semaphore(5) // Max 5 parallel requests
        val requestTimestamps = ArrayDeque<Long>()
        val rateLimitMutex = Mutex()

        suspend fun acquireRateLimit() {
            rateLimitMutex.withLock {
                val now = System.currentTimeMillis()
                if (requestTimestamps.size >= 13) {
                    val oldest = requestTimestamps.first()
                    val timeSinceOldest = now - oldest
                    if (timeSinceOldest < 60000) {
                        val waitTime = 60000 - timeSinceOldest
                        logger.info("Rate limit approached (13 requests in 1 min). Waiting for ${waitTime}ms...")
                        delay(waitTime)
                    }
                    requestTimestamps.removeFirst()
                }
                // Record the actual time after any delay
                requestTimestamps.addLast(System.currentTimeMillis())
            }
        }

        coroutineScope {
            files.mapIndexed { index, file ->
                async {
                    if (isCancelled) {
                        logger.info("Processing stopped at image ${index + 1}/$total.")
                        return@async
                    }

                    semaphore.withPermit {
                        if (isCancelled) return@async

                        logger.info("Processing image ${index + 1}/$total: '${file.name}'")

                        try {
                            val ocrPrompt = prompt(
                                id = "ocr-task",
                                params = LLMParams(
                                    schema = if (useStructuredOutput) {
                                        LLMParams.Schema.JSON.Basic(
                                            name = "BalloonsResponse",
                                            schema = balloonSchema
                                        )
                                    } else null
                                )
                            ) {
                                user {
                                    text(effectivePromptInstructions)
                                    image(Path(file.absolutePath))
                                }
                            }

                            acquireRateLimit()

                            logger.debug("Sending request to Google AI...")
                            val responses = retryWithBackoff {
                                executor.execute(ocrPrompt, model)
                            }
                            logger.debug("Response received. Parts: ${responses.size}")

                            var rawResponse = responses.joinToString("\n") { it.content }.trim()

                            // Strip thinking blocks
                            rawResponse = rawResponse.replace(
                                Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
                            ).trim()

                            // Robust JSON extraction if not using structured output
                            val jsonToParse = if (!useStructuredOutput) {
                                extractJsonFromText(rawResponse)
                            } else {
                                rawResponse
                            }

                            // Attempt to parse JSON
                            val json = Json { ignoreUnknownKeys = true }
                            val balloonsResponse = json.decodeFromString<BalloonsResponse>(jsonToParse)

                            resultsMutex.withLock {
                                balloonsResponse.balloons.forEach { balloon ->
                                    allTexts.add(balloon.text)
                                    allBoxes.add(listOf(balloon.xmin, balloon.ymin, balloon.xmax, balloon.ymax))
                                    allPageNumbers.add(index + 1)
                                    allPageNames.add(file.name)
                                }
                                logger.info("Extracted ${balloonsResponse.balloons.size} balloons from '${file.name}'.")

                                processedFilesCount++
                                progressReporter.report(processedFilesCount.toFloat() / total.toFloat())
                            }

                            if (save) {
                                val outDir = if (outputDir.isNotBlank()) {
                                    File(outputDir)
                                } else {
                                    file.parentFile ?: File(".")
                                }
                                if (!outDir.exists()) {
                                    outDir.mkdirs()
                                    logger.info("Created output directory: ${outDir.absolutePath}")
                                }
                                val outFile = File(outDir, "${file.nameWithoutExtension}_OCR.json")
                                outFile.writeText(rawResponse, Charsets.UTF_8)
                                logger.info("Saved OCR JSON result to: ${outFile.absolutePath}")
                            }

                        } catch (e: Throwable) {
                            logger.error("Error processing '${file.name}': ${e::class.simpleName}: ${e.message}")
                            
                            resultsMutex.withLock {
                                failedFiles.add(file.name)
                                processedFilesCount++
                                progressReporter.report(processedFilesCount.toFloat() / total.toFloat())
                            }

                            if (save) {
                                val outDir = if (outputDir.isNotBlank()) {
                                    File(outputDir)
                                } else {
                                    file.parentFile ?: File(".")
                                }
                                if (!outDir.exists()) {
                                    outDir.mkdirs()
                                }
                                val errorFile = File(outDir, "${file.name}_ERROR.txt")
                                errorFile.writeText("Error processing '${file.name}':\n${e::class.simpleName}: ${e.message}\n\n${e.stackTraceToString()}", Charsets.UTF_8)
                                logger.info("Saved error details to: ${errorFile.absolutePath}")
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val status = if (isCancelled) "cancelled" else "completed"
        logger.info("OCR $status. Processed ${files.size} image(s). Total balloons: ${allTexts.size} | Failures: ${failedFiles.size}")
        return OcrServiceResult(allTexts, allBoxes, allPageNumbers, allPageNames, failedFiles)
    }

    /**
     * Extracts a JSON block from potentially messy LLM response.
     * Looks for text between ```json and ```, or the first { and last }.
     */
    private fun extractJsonFromText(text: String): String {
        // Try to find markdown code block
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val match = codeBlockRegex.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // Fallback: Find first '{' and last '}'
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1).trim()
        }

        return text.trim()
    }
}
