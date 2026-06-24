package com.wip.manhwaTranslatorAI

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginSignal
import kotlinx.coroutines.delay
import org.wip.plugintoolkit.api.HostFileSystem
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileImageOutputStream

@Serializable
data class TranslationResponse(
    val translations: List<String>
)



/**
 * Kotlin-native Translator service using Koog + Google AI.
 * Translates a list of strings to Italian following dictionary guidelines.
 */
class KoogAITranslatorService(private val context: PluginContext, private val hostFs: HostFileSystem) {
    private val logger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

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
                delay(waitTime + 100)
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        block: suspend () -> T
    ): T {
        val delaysMs = listOf(5000L, 10000L, 10000L, 10000L, 15000L) // Max 5 retries
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

    private data class TextEntry(val index: Int, val text: String, val pageName: String)

    suspend fun performTranslation(
        input: List<String>,
        dictionary: String,
        apiKey: String,
        useStructuredOutput: Boolean = true,
        modelId: String,
        pageNames: List<String>? = null,
        inputFolder: String,
        outputDir: String,
        tempSummaryDir: String,
        useContextImages: Boolean = false,
        generateChapterSummary: Boolean = true,
        save: Boolean = true
    ): List<String> {
        if (input.isEmpty()) return emptyList()

        // ── API Key ────────────────────────────────────────────────────
        val effectiveApiKey = apiKey.trim().ifBlank { System.getenv("API_KEY")?.trim() ?: "" }

        if (effectiveApiKey.isBlank()) {
            val msg = "API Key not found. Pass it via 'apiKey' parameter or set the API_KEY environment variable."
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

        val isContextModeValid = useContextImages && pageNames != null && inputFolder != null && pageNames.size == input.size
        
        if (useContextImages && !isContextModeValid) {
            logger.warn("Context Images enabled but missing/invalid inputs (pageNames or inputFolder mismatch). Falling back to text-only mode.")
        }
        
        var globalContext: String? = null
        if (generateChapterSummary && !inputFolder.isNullOrBlank()) {
            val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")
            val allImages = if (pageNames != null && pageNames.isNotEmpty()) {
                pageNames.distinct().map { File(inputFolder, it) }.filter { it.exists() }
            } else {
                val folder = File(inputFolder)
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles { f -> f.isFile && f.extension.lowercase() in imageExtensions }
                        ?.sortedBy { it.name }
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
            if (isContextModeValid) {
                // Image Context Mode
                val entries = input.mapIndexed { index, text -> TextEntry(index, text, pageNames[index]) }
                val groupedByPage = entries.groupBy { it.pageName }
                val uniquePages = groupedByPage.keys.toList()
                
                val pageChunks = mutableListOf<List<TextEntry>>()
                var currentChunk = mutableListOf<TextEntry>()
                var currentImagesCount = 0
                
                for (page in uniquePages) {
                    val pageEntries = groupedByPage[page] ?: emptyList()
                    
                    // Flush if adding this page would exceed 5 images OR 50 texts (and the chunk is already not empty)
                    if (currentChunk.isNotEmpty() && (currentImagesCount + 1 > 5 || currentChunk.size + pageEntries.size > 50)) {
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

                logger.info("Context mode active. Split ${entries.size} texts into ${pageChunks.size} chunks (max 50 texts/5 images).")

                val deferreds = pageChunks.mapIndexed { index, chunkEntries ->
                    async {
                        concurrentSemaphore.withPermit {
                            acquireRateLimit()
                            val chunkTexts = chunkEntries.map { it.text }
                            val uniqueChunkPages = chunkEntries.map { it.pageName }.distinct()
                            val images = uniqueChunkPages.map { File(inputFolder, it) }.filter { it.exists() }
                            
                            val translations = translateChunkWithRetry(
                                chunkTexts, images, dictionaryContent, effectiveApiKey, useStructuredOutput, modelId, index, globalContext
                            )

                            // Place translations in correct original indices
                            chunkEntries.forEachIndexed { i, entry ->
                                results[entry.index] = translations.getOrNull(i) ?: "[Translation Missing]"
                            }
                        }
                    }
                }
                deferreds.awaitAll()
            } else {
                // Text-only mode (Classic)
                val chunks = input.chunked(50)
                logger.info("Text mode. Split ${input.size} texts into ${chunks.size} chunks of max 50 items.")
                
                val deferreds = chunks.mapIndexed { index, chunk ->
                    async {
                        concurrentSemaphore.withPermit {
                            acquireRateLimit()
                            val translations = translateChunkWithRetry(
                                chunk, null, dictionaryContent, effectiveApiKey, useStructuredOutput, modelId, index, globalContext
                            )
                            val startIndex = index * 50
                            translations.forEachIndexed { i, translatedText ->
                                if (startIndex + i < results.size) {
                                    results[startIndex + i] = translatedText
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
                if (!outDir.exists()) outDir.mkdirs()
                val outFile = File(outDir, "translation_result.json")
                val json = Json { prettyPrint = true }
                outFile.writeText(json.encodeToString(TranslationResponse(finalTranslations)))
                logger.info("Saved translation result to: ${outFile.absolutePath}")
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
        chapterContext: String?
    ): List<String> {
        val executor = simpleGoogleAIExecutor(apiKey)
        
        val model = LLModel(
            provider = LLMProvider.Google,
            id = modelId,
            capabilities = buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.Temperature)
                add(LLMCapability.Thinking)
                if (!images.isNullOrEmpty()) add(LLMCapability.Vision.Image)
            },
            contextLength = 100000,
        )

        val dictionaryInstructions = if (dictionary.isNotEmpty()) {
            "ADHERE STRICTLY TO THESE DICTIONARY GUIDELINES:\n$dictionary"
        } else {
            "No specific dictionary guidelines provided."
        }

        val jsonFormatRequirement = if (!useStructuredOutput) {
            """
            
            FORMAT REQUIREMENT:
            You MUST return a JSON object with the following structure:
            {
              "translations": ["string1", "string2", ...]
            }
            Do not include any other text, explanations, or markdown formatting outside of the JSON block.
            """.trimIndent()
        } else ""

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
            5. Return ONLY the JSON object containing the list of translated strings. $jsonFormatRequirement
            
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
            val llmParams = if (useStructuredOutput) {
                LLMParams(
                    schema = LLMParams.Schema.JSON.Basic(
                        name = "TranslationResponse",
                        schema = translationSchema
                    )
                )
            } else {
                LLMParams()
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

            // Strip thinking blocks if present
            rawResponse = rawResponse.replace(
                Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
            ).trim()

            val jsonToParse = if (useStructuredOutput) rawResponse else extractJson(rawResponse)

            val json = Json { ignoreUnknownKeys = true }
            val responseObj = json.decodeFromString<TranslationResponse>(jsonToParse)

            if (responseObj.translations.size != chunk.size) {
                throw IllegalStateException("Mismatch in chunk $chunkIndex: Expected ${chunk.size} translations, got ${responseObj.translations.size}")
            }

            logger.info("Translation for chunk $chunkIndex completed successfully.")
            responseObj.translations
        }
    }

    private fun extractJson(raw: String): String {
        // Try to extract from markdown code blocks first
        val jsonRegex = Regex("```json\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonRegex.find(raw)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // Fallback to finding the first '{' and last '}'
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return raw.substring(start, end + 1)
        }

        return raw.trim()
    }

    private suspend fun generateChapterContext(images: List<File>, apiKey: String): String? {
        logger.info("Generating global chapter context using gemini-3.1-flash-lite with ${images.size} images...")
        return try {
            retryWithBackoff {
                val executor = simpleGoogleAIExecutor(apiKey)
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
                    Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
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
}
