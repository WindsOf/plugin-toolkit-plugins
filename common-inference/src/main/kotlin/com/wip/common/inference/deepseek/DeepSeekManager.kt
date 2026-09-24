package com.wip.common.inference.deepseek

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wip.plugintoolkit.api.PluginLogger

/**
 * Status snapshot of a DeepSeek API connection check.
 */
data class DeepSeekStatus(
    val connected: Boolean,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Centralized manager and utility client for DeepSeek integrations across plugins.
 */
class DeepSeekManager(
    private val httpClient: HttpClient = createDefaultHttpClient()
) {
    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.deepseek.com"
        const val MODEL_FLASH: String = "deepseek-flash"
        const val MODEL_PRO: String = "deepseek-v4-pro"

        private val jsonParser = Json { ignoreUnknownKeys = true }

        val Default = DeepSeekManager()

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000L
                    connectTimeoutMillis = 15_000L
                    socketTimeoutMillis = 30_000L
                }
            }
        }
    }

    /**
     * Probes the DeepSeek endpoint to determine connectivity and enumerate available models.
     */
    suspend fun checkStatus(
        baseUrl: String = DEFAULT_BASE_URL,
        apiKey: String,
        logger: PluginLogger? = null
    ): DeepSeekStatus = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val modelsUrl = if (cleanUrl.endsWith("/v1")) "$cleanUrl/models" else "$cleanUrl/v1/models"

        try {
            logger?.info("[DeepSeekManager] Checking DeepSeek status at: $modelsUrl")
            val response: HttpResponse = httpClient.get(modelsUrl) {
                if (apiKey.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                val jsonTree = jsonParser.parseToJsonElement(body).jsonObject
                val dataArray = jsonTree["data"]?.jsonArray ?: emptyList()
                val modelIds = dataArray.mapNotNull { item ->
                    item.jsonObject["id"]?.jsonPrimitive?.content
                }
                logger?.info("[DeepSeekManager] DeepSeek connected successfully! Models found: $modelIds")
                DeepSeekStatus(
                    connected = true,
                    baseUrl = cleanUrl,
                    models = modelIds
                )
            } else {
                val err = "HTTP ${response.status.value}: ${response.status.description}"
                logger?.warn("[DeepSeekManager] DeepSeek returned non-OK status: $err")
                DeepSeekStatus(
                    connected = false,
                    baseUrl = cleanUrl,
                    errorMessage = err
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to reach DeepSeek API"
            logger?.warn("[DeepSeekManager] Connection check failed for $cleanUrl: $msg")
            DeepSeekStatus(
                connected = false,
                baseUrl = cleanUrl,
                errorMessage = msg
            )
        }
    }

    /**
     * Creates a Koog-compatible OpenAILLMClient tailored for DeepSeek with required capabilities.
     */
    fun createKoogClient(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        baseHttpClient: HttpClient = createDefaultHttpClient()
    ): OpenAILLMClient {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val key = apiKey.trim()

        return object : OpenAILLMClient(
            apiKey = key,
            settings = OpenAIClientSettings(baseUrl = cleanUrl),
            baseClient = baseHttpClient
        ) {
            private val defaultCapabilities = OpenAIModels.Chat.GPT4o.capabilities

            private fun injectCapabilities(model: LLModel): LLModel {
                val baseCaps = model.capabilities ?: defaultCapabilities ?: emptyList()
                val mergedCaps = buildList<LLMCapability> {
                    addAll(baseCaps)
                    if (!contains(LLMCapability.OpenAIEndpoint.Completions)) {
                        add(LLMCapability.OpenAIEndpoint.Completions)
                    }
                    if (!contains(LLMCapability.Completion)) {
                        add(LLMCapability.Completion)
                    }
                    if (!contains(LLMCapability.Schema.JSON.Basic)) {
                        add(LLMCapability.Schema.JSON.Basic)
                    }
                    if (!contains(LLMCapability.Schema.JSON.Standard)) {
                        add(LLMCapability.Schema.JSON.Standard)
                    }
                }
                return LLModel(
                    provider = LLMProvider.OpenAI,
                    id = model.id,
                    capabilities = mergedCaps,
                    contextLength = model.contextLength ?: 1_000_000,
                    maxOutputTokens = model.maxOutputTokens ?: 384_000
                )
            }

            override fun createResponseFormat(
                schema: ai.koog.prompt.params.LLMParams.Schema?,
                model: LLModel
            ): ai.koog.prompt.executor.clients.openai.base.models.OpenAIResponseFormat? {
                return if (schema != null) {
                    ai.koog.prompt.executor.clients.openai.base.models.OpenAIResponseFormat.JsonObject()
                } else null
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
        }
    }
}
