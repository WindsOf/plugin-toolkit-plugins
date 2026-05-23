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

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 5,
        initialDelayMs: Long = 1000,
        backoffFactor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e
                logger.warn("Attempt $attempt failed: ${e::class.simpleName}: ${e.message}. Retrying in ${currentDelay}ms...")
                if (attempt < maxAttempts) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * backoffFactor).toLong()
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
        useStructuredOutput: Boolean = true
    ): List<String> {
        if (input.isEmpty()) return emptyList()

        // ── API Key ────────────────────────────────────────────────────
        val effectiveApiKey = apiKey.ifBlank { System.getenv("API_KEY") ?: "" }

        if (effectiveApiKey.isBlank()) {
            val msg = "API Key not found. Pass it via 'apiKey' parameter or set the API_KEY environment variable."
            logger.error(msg)
            throw IllegalArgumentException(msg)
        }

        // ── Executor + Model ───────────────────────────────────────────
        val executor = simpleGoogleAIExecutor(effectiveApiKey)
        
        // Note: Using the model ID specified in the original code, though "gemma-4-31b-it" might be a placeholder.
        val model = LLModel(
            provider = LLMProvider.Google,
            id = "gemma-4-31b-it",
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Thinking
            )
        )

        // ── Prompt ─────────────────────────────────────────────────────
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

        try {
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
                system {
                    text(promptInstructions)
                }
                user {
                    text("Strings to translate:\n" + input.joinToString("\n") { "[TEXT]: $it" })
                }
            }

            logger.info("Sending translation request for ${input.size} strings... (Structured Output: $useStructuredOutput)")
            val responses = retryWithBackoff {
                executor.execute(translatePrompt, model)
            }
            
            var rawResponse = responses.joinToString("\n") { it.content }.trim()

            // Strip thinking blocks if present
            rawResponse = rawResponse.replace(
                Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
            ).trim()

            val jsonToParse = if (useStructuredOutput) rawResponse else extractJson(rawResponse)

            val json = Json { ignoreUnknownKeys = true }
            val responseObj = json.decodeFromString<TranslationResponse>(jsonToParse)

            if (responseObj.translations.size != input.size) {
                logger.warn("Received ${responseObj.translations.size} translations for ${input.size} inputs. Alignment might be off.")
            }

            logger.info("Translation completed successfully.")
            
            return responseObj.translations

        } catch (e: Throwable) {
            logger.error("Error during translation: ${e::class.simpleName}: ${e.message}")
            throw e
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
