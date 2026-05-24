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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Serializable
data class TranslationResponse(
    val translations: List<String>
)

/**
 * Kotlin-native Translator service using Koog + Google AI.
 * Translates a list of strings to Italian following dictionary guidelines.
 */
class KoogAITranslatorService(private val context: PluginContext) {
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
        val delaysMs = listOf(5000L, 10000L, 15000L) // Max 3 retries
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

    suspend fun performTranslation(
        input: List<String>,
        dictionary: String,
        apiKey: String,
        useStructuredOutput: Boolean = true,
        modelId: String
    ): List<String> {
        if (input.isEmpty()) return emptyList()

        // ── API Key ────────────────────────────────────────────────────
        val effectiveApiKey = apiKey.ifBlank { System.getenv("API_KEY") ?: "" }

        if (effectiveApiKey.isBlank()) {
            val msg = "API Key not found. Pass it via 'apiKey' parameter or set the API_KEY environment variable."
            logger.error(msg)
            throw IllegalArgumentException(msg)
        }

        // ── Chunking & Concurrency ──────────────────────────────────────
        val chunks = input.chunked(50)
        logger.info("Split ${input.size} texts into ${chunks.size} chunks of max 50 items.")
        
        val results = mutableListOf<String>()
        
        coroutineScope {
            val deferreds = chunks.mapIndexed { index, chunk ->
                async {
                    concurrentSemaphore.withPermit {
                        acquireRateLimit()
                        translateChunkWithRetry(chunk, dictionary, effectiveApiKey, useStructuredOutput, modelId, index)
                    }
                }
            }
            // Await all chunks and flatten
            results.addAll(deferreds.awaitAll().flatten())
        }
        
        return results
    }

    private suspend fun translateChunkWithRetry(
        chunk: List<String>,
        dictionary: String,
        apiKey: String,
        useStructuredOutput: Boolean,
        modelId: String,
        chunkIndex: Int
    ): List<String> {
        val executor = simpleGoogleAIExecutor(apiKey)
        
        val model = LLModel(
            provider = LLMProvider.Google,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Thinking
            ),
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
            Do not include any other text, explanations, or markdown formatting outside of the JSON block if possible.
            """.trimIndent()
        } else ""

        val promptInstructions = """
            You are a professional Manhwa/Manga translator. 
            Translate the following list of strings into natural, expressive Italian.
            
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
}
