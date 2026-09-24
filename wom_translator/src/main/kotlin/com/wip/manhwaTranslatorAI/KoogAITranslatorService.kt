package com.wip.manhwaTranslatorAI

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.wip.common.inference.deepseek.DeepSeekManager
import com.wip.common.inference.llm.ReasoningEffortLevel
import com.wip.common.inference.lmstudio.LmStudioManager
import com.wip.common.inference.retry.retryWithBackoff as commonRetryWithBackoff
import com.wip.common.models.OcrTextFilter
import com.wip.common.models.sortedNaturally
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginSignal
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@Serializable
data class TranslationResponse(
    val translations: List<String>
)

@Serializable
data class DictionaryUpdateResult(
    val changelog: String,
    val updatedDictionary: String,
    val savedFilePath: String? = null,
    val changelogFilePath: String? = null
)


/**
 * Kotlin-native Translator service using Koog + Google AI or DeepSeek.
 * Translates a list of strings to Italian following dictionary guidelines.
 */
class KoogAITranslatorService(
    private val context: PluginContext,
    private val settings: TranslatorAISettings,
    private val hostFs: HostFileSystem
) {
    private val logger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

    private fun getProvider(modelId: String): LLMProvider {
        return when (modelId) {
            AIModel.GEMMA_26B.id, AIModel.GEMMA_31B.id,
            AIModel.GEMINI_3_5_FLASH.id, AIModel.GEMINI_3_6_FLASH.id,
            AIModel.GEMINI_3_7_FLASH.id, AIModel.GEMINI_3_8_FLASH.id,
            AIModel.GEMINI_3_1_FLASH_LITE.id -> LLMProvider.Google
            AIModel.LM_STUDIO.id,
            AIModel.DEEPSEEK_FLASH.id, AIModel.DEEPSEEK_PRO.id,
            "deepseek-flash", "deepseek-v4-pro" -> LLMProvider.OpenAI
            else -> LLMProvider.Google
        }
    }

    private fun createKoogHttpClient(): HttpClient {
        return HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = (10.minutes).inWholeMilliseconds
                connectTimeoutMillis = (10.minutes).inWholeMilliseconds
                socketTimeoutMillis = (10.minutes).inWholeMilliseconds
            }
        }
    }

    private fun getExecutor(modelId: String) = when (modelId) {
        AIModel.DEEPSEEK_FLASH.id, AIModel.DEEPSEEK_PRO.id,
        "deepseek-flash", "deepseek-v4-pro" -> {
            val key = (settings.deepseekApiKey ?: "").ifBlank { System.getenv("DEEPSEEK_API_KEY") ?: "" }
            if (key.isBlank()) throw IllegalArgumentException("DeepSeek API Key not found. Please set deepseekApiKey in settings.")
            val baseUrl = (settings.deepseekBaseUrl ?: DeepSeekManager.DEFAULT_BASE_URL).ifBlank { DeepSeekManager.DEFAULT_BASE_URL }.trim()
            val wrapperClient = DeepSeekManager.Default.createKoogClient(
                apiKey = key,
                baseUrl = baseUrl,
                baseHttpClient = createKoogHttpClient()
            )
            MultiLLMPromptExecutor(wrapperClient)
        }

        AIModel.LM_STUDIO.id -> {
            val key = settings.lmStudioApiKey?.ifBlank { "lm-studio" } ?: "lm-studio"
            val baseUrl =
                (settings.lmStudioUrl ?: "http://localhost:1234/v1").ifBlank { "http://localhost:1234/v1" }.trim()
                    .removeSuffix("/")
            val wrapperClient = LmStudioManager.Default.createKoogClient(
                baseUrl = baseUrl,
                apiKey = key,
                baseHttpClient = createKoogHttpClient()
            )
            MultiLLMPromptExecutor(wrapperClient)
        }

        else -> {
            val key = (settings.googleApiKey ?: "").ifBlank { System.getenv("API_KEY") ?: "" }
            if (key.isBlank()) throw IllegalArgumentException("Google API Key not found.")
            MultiLLMPromptExecutor(GoogleLLMClient(apiKey = key, baseClient = createKoogHttpClient()))
        }
    }

    private val requestTimes = ConcurrentLinkedQueue<Long>()
    private val concurrentSemaphore = Semaphore(5)

    private suspend fun acquireRateLimit() {
        val maxRequestsPerMinute = 13
        val windowMs = 60_000L

        while (true) {
            val now = System.currentTimeMillis()
            while (requestTimes.peek()?.let { now - it > windowMs } == true) {
                requestTimes.poll()
            }
            if (requestTimes.size < maxRequestsPerMinute) {
                requestTimes.add(now)
                break
            }
            val oldest = requestTimes.peek() ?: now
            val waitTime = windowMs - (now - oldest)
            if (waitTime > 0) {
                delay((waitTime + 100).milliseconds)
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        block: suspend () -> T
    ): T = commonRetryWithBackoff(
        logger = logger,
        delaysMs = listOf(5000L, 10000L, 10000L, 10000L, 15000L),
        isCancelled = { isCancelled },
        block = block
    )

    init {
        context.signals.onSignal { signal ->
            if (signal == PluginSignal.CANCEL) {
                isCancelled = true
                logger.info("Cancellation signal received.")
            }
        }
    }

    private data class TextEntry(val index: Int, val text: String, val pageName: String)

    suspend fun performTranslation(
        input: List<String>,
        dictionary: String,
        apiKey: String,
        useStructuredOutput: Boolean = true,
        modelId: String,
        pageNames: List<String>? = null,
        inputFolder: String?,
        outputDir: String,
        tempSummaryDir: String,
        useContextImages: Boolean = false,
        generateChapterSummary: Boolean = true,
        save: Boolean = true,
        enableThinking: Boolean = true,
        reasoningEffort: ReasoningEffortLevel = ReasoningEffortLevel.DEFAULT,
        debugLogging: Boolean = false
    ): List<String> {
        if (input.isEmpty()) return emptyList()

        // ── API Key ────────────────────────────────────────────────────
        val isDeepSeek = modelId == AIModel.DEEPSEEK_FLASH.id || modelId == AIModel.DEEPSEEK_PRO.id
            || modelId == "deepseek-flash" || modelId == "deepseek-v4-pro"
        val effectiveApiKey = if (isDeepSeek) {
            settings.deepseekApiKey?.trim().orEmpty().ifBlank { System.getenv("DEEPSEEK_API_KEY")?.trim() ?: "" }
        } else {
            apiKey.trim().ifBlank { System.getenv("API_KEY")?.trim() ?: "" }
        }

        if (effectiveApiKey.isBlank() && modelId != AIModel.LM_STUDIO.id) {
            val keyName = if (isDeepSeek) "DeepSeek API Key (deepseekApiKey)" else "Google API Key (googleApiKey)"
            val msg = "$keyName not found. Pass it via settings or environment variable."
            logger.error(msg)
            throw IllegalArgumentException(msg)
        }

        // ── Dictionary Handling ─────────────────────────────────────────
        val dictionaryContent = try {
            val file = File(dictionary)
            if (file.exists() && file.isFile) file.readText() else dictionary
        } catch (e: Exception) {
            dictionary
        }

        // ── Chunking & Concurrency ──────────────────────────────────────
        val results = arrayOfNulls<String>(input.size)

        // deepseek-v4-pro does not support vision; deepseek-flash supports native vision
        val isVisionSupported = modelId != AIModel.DEEPSEEK_PRO.id && modelId != "deepseek-v4-pro"
        if (!isVisionSupported && useContextImages) {
            logger.warn("Model $modelId does not support vision/context images. Falling back to text-only mode.")
        }

        val isContextModeValid =
            isVisionSupported && useContextImages && pageNames != null && inputFolder != null && pageNames.size == input.size

        if (useContextImages && !isContextModeValid && isVisionSupported) {
            logger.warn("Context Images enabled but missing/invalid inputs (pageNames or inputFolder mismatch). Falling back to text-only mode.")
        }

        var globalContext: String? = null
        if (isVisionSupported && generateChapterSummary && !inputFolder.isNullOrBlank()) {
            val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")
            val allImages = if (pageNames != null && pageNames.isNotEmpty()) {
                pageNames.distinct().map { File(inputFolder, it) }.filter { it.exists() }
            } else {
                val folder = File(inputFolder)
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles { f -> f.isFile && f.extension.lowercase() in imageExtensions }
                        ?.sortedNaturally()
                        ?.toList() ?: emptyList()
                } else {
                    emptyList()
                }
            }

            if (allImages.isNotEmpty()) {
                logger.info("Preparing ${allImages.size} images for global context summary (resizing and compressing)...")
                val tempDir = File(tempSummaryDir)
                if (!tempDir.exists()) tempDir.mkdirs()

                try {
                    val processedImages = allImages.mapIndexed { idx, img ->
                        if (tempDir != null) {
                            val targetFile = File(tempDir, "summary_page_${idx}.jpg")
                            resizeAndCompressImage(img, targetFile)
                        } else {
                            img
                        }
                    }

                    globalContext = generateChapterContext(processedImages, effectiveApiKey)
                } finally {
                    // Cleanup temp directory
                    try {
                        tempDir.deleteRecursively()
                        logger.info("Temporary directory for summary images deleted successfully.")
                    } catch (e: Exception) {
                        logger.warn("Failed to delete temp directory: ${e.message}")
                    }
                }
            }
        }

        coroutineScope {
            val effectiveChunkSize = (settings.chunkSize ?: 25).coerceIn(5, 50)

            // Identify non-text / hallucination entries to preserve exact 1:1 list length
            // without sending garbage or infinite repetition loops to the translating LLM.
            val isHallucination = BooleanArray(input.size) { i ->
                OcrTextFilter.isHallucinationOrEmpty(input[i])
            }
            var hallucinationCount = 0
            for (i in input.indices) {
                if (isHallucination[i]) {
                    results[i] = ""
                    hallucinationCount++
                }
            }
            if (hallucinationCount > 0) {
                logger.info("Pre-filtered $hallucinationCount non-text/hallucinated items from translation input (retained as blank in 1:1 result).")
            }

            if (isContextModeValid) {
                // Image Context Mode
                val validEntries = input.mapIndexedNotNull { index, text ->
                    if (isHallucination[index]) null else TextEntry(index, text, pageNames[index])
                }
                if (validEntries.isEmpty()) {
                    logger.info("Context mode: No valid texts to translate after filtering non-text/hallucinations.")
                    return@coroutineScope
                }
                val groupedByPage = validEntries.groupBy { it.pageName }
                val uniquePages = groupedByPage.keys.toList()

                val pageChunks = mutableListOf<List<TextEntry>>()
                var currentChunk = mutableListOf<TextEntry>()
                var currentImagesCount = 0

                for (page in uniquePages) {
                    val pageEntries = groupedByPage[page] ?: emptyList()

                    // Flush if adding this page would exceed 5 images OR effectiveChunkSize texts (and the chunk is already not empty)
                    if (currentChunk.isNotEmpty() && (currentImagesCount + 1 > 5 || currentChunk.size + pageEntries.size > effectiveChunkSize)) {
                        pageChunks.add(currentChunk)
                        currentChunk = mutableListOf()
                        currentImagesCount = 0
                    }

                    currentChunk.addAll(pageEntries)
                    currentImagesCount++
                }
                if (currentChunk.isNotEmpty()) {
                    pageChunks.add(currentChunk)
                }

                logger.info("Context mode active. Split ${validEntries.size} valid texts into ${pageChunks.size} chunks (max $effectiveChunkSize texts/5 images).")

                val deferreds = pageChunks.mapIndexed { index, chunkEntries ->
                    async {
                        concurrentSemaphore.withPermit {
                            acquireRateLimit()
                            val chunkTexts = chunkEntries.map { it.text }
                            val uniqueChunkPages = chunkEntries.map { it.pageName }.distinct()
                            val images = uniqueChunkPages.map { File(inputFolder, it) }.filter { it.exists() }

                            val translations = translateChunkWithRetry(
                                chunkTexts,
                                images,
                                dictionaryContent,
                                effectiveApiKey,
                                useStructuredOutput,
                                modelId,
                                index,
                                globalContext,
                                enableThinking,
                                reasoningEffort,
                                debugLogging
                            )

                            // Place translations in correct original indices
                            chunkEntries.forEachIndexed { i, entry ->
                                val t = translations.getOrNull(i)
                                results[entry.index] = when {
                                    t == null -> "[Translation Missing]"
                                    OcrTextFilter.isHallucinationOrEmpty(t) -> ""
                                    else -> t
                                }
                            }
                        }
                    }
                }
                deferreds.awaitAll()
            } else {
                // Text-only mode (Classic)
                val validEntries = input.mapIndexedNotNull { index, text ->
                    if (isHallucination[index]) null else Pair(index, text)
                }
                if (validEntries.isEmpty()) {
                    logger.info("Text mode: No valid texts to translate after filtering non-text/hallucinations.")
                    return@coroutineScope
                }
                val chunks = validEntries.chunked(effectiveChunkSize)
                logger.info("Text mode. Split ${validEntries.size} valid texts (filtered from ${input.size}) into ${chunks.size} chunks of max $effectiveChunkSize items.")

                val deferreds = chunks.mapIndexed { index, chunk ->
                    async {
                        concurrentSemaphore.withPermit {
                            acquireRateLimit()
                            val chunkTexts = chunk.map { it.second }
                            val translations = translateChunkWithRetry(
                                chunkTexts,
                                null,
                                dictionaryContent,
                                effectiveApiKey,
                                useStructuredOutput,
                                modelId,
                                index,
                                globalContext,
                                enableThinking,
                                reasoningEffort,
                                debugLogging
                            )
                            chunk.forEachIndexed { i, (originalIndex, _) ->
                                val t = translations.getOrNull(i)
                                results[originalIndex] = when {
                                    t == null -> "[Translation Missing]"
                                    OcrTextFilter.isHallucinationOrEmpty(t) -> ""
                                    else -> t
                                }
                            }
                        }
                    }
                }
                deferreds.awaitAll()
            }
        }

        val finalTranslations = results.map { it ?: "" }

        if (save) {
            try {
                val outDir = File(outputDir)
                hostFs.createDirectory(outDir.absolutePath)
                val outFile = File(outDir, "translation_result.json")
                val json = Json { prettyPrint = true }
                hostFs.writeTextFile(outFile.absolutePath, json.encodeToString(TranslationResponse(finalTranslations)))
                logger.info("Saved translation result to: ${outFile.absolutePath}")

                // Also save human-readable single translation file
                val readableFile = File(outDir, "translations.txt")
                val formatted = formatTranslations(
                    translations = finalTranslations,
                    originalTexts = input,
                    pageNames = pageNames,
                    format = "txt"
                )
                hostFs.writeTextFile(readableFile.absolutePath, formatted)
                logger.info("Saved human-readable translations to: ${readableFile.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to save translation result: ${e.message}")
            }
        }

        return finalTranslations
    }

    private suspend fun translateChunkWithRetry(
        chunk: List<String>,
        images: List<File>?,
        dictionary: String,
        apiKey: String,
        useStructuredOutput: Boolean,
        modelId: String,
        chunkIndex: Int,
        chapterContext: String?,
        enableThinking: Boolean = true,
        reasoningEffort: ReasoningEffortLevel = ReasoningEffortLevel.DEFAULT,
        debugLogging: Boolean = false
    ): List<String> {
        val executor = getExecutor(modelId)
        val finalModelId = if (modelId == AIModel.LM_STUDIO.id) {
            val baseUrl = (settings.lmStudioUrl ?: "http://localhost:1234/v1").ifBlank { "http://localhost:1234/v1" }
            LmStudioManager.Default.resolveModelName(
                baseUrl = baseUrl,
                configuredModel = settings.lmStudioModelName,
                apiKey = settings.lmStudioApiKey,
                logger = logger
            )
        } else modelId

        val model = LLModel(
            provider = getProvider(modelId),
            id = finalModelId,
            capabilities = buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.Temperature)
                if (getProvider(modelId) == LLMProvider.OpenAI) {
                    add(LLMCapability.OpenAIEndpoint.Completions)
                }
                if (useStructuredOutput) {
                    add(LLMCapability.Schema.JSON.Basic)
                }
                if (enableThinking) {
                    add(LLMCapability.Thinking)
                }
                if (!images.isNullOrEmpty()) add(LLMCapability.Vision.Image)
            },
            contextLength = 100000,
        )

        val dictionaryInstructions = if (dictionary.isNotEmpty()) {
            "ADHERE STRICTLY TO THESE DICTIONARY GUIDELINES:\n$dictionary"
        } else {
            "No specific dictionary guidelines provided."
        }

        val jsonFormatRequirement = """
            
            FORMAT REQUIREMENT:
            You MUST return a JSON object with the following structure:
            {
              "translations": [
                "translated string 1",
                "translated string 2"
              ]
            }
            Do not include any other text, explanations, or markdown formatting outside of the JSON block.
            Do not use unescaped double quotes inside translated strings.
            
            CRITICAL RULES:
            1. The "translations" array MUST contain EXACTLY ${chunk.size} string elements, matching the input order 1-to-1.
            2. Do not omit any elements or use summary placeholders like "..." or [...] for real dialogue. Every input string must have a corresponding slot in "translations". For valid dialogue, provide the full translated text; for detected OCR hallucinations or degenerate repeating loops, shrink the entry to an empty string ("") so that the slot is preserved without generating massive garbage or breaking output length.
        """.trimIndent()

        val imageContextRule = if (!images.isNullOrEmpty()) {
            "\nContext Images are provided. Use them to understand the scene, characters, and tone, but DO NOT transcribe them. Only translate the strings provided."
        } else ""

        val globalContextRule = if (!chapterContext.isNullOrBlank()) {
            "\n\nGLOBAL CHAPTER CONTEXT:\n$chapterContext\n\nUse this context to maintain consistency in names, tone, and story events."
        } else ""

        val promptInstructions = """
            You are a professional Manhwa/Manga translator. 
            Translate the following list of strings into natural, expressive Italian.$imageContextRule$globalContextRule
            
            GUIDELINES:
            1. Maintain the exact same order as the input list.
            2. Preserve the tone and context of a comic.
            3. $dictionaryInstructions
            4. If a string is a sound effect (SFX), translate it if appropriate for Italian comics or leave it in English/Korean as per standard scanlation practices.
            5. OCR ARTIFACTS, NON-TEXT & REPETITION LOOPS:
               - The input strings originate from raw OCR and may occasionally contain OCR artifacts, non-text indicators (e.g., "[Non-Text]", "[image]"), unreadable noise, watermarks, or degenerate repetition loops (e.g., repeating digits like "1.1.1.1.1...", repeated punctuation, or nonsensical repeating character loops).
               - DO NOT attempt to translate or replicate large repetitive garbage strings, as doing so will exceed output token limits and break generation.
               - DO NOT omit the item or skip the slot, as the application requires an exact 1:1 element count and will discard the response if the array size does not match.
               - INSTEAD, SHRINK THE OUTPUT: For any string you identify as an OCR artifact, non-text marker, or degenerate repetition loop, return an empty string "" at that exact position in the "translations" array.
            6. Return ONLY the JSON object containing the list of translated strings.
            
            $jsonFormatRequirement
            
            CRITICAL REQUIREMENT: You MUST return EXACTLY ${chunk.size} translations in the output array, maintaining a strict 1:1 mapping with the input.
        """.trimIndent()

        val translationSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("translations") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                    }
                }
            }
            putJsonArray("required") {
                add("translations")
            }
        }

        return retryWithBackoff {
            val koogEffort = reasoningEffort.toKoogOpenAIEffort()
            val llmParams = if (useStructuredOutput) {
                if (getProvider(modelId) == LLMProvider.OpenAI) {
                    OpenAIChatParams(
                        schema = LLMParams.Schema.JSON.Basic(
                            name = "TranslationResponse",
                            schema = translationSchema
                        ),
                        reasoningEffort = koogEffort
                    )
                } else {
                    LLMParams(
                        schema = LLMParams.Schema.JSON.Basic(
                            name = "TranslationResponse",
                            schema = translationSchema
                        )
                    )
                }
            } else {
                if (getProvider(modelId) == LLMProvider.OpenAI) {
                    OpenAIChatParams(reasoningEffort = koogEffort)
                } else {
                    LLMParams()
                }
            }

            val translatePrompt = prompt(
                id = "translation-task",
                params = llmParams
            ) {
                user {
                    images?.forEach { img ->
                        try {
                            image(kotlinx.io.files.Path(img.absolutePath))
                        } catch (e: Exception) {
                            logger.warn("Could not attach image ${img.name}: ${e.message}")
                        }
                    }
                    text(promptInstructions + "\n\nStrings to translate:\n" + chunk.joinToString("\n") { "[TEXT]: $it" })
                }
            }

            logger.info("Sending translation request for chunk $chunkIndex (${chunk.size} strings)...")
            val responses = executor.execute(translatePrompt, model)

            var rawResponse = responses.joinToString("\n") { it.content }.trim()

            if (debugLogging || settings.debugLogging == true) {
                logger.info("[DEBUG] Chunk $chunkIndex raw LLM response:\n$rawResponse")
            }

            // Strip thinking blocks if present
            rawResponse = rawResponse.replace(
                Regex("<(thought|thinking|think)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
            ).trim()

            val translations = try {
                parseTranslationsResponse(rawResponse, chunk.size)
            } catch (e: Throwable) {
                if (!debugLogging && settings.debugLogging != true) {
                    logger.warn("[DEBUG] Chunk $chunkIndex raw response that caused parsing error:\n$rawResponse")
                }
                throw e
            }

            logger.info("Translation for chunk $chunkIndex completed successfully.")
            translations
        }
    }

    internal fun parseTranslationsResponse(raw: String, expectedCount: Int): List<String> {
        val jsonToParse = extractJson(raw)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val element = json.parseToJsonElement(jsonToParse)
        val translationsList = when (element) {
            is JsonObject -> {
                val array = element["translations"] as? JsonArray
                    ?: element["translation"] as? JsonArray
                    ?: element["results"] as? JsonArray
                    ?: element["result"] as? JsonArray
                    ?: element["dialogues"] as? JsonArray
                    ?: element["texts"] as? JsonArray
                    ?: element.values.firstOrNull { it is JsonArray } as? JsonArray
                    ?: throw IllegalArgumentException("No JSON array of translations found in response object: $jsonToParse")
                array.map { extractStringFromElement(it) }
            }
            is JsonArray -> {
                element.map { extractStringFromElement(it) }
            }
            else -> throw IllegalArgumentException("Expected JSON object or array, got: $jsonToParse")
        }

        if (translationsList.size != expectedCount) {
            throw IllegalStateException("Mismatch in chunk: Expected $expectedCount translations, got ${translationsList.size}")
        }

        return translationsList
    }

    private fun extractStringFromElement(elem: JsonElement): String {
        return when (elem) {
            is JsonNull -> ""
            is JsonPrimitive -> if (elem.content == "null") "" else elem.content
            is JsonObject -> {
                elem["translation"]?.jsonPrimitive?.content
                    ?: elem["text"]?.jsonPrimitive?.content
                    ?: elem.values.firstOrNull()?.jsonPrimitive?.content
                    ?: elem.toString()
            }
            else -> elem.toString()
        }
    }

    internal fun extractJson(raw: String): String {
        // Strip thinking/thought tags first if still lingering
        val cleaned = raw.replace(
            Regex("<(thought|thinking|think)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
        ).trim()

        // 1. If wrapped in markdown code fence(s), prefer the fence containing translation JSON
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val allFences = fenceRegex.findAll(cleaned).map { it.groupValues[1].trim() }.toList()
        val textToSearch = allFences.firstOrNull { fence ->
            fence.contains("\"translations\"") || fence.contains("\"translation\"") ||
            fence.contains("\"results\"") || fence.contains("\"result\"") ||
            fence.startsWith("{")
        } ?: allFences.firstOrNull() ?: cleaned

        // 2. Identify start position: prioritize finding the root '{' of the translations object
        val knownKeys = listOf("\"translations\"", "\"translation\"", "\"results\"", "\"result\"", "\"dialogues\"", "\"texts\"")
        val keyIndex = knownKeys.map { textToSearch.indexOf(it) }.filter { it != -1 }.minOrNull()

        val start = if (keyIndex != null) {
            val braceBeforeKey = textToSearch.lastIndexOf('{', keyIndex)
            if (braceBeforeKey != -1) braceBeforeKey else textToSearch.indexOf('{')
        } else {
            val firstBrace = textToSearch.indexOf('{')
            if (firstBrace != -1) firstBrace else textToSearch.indexOf('[')
        }

        if (start != -1) {
            val openChar = textToSearch[start]
            val closeChar = if (openChar == '{') '}' else ']'
            var depth = 0
            var inString = false
            var escape = false

            for (i in start until textToSearch.length) {
                val c = textToSearch[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == openChar) depth++
                    else if (c == closeChar) {
                        depth--
                        if (depth == 0) {
                            return textToSearch.substring(start, i + 1)
                        }
                    }
                }
            }

            // Fallback if bracket balancing was interrupted
            val lastEnd = textToSearch.lastIndexOf(closeChar)
            if (lastEnd > start) {
                return textToSearch.substring(start, lastEnd + 1)
            }
        }

        // 3. Fallback search on original cleaned string if fenced search yielded nothing
        if (textToSearch !== cleaned) {
            val fallbackKeyIndex = knownKeys.map { cleaned.indexOf(it) }.filter { it != -1 }.minOrNull()
            val rawStart = if (fallbackKeyIndex != null) {
                val braceBeforeKey = cleaned.lastIndexOf('{', fallbackKeyIndex)
                if (braceBeforeKey != -1) braceBeforeKey else cleaned.indexOf('{')
            } else {
                val firstBrace = cleaned.indexOf('{')
                if (firstBrace != -1) firstBrace else cleaned.indexOf('[')
            }

            if (rawStart != -1) {
                val closeChar = if (cleaned[rawStart] == '{') '}' else ']'
                val rawEnd = cleaned.lastIndexOf(closeChar)
                if (rawEnd > rawStart) {
                    return cleaned.substring(rawStart, rawEnd + 1)
                }
            }
        }

        return cleaned
    }

    private suspend fun generateChapterContext(images: List<File>, apiKey: String): String? {
        logger.info("Generating global chapter context using gemini-3.1-flash-lite with ${images.size} images...")
        return try {
            retryWithBackoff {
                val executor = getExecutor("gemini-3.1-flash-lite")
                val model = LLModel(
                    provider = LLMProvider.Google,
                    id = "gemini-3.1-flash-lite",
                    capabilities = buildList {
                        add(LLMCapability.Completion)
                        add(LLMCapability.Vision.Image)
                    },
                    contextLength = 2000000,
                )

                val summaryPrompt = prompt(id = "chapter-summary") {
                    user {
                        images.forEach { img ->
                            try {
                                image(kotlinx.io.files.Path(img.absolutePath))
                            } catch (e: Exception) {
                                logger.warn("Could not attach image ${img.name} for summary: ${e.message}")
                            }
                        }
                        text("You are an expert Manhwa/Manga translator. Read these images from the current chapter and write a concise but comprehensive summary in English of the story, main characters, and key elements present. This summary will be used as context for translating the dialogues.")
                    }
                }

                val responses = executor.execute(summaryPrompt, model)
                val summary = responses.joinToString("\n") { it.content }.trim()

                // Strip thinking blocks if present
                val cleanedSummary = summary.replace(
                    Regex("<(thought|thinking|think)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
                ).trim()

                logger.info("Global chapter context generated successfully.")
                cleanedSummary
            }
        } catch (e: Throwable) {
            logger.warn("Failed to generate global chapter context: ${e.message}. Continuing without global context.")
            null
        }
    }

    private fun resizeAndCompressImage(original: File, target: File): File {
        return try {
            val image = ImageIO.read(original) ?: return original
            val newWidth = image.width / 2
            val newHeight = image.height / 2
            if (newWidth <= 0 || newHeight <= 0) return original

            val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
            val g: Graphics2D = resized.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, newWidth, newHeight, null)
            g.dispose()

            val writers = ImageIO.getImageWritersByFormatName("jpg")
            if (!writers.hasNext()) {
                ImageIO.write(resized, "jpg", target)
                return target
            }
            val writer = writers.next()
            FileImageOutputStream(target).use { output ->
                writer.output = output
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = 0.6f
                }
                writer.write(null, IIOImage(resized, null, null), param)
            }
            writer.dispose()
            target
        } catch (e: Throwable) {
            // Fallback to original
            original
        }
    }

    /**
     * Parses the LLM response from the Lore Master prompt into changelog and clean markdown dictionary.
     */
    fun parseDictionaryUpdateResponse(rawResponse: String): DictionaryUpdateResult {
        // Strip thinking blocks if present
        val cleaned = rawResponse.replace(
            Regex("<(thought|thinking|think)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
        ).trim()

        // 1. Separate Changelog and Dictionary sections
        val contextHeaderRegex = Regex(
            "(?i)^#+\\s*(?:NUOVO\\s+FILE\\s+DI\\s+CONTESTO|NUOVO\\s+CONTESTO|NUOVO\\s+DIZIONARIO|FILE\\s+DI\\s+CONTESTO|CONTESTO\\s+AGGIORNATO).*$",
            RegexOption.MULTILINE
        )
        val match = contextHeaderRegex.find(cleaned)

        var changelogPart: String
        var dictionaryPart: String

        if (match != null) {
            changelogPart = cleaned.substring(0, match.range.first).trim()
            dictionaryPart = cleaned.substring(match.range.last + 1).trim()
        } else {
            // Fallback: look for the first 4-block header: "=== LORE & GLOSSARIO FISSO ==="
            val loreIndex = cleaned.indexOf("=== LORE & GLOSSARIO FISSO ===")
            if (loreIndex != -1) {
                changelogPart = cleaned.substring(0, loreIndex).trim()
                dictionaryPart = cleaned.substring(loreIndex).trim()
            } else {
                changelogPart = ""
                dictionaryPart = cleaned
            }
        }

        // Clean up changelog part (remove leading "# CHILOMETRO / MODIFICHE APPORTATE" or similar headers)
        changelogPart = changelogPart
            .replace(
                Regex("(?i)^#+\\s*(?:CHILOMETRO|CHANGELOG|MODIFICHE(?:\\s+APPORTATE)?)[^\r\n]*", RegexOption.MULTILINE),
                ""
            )
            .trim()

        // 2. Extract markdown from dictionaryPart (stripping code fences)
        val fenceRegex = Regex("```(?:markdown|md)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val fenceMatch = fenceRegex.find(dictionaryPart)

        val extractedDict = if (fenceMatch != null) {
            fenceMatch.groupValues[1].trim()
        } else {
            // Strip any dangling opening/closing fences
            dictionaryPart
                .replace(Regex("^```(?:markdown|md)?\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("```\\s*$"), "")
                .trim()
        }

        return DictionaryUpdateResult(
            changelog = changelogPart.ifBlank { "Nessuna variazione specificata" },
            updatedDictionary = extractedDict
        )
    }

    /**
     * Formats translations into a single string (TXT, Markdown, Bilingual, or JSON).
     */
    fun formatTranslations(
        translations: List<String>,
        originalTexts: List<String>? = null,
        pageNames: List<String>? = null,
        pageNumbers: List<Int>? = null,
        format: String = "txt"
    ): String {
        val cleanFormat = format.trim().lowercase()
        val hasPageNames = !pageNames.isNullOrEmpty() && pageNames.size == translations.size
        val hasPageNumbers = !pageNumbers.isNullOrEmpty() && pageNumbers.size == translations.size
        val hasOriginals = !originalTexts.isNullOrEmpty() && originalTexts.size == translations.size

        val originals = if (hasOriginals) originalTexts else null
        val pages = if (hasPageNames) pageNames else null
        val pNums = if (hasPageNumbers) pageNumbers else null

        return when (cleanFormat) {
            "json" -> {
                val json = Json { prettyPrint = true }
                val rootObj = buildJsonObject {
                    put("totalCount", translations.size)
                    putJsonArray("items") {
                        translations.forEachIndexed { i, trans ->
                            add(buildJsonObject {
                                put("index", i)
                                if (originals != null) put("original", originals[i])
                                put("translation", trans)
                                if (pages != null) put("pageName", pages[i])
                                if (pNums != null) put("pageNumber", pNums[i])
                            })
                        }
                    }
                }
                json.encodeToString(rootObj)
            }

            "markdown", "md" -> {
                buildString {
                    appendLine("# Traduzione")
                    appendLine()
                    if (pages != null) {
                        var currentPage = ""
                        translations.indices.forEach { i ->
                            val page = pages[i]
                            if (page != currentPage) {
                                currentPage = page
                                appendLine("## Pagina: $currentPage")
                                appendLine()
                            }
                            if (originals != null && originals[i].isNotBlank()) {
                                appendLine("- **[Originale]**: ${originals[i]}")
                                appendLine("  **[Italiano]**: ${translations[i]}")
                            } else {
                                appendLine("${i + 1}. ${translations[i]}")
                            }
                            appendLine()
                        }
                    } else {
                        translations.indices.forEach { i ->
                            if (originals != null && originals[i].isNotBlank()) {
                                appendLine("- **[Originale]**: ${originals[i]}")
                                appendLine("  **[Italiano]**: ${translations[i]}")
                            } else {
                                appendLine("${i + 1}. ${translations[i]}")
                            }
                            appendLine()
                        }
                    }
                }.trimEnd()
            }

            "bilingual", "bilingual_txt", "bilingual_md" -> {
                buildString {
                    appendLine("========================================")
                    appendLine("TRADUZIONE BILINGUE")
                    appendLine("========================================")
                    appendLine()
                    if (pages != null) {
                        var currentPage = ""
                        translations.indices.forEach { i ->
                            val page = pages[i]
                            if (page != currentPage) {
                                currentPage = page
                                appendLine("----------------------------------------")
                                appendLine("PAGINA: $currentPage")
                                appendLine("----------------------------------------")
                            }
                            val orig = originals?.getOrNull(i) ?: ""
                            appendLine("[${i + 1}] ORIGINAL: $orig")
                            appendLine("    ITALIAN : ${translations[i]}")
                            appendLine()
                        }
                    } else {
                        translations.indices.forEach { i ->
                            val orig = originals?.getOrNull(i) ?: ""
                            appendLine("[${i + 1}] ORIGINAL: $orig")
                            appendLine("    ITALIAN : ${translations[i]}")
                            appendLine()
                        }
                    }
                }.trimEnd()
            }

            else -> { // "txt" or default
                buildString {
                    if (pages != null) {
                        var currentPage = ""
                        translations.indices.forEach { i ->
                            val page = pages[i]
                            if (page != currentPage) {
                                currentPage = page
                                appendLine("========================================")
                                appendLine("PAGINA: $currentPage")
                                appendLine("========================================")
                            }
                            appendLine("[${i + 1}] ${translations[i]}")
                        }
                    } else {
                        translations.indices.forEach { i ->
                            appendLine("[${i + 1}] ${translations[i]}")
                        }
                    }
                }.trimEnd()
            }
        }
    }

    /**
     * Saves translations into a single formatted file on disk.
     */
    suspend fun saveTranslationsToFile(
        translations: List<String>,
        originalTexts: List<String>? = null,
        pageNames: List<String>? = null,
        pageNumbers: List<Int>? = null,
        outputFile: String? = null,
        outputDir: String? = null,
        fileName: String? = "translations.txt",
        format: String? = "txt"
    ): String {
        val targetFile = if (!outputFile.isNullOrBlank()) {
            File(outputFile)
        } else if (!outputDir.isNullOrBlank()) {
            File(outputDir, fileName?.ifBlank { "translations.txt" } ?: "translations.txt")
        } else {
            File(fileName?.ifBlank { "translations.txt" } ?: "translations.txt")
        }

        targetFile.parentFile?.let {
            hostFs.createDirectory(it.absolutePath)
        }

        val content = formatTranslations(
            translations = translations,
            originalTexts = originalTexts,
            pageNames = pageNames,
            pageNumbers = pageNumbers,
            format = format ?: "txt"
        )

        hostFs.writeTextFile(targetFile.absolutePath, content)
        logger.info("[TranslatorAI] Saved translation file (${translations.size} entries) to: ${targetFile.absolutePath}")
        return targetFile.absolutePath
    }

    /**
     * Updates or generates the dictionary/lore context for a new chapter using the Lore Master prompt.
     * Backwards-compatible overload using a single list of texts.
     */
    @Deprecated(
        "Use boolean outputFile overload instead",
        ReplaceWith("updateDictionary(currentDictionary, chapterSummary, chapterTexts, inputFolder, modelId, outputFile = !outputFile.isNullOrBlank(), outputDir = outputDir)")
    )
    suspend fun updateDictionary(
        currentDictionary: String?,
        chapterSummary: String?,
        chapterTexts: List<String>?,
        inputFolder: String?,
        modelId: String,
        outputFile: String?,
        outputDir: String?
    ): DictionaryUpdateResult = updateDictionary(
        currentDictionary = currentDictionary,
        chapterSummary = chapterSummary,
        chapterNumber = null,
        originalTexts = null,
        translatedTexts = chapterTexts,
        inputFolder = inputFolder,
        modelId = modelId,
        outputFile = !outputFile.isNullOrBlank(),
        outputDir = outputDir ?: outputFile?.let { File(it).parent }
    )

    @Deprecated(
        "Use boolean outputFile overload instead",
        ReplaceWith("updateDictionary(currentDictionary, chapterSummary, chapterNumber, originalTexts, translatedTexts, inputFolder, modelId, outputFile = !outputFile.isNullOrBlank(), outputDir = outputDir)")
    )
    suspend fun updateDictionary(
        currentDictionary: String?,
        chapterSummary: String?,
        chapterNumber: String?,
        originalTexts: List<String>?,
        translatedTexts: List<String>?,
        inputFolder: String?,
        modelId: String,
        outputFile: String?,
        outputDir: String?
    ): DictionaryUpdateResult = updateDictionary(
        currentDictionary = currentDictionary,
        chapterSummary = chapterSummary,
        chapterNumber = chapterNumber,
        originalTexts = originalTexts,
        translatedTexts = translatedTexts,
        inputFolder = inputFolder,
        modelId = modelId,
        outputFile = !outputFile.isNullOrBlank(),
        outputDir = outputDir ?: outputFile?.let { File(it).parent }
    )

    /**
     * Updates or generates the dictionary/lore context for a new chapter using the Lore Master prompt.
     * Backwards-compatible overload using a single list of texts.
     */
    suspend fun updateDictionary(
        currentDictionary: String?,
        chapterSummary: String?,
        chapterTexts: List<String>?,
        inputFolder: String?,
        modelId: String,
        outputFile: Boolean? = true,
        outputDir: String?,
        enableThinking: Boolean = true,
        reasoningEffort: ReasoningEffortLevel = ReasoningEffortLevel.DEFAULT
    ): DictionaryUpdateResult = updateDictionary(
        currentDictionary = currentDictionary,
        chapterSummary = chapterSummary,
        chapterNumber = null,
        originalTexts = null,
        translatedTexts = chapterTexts,
        inputFolder = inputFolder,
        modelId = modelId,
        outputFile = outputFile,
        outputDir = outputDir,
        enableThinking = enableThinking,
        reasoningEffort = reasoningEffort
    )

    /**
     * Updates or generates the dictionary/lore context for a new chapter using the Lore Master prompt.
     * Supports passing both original OCR texts and translated texts for maximum terminology accuracy.
     * All resulting files (dictionary and changelog) are saved in outputDir when outputFile toggle is true.
     */
    suspend fun updateDictionary(
        currentDictionary: String?,
        chapterSummary: String?,
        chapterNumber: String? = null,
        originalTexts: List<String>? = null,
        translatedTexts: List<String>? = null,
        inputFolder: String?,
        modelId: String,
        outputFile: Boolean? = true,
        outputDir: String?,
        enableThinking: Boolean = true,
        reasoningEffort: ReasoningEffortLevel = ReasoningEffortLevel.DEFAULT,
        debugLogging: Boolean = false
    ): DictionaryUpdateResult {
        val isDeepSeek = modelId == AIModel.DEEPSEEK_FLASH.id || modelId == AIModel.DEEPSEEK_PRO.id
            || modelId == "deepseek-flash" || modelId == "deepseek-v4-pro"
        val effectiveApiKey = if (isDeepSeek) {
            settings.deepseekApiKey?.trim().orEmpty().ifBlank { System.getenv("DEEPSEEK_API_KEY")?.trim() ?: "" }
        } else {
            settings.googleApiKey.trim().ifBlank { System.getenv("API_KEY")?.trim() ?: "" }
        }
        if (effectiveApiKey.isBlank() && modelId != AIModel.LM_STUDIO.id) {
            val keyName = if (isDeepSeek) "DeepSeek API Key (deepseekApiKey)" else "Google API Key (googleApiKey)"
            val msg = "API Key not found. Pass it via $keyName setting or set the corresponding environment variable."
            logger.error("[TranslatorAI] $msg")
            throw IllegalArgumentException(msg)
        }

        // 1. Resolve current dictionary content
        val currentDictionaryContent = if (!currentDictionary.isNullOrBlank()) {
            try {
                val file = File(currentDictionary)
                if (file.exists() && file.isFile) file.readText() else currentDictionary
            } catch (e: Exception) {
                currentDictionary
            }
        } else ""

        // 2. Resolve chapter summary
        var effectiveChapterSummary = ""
        if (!chapterSummary.isNullOrBlank()) {
            effectiveChapterSummary = try {
                val file = File(chapterSummary)
                if (file.exists() && file.isFile) file.readText() else chapterSummary
            } catch (e: Exception) {
                chapterSummary
            }
        }

        val isVisionSupported = modelId != AIModel.DEEPSEEK_PRO.id && modelId != "deepseek-v4-pro"
        // If summary is blank but images are present in inputFolder, generate summary with vision
        if (isVisionSupported && effectiveChapterSummary.isBlank() && !inputFolder.isNullOrBlank()) {
            val folder = File(inputFolder)
            if (folder.exists() && folder.isDirectory) {
                val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")
                val images = folder.listFiles { f -> f.isFile && f.extension.lowercase() in imageExtensions }
                    ?.sortedNaturally()
                    ?.toList() ?: emptyList()
                if (images.isNotEmpty()) {
                    logger.info("[TranslatorAI] Generating chapter summary from ${images.size} images in $inputFolder...")
                    val tempDir = File.createTempFile("wom_dict_summary_", "")
                    tempDir.delete()
                    tempDir.mkdirs()
                    try {
                        val processed = images.mapIndexed { idx, img ->
                            val targetFile = File(tempDir, "summary_page_$idx.jpg")
                            resizeAndCompressImage(img, targetFile)
                        }
                        effectiveChapterSummary = generateChapterContext(processed, effectiveApiKey) ?: ""
                    } finally {
                        tempDir.deleteRecursively()
                    }
                }
            }
        } else if (!isVisionSupported && effectiveChapterSummary.isBlank() && !inputFolder.isNullOrBlank()) {
            logger.warn("[TranslatorAI] Model $modelId does not support vision. Cannot generate chapter summary from images directly.")
        }

        val hasOriginals = !originalTexts.isNullOrEmpty()
        val hasTranslations = !translatedTexts.isNullOrEmpty()

        if (effectiveChapterSummary.isBlank() && !hasOriginals && !hasTranslations) {
            val msg = "Chapter summary, chapter dialogues (original or translated), or inputFolder containing images must be provided to update the dictionary."
            logger.error("[TranslatorAI] $msg")
            throw IllegalArgumentException(msg)
        }

        // 3. Build prompt
        val promptInstructions = """
            SEI IL RESPONSABILE DEL CONTESTO E DELLA COERENZA LINGUISTICA (LORE MASTER) DI UNA PIPELINE DI TRADUZIONE DI MANHWA.

            IL TUO OBIETTIVO:
            Riceverai in input:
            1. Il FILE DI CONTESTO ATTUALE (in formato Markdown).
            2. L'ANALISI / SUNTO DEL NUOVO CAPITOLO da integrare (dal testo o dal visual extractor).

            DEVI PRODURRE IN USCITA:
            1. Un breve SUNTO DELLE MODIFICHE (Changelog essenziale).
            2. Il NUOVO FILE DI CONTESTO intero e formattato, pronto per essere sovrascritto.

            REGOLE FERREE DI STRUTTURA:
            1. MANTIENI LA STRUTTURA ESATTA DEI 4 BLOCCHI:
               === LORE & GLOSSARIO FISSO ===
               === CRONOLOGIA REMOTA & ARCHIVIO ===
               === BUFFER EVENTI RECENTI ===
               === REGISTRO PERSONAGGI ATTIVI ===

            2. POLITICA SLIDING WINDOW (FINESTRA SCORREVOLE) PER "BUFFER EVENTI RECENTI":
               - Il buffer DEVE contenere SEMPRE un massimo di 4-5 capitoli recenti.
               - Quando aggiungi un nuovo capitolo (es. Cap. N), il capitolo più vecchio (es. Cap. N-5) DEVE ESSERE ELIMINATO dal buffer.
               - Prima di eliminare il capitolo più vecchio, estraine 1 sola riga di sintesi fondamentale e inseriscila in "CRONOLOGIA REMOTA & ARCHIVIO" per non perdere la memoria storica.

            3. "REGISTRO PERSONAGGI ATTIVI":
               - Mantieni solo i personaggi presenti o direttamente influenti negli ultimi capitoli del buffer.
               - Se un personaggio esce di scena (es. muore, viaggia altrove, diventa prigioniero non visibile), spostalo nella sottosezione "Personaggi secondari/inattivi" dell'Archivio.
               - Per ogni personaggio attivo specifica SEMPRE: genere grammaticale, razza/ruolo, e tono di voce/registro (es. intimo, militare, freddo, infantile).

            4. "LORE & GLOSSARIO FISSO":
               - Aggiungi SOLO termini stabili e ricorrenti (nomi di abilità uniche, classi, fazioni, luoghi cardine).
               - Specifica SEMPRE il genere grammaticale italiano assegnato (es. "mana = maschile", "lancia = femminile").

            5. FORMATO DI OUTPUT RICHIESTO:
            Restituisci la risposta ESATTAMENTE in questo schema senza preamboli né spiegazioni aggiuntive:

            # CHILOMETRO / MODIFICHE APPORTATE
            - Rimosso dal buffer: Cap. X (spostato in archivio: "[sintesi]")
            - Aggiunto al buffer: Cap. Y
            - Variazioni Personaggi / Glossario: [eventuali modifiche o "Nessuna variazione"]

            # NUOVO FILE DI CONTESTO
            ```markdown
            [Incolla qui l'intero file aggiornato rispettando il template identico dei 4 blocchi]
            ```
        """.trimIndent()

        val contextInputText = if (currentDictionaryContent.isNotBlank()) {
            "=== FILE DI CONTESTO ATTUALE ===\n$currentDictionaryContent\n=== FINE FILE DI CONTESTO ATTUALE ==="
        } else {
            "=== FILE DI CONTESTO ATTUALE ===\n(Nessun contesto precedente fornito. Crea il file di contesto iniziale con i 4 blocchi richiesti basandoti su questo capitolo.)\n=== FINE FILE DI CONTESTO ATTUALE ==="
        }

        val chapterInfoText = if (!chapterNumber.isNullOrBlank()) {
            "\n=== INDICAZIONE CAPITOLO NUOVO ===\nIl nuovo capitolo da integrare è: $chapterNumber.\nInserisci questo capitolo come voce più recente nel \"BUFFER EVENTI RECENTI\" ed elimina il capitolo più vecchio (spostandone una riga di sintesi fondamentale in CRONOLOGIA REMOTA & ARCHIVIO).\n=== FINE INDICAZIONE CAPITOLO ==="
        } else ""

        val chapterAnalysisText = if (effectiveChapterSummary.isNotBlank()) {
            "=== ANALISI / SUNTO DEL NUOVO CAPITOLO ===\n$effectiveChapterSummary\n=== FINE ANALISI DEL NUOVO CAPITOLO ==="
        } else ""

        val dialogueText = when {
            hasOriginals && hasTranslations -> {
                val origList = originalTexts ?: emptyList()
                val transList = translatedTexts ?: emptyList()
                val maxLen = maxOf(origList.size, transList.size)
                buildString {
                    appendLine()
                    appendLine("=== TESTI E DIALOGHI DEL NUOVO CAPITOLO (ORIGINALE OCR vs TRADUZIONE) ===")
                    appendLine("NOTA FONDAMENTALE PER IL LORE MASTER:")
                    appendLine("- Usa i testi ORIGINALI OCR per verificare l'esatta denominazione di nomi propri, titoli, abilità, ruoli e fazioni, prevenendo allucinazioni o errori della traduzione.")
                    appendLine("- Usa le TRADUZIONI ITALIANE per capire la resa stilistica già adottata e segnalare nel glossario/changelog eventuali discrepanze o correzioni necessarie.")
                    appendLine()
                    for (i in 0 until maxLen) {
                        val o = origList.getOrNull(i)?.takeIf { it.isNotBlank() }
                        val t = transList.getOrNull(i)?.takeIf { it.isNotBlank() }
                        if (o != null || t != null) {
                            appendLine("[${i + 1}]")
                            if (o != null) appendLine("  - ORIGINALE (OCR): $o")
                            if (t != null) appendLine("  - TRADUZIONE (IT): $t")
                        }
                    }
                    appendLine("=== FINE TESTI E DIALOGHI ===")
                }
            }
            hasOriginals -> {
                val origList = originalTexts ?: emptyList()
                buildString {
                    appendLine()
                    appendLine("=== TESTI ORIGINALI DEL NUOVO CAPITOLO (OCR SOURCE) ===")
                    appendLine("NOTA: Usa questi testi originali estratti via OCR per identificare eventi, dialoghi, nomi dei personaggi ed elementi di lore.")
                    appendLine()
                    origList.forEachIndexed { i, txt ->
                        if (txt.isNotBlank()) {
                            appendLine("[${i + 1}] $txt")
                        }
                    }
                    appendLine("=== FINE TESTI ORIGINALI ===")
                }
            }
            hasTranslations -> {
                val transList = translatedTexts ?: emptyList()
                buildString {
                    appendLine()
                    appendLine("=== TESTI TRADOTTI DEL NUOVO CAPITOLO (ITALIANO) ===")
                    appendLine()
                    transList.forEachIndexed { i, txt ->
                        if (txt.isNotBlank()) {
                            appendLine("[${i + 1}] $txt")
                        }
                    }
                    appendLine("=== FINE TESTI TRADOTTI ===")
                }
            }
            else -> ""
        }

        val userMessage = "$contextInputText$chapterInfoText\n\n$chapterAnalysisText$dialogueText".trim()

        val executor = getExecutor(modelId)
        val finalModelId = if (modelId == AIModel.LM_STUDIO.id) {
            val baseUrl = (settings.lmStudioUrl ?: "http://localhost:1234/v1").ifBlank { "http://localhost:1234/v1" }
            LmStudioManager.Default.resolveModelName(
                baseUrl = baseUrl,
                configuredModel = settings.lmStudioModelName,
                apiKey = settings.lmStudioApiKey,
                logger = logger
            )
        } else modelId

        val model = LLModel(
            provider = getProvider(modelId),
            id = finalModelId,
            capabilities = buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.Temperature)
                if (getProvider(modelId) == LLMProvider.OpenAI) {
                    add(LLMCapability.OpenAIEndpoint.Completions)
                }
                if (enableThinking) {
                    add(LLMCapability.Thinking)
                }
            },
            contextLength = 1000000,
        )

        val koogEffort = reasoningEffort.toKoogOpenAIEffort()
        val dictParams = if (getProvider(modelId) == LLMProvider.OpenAI) {
            OpenAIChatParams(reasoningEffort = koogEffort)
        } else {
            LLMParams()
        }

        val dictPrompt = prompt(
            id = "lore-master-update-dictionary",
            params = dictParams
        ) {
            user {
                text("$promptInstructions\n\n$userMessage")
            }
        }

        logger.info("[TranslatorAI] Requesting dictionary update from model: $finalModelId...")
        val rawResponse = retryWithBackoff {
            val responses = executor.execute(dictPrompt, model)
            val content = responses.joinToString("\n") { it.content }.trim()
            if (debugLogging || settings.debugLogging == true) {
                logger.info("[DEBUG] Dictionary update raw LLM response:\n$content")
            }
            content
        }

        val parsed = parseDictionaryUpdateResponse(rawResponse)
        logger.info("[TranslatorAI] Dictionary Update Changelog:\n${parsed.changelog}")

        // 4. Save to disk if requested (outputFile toggle is true)
        val shouldSave = outputFile ?: true
        var savedPath: String? = null
        var changelogPath: String? = null

        if (shouldSave && !outputDir.isNullOrBlank()) {
            try {
                val (dictPath, chPath) = saveDictionaryFiles(parsed, outputDir, chapterNumber)
                savedPath = dictPath
                changelogPath = chPath
            } catch (e: Exception) {
                logger.error("[TranslatorAI] Failed to save updated dictionary files: ${e.message}")
            }
        }

        return parsed.copy(savedFilePath = savedPath, changelogFilePath = changelogPath)
    }

    /**
     * Saves updated dictionary and changelog as separated files in the output directory.
     * Also writes backwards-compatible legacy files and optional chapter-specific changelog.
     */
    suspend fun saveDictionaryFiles(
        parsed: DictionaryUpdateResult,
        outputDir: String,
        chapterNumber: String? = null
    ): Pair<String, String> {
        val outDir = File(outputDir)
        hostFs.createDirectory(outDir.absolutePath)

        // 1) Primary dictionary file
        val dictFile = File(outDir, "dictionary.md")
        hostFs.writeTextFile(dictFile.absolutePath, parsed.updatedDictionary)
        logger.info("[TranslatorAI] Saved updated dictionary to: ${dictFile.absolutePath}")

        // Legacy dictionary file for backwards compatibility
        val legacyDictFile = File(outDir, "dizionario_aggiornato.md")
        if (legacyDictFile.canonicalPath != dictFile.canonicalPath) {
            hostFs.writeTextFile(legacyDictFile.absolutePath, parsed.updatedDictionary)
        }

        // 2) Primary changelog file
        val changelogFile = File(outDir, "changelog.md")
        hostFs.writeTextFile(changelogFile.absolutePath, parsed.changelog)
        logger.info("[TranslatorAI] Saved dictionary changelog to: ${changelogFile.absolutePath}")

        // Legacy changelog file for backwards compatibility
        val legacyChangelogFile = File(outDir, "dizionario_changelog.txt")
        if (legacyChangelogFile.canonicalPath != changelogFile.canonicalPath) {
            hostFs.writeTextFile(legacyChangelogFile.absolutePath, parsed.changelog)
        }

        // If chapterNumber is provided, also save chapter-specific changelog
        if (!chapterNumber.isNullOrBlank()) {
            val sanitizedChapter = chapterNumber.trim()
                .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                .trim('_')
            if (sanitizedChapter.isNotBlank()) {
                val chapterChangelogFile = File(outDir, "changelog_$sanitizedChapter.md")
                hostFs.writeTextFile(chapterChangelogFile.absolutePath, parsed.changelog)
                logger.info("[TranslatorAI] Saved chapter-specific changelog to: ${chapterChangelogFile.absolutePath}")
            }
        }

        return Pair(dictFile.absolutePath, changelogFile.absolutePath)
    }
}
