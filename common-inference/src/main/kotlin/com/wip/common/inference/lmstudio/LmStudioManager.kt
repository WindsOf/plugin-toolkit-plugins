package com.wip.common.inference.lmstudio

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wip.plugintoolkit.api.PluginLogger

/**
 * Status snapshot of an LM Studio server instance.
 */
data class LmStudioStatus(
    val connected: Boolean,
    val baseUrl: String,
    val models: List<String> = emptyList(),
    val activeModel: String? = null,
    val errorMessage: String? = null
)

/**
 * Centralized manager and utility client for LM Studio integrations across plugins.
 */
class LmStudioManager(
    private val httpClient: HttpClient = createDefaultHttpClient()
) {
    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }

        val Default = LmStudioManager()

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 15_000L
                    connectTimeoutMillis = 10_000L
                    socketTimeoutMillis = 15_000L
                }
            }
        }

        /**
         * Standard paths where LM Studio models are stored across operating systems.
         */
        fun getStandardModelDirectories(): List<File> {
            val userHome = System.getProperty("user.home", ".")
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
            return listOf(
                File(userHome, ".cache/lm-studio/models"),
                File(userHome, ".lmstudio/models"),
                File(userHome, ".lmstudio/models/sahilchachra/Unlimited-OCR-GGUF"),
                File(localAppData, "lm-studio/models")
            )
        }
    }

    /**
     * Probes the LM Studio endpoint to determine connectivity and enumerate currently loaded models.
     */
    suspend fun checkStatus(
        baseUrl: String = "http://localhost:1234/v1",
        apiKey: String? = null,
        logger: PluginLogger? = null
    ): LmStudioStatus = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val modelsUrl = if (cleanUrl.endsWith("/v1")) "$cleanUrl/models" else "$cleanUrl/v1/models"

        try {
            logger?.info("[LmStudioManager] Checking LM Studio status at: $modelsUrl")
            val response: HttpResponse = httpClient.get(modelsUrl) {
                if (!apiKey.isNullOrBlank()) {
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
                val active = modelIds.firstOrNull()

                logger?.info("[LmStudioManager] LM Studio connected successfully! Models found: $modelIds")
                LmStudioStatus(
                    connected = true,
                    baseUrl = cleanUrl,
                    models = modelIds,
                    activeModel = active
                )
            } else {
                val err = "HTTP ${response.status.value}: ${response.status.description}"
                logger?.warn("[LmStudioManager] LM Studio returned non-OK status: $err")
                LmStudioStatus(
                    connected = false,
                    baseUrl = cleanUrl,
                    errorMessage = err
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to reach LM Studio"
            logger?.warn("[LmStudioManager] Connection check failed for $cleanUrl: $msg")
            LmStudioStatus(
                connected = false,
                baseUrl = cleanUrl,
                errorMessage = msg
            )
        }
    }

    /**
     * Resolves the effective model name to query in LM Studio.
     * If [configuredModel] is blank or generic ("default-model", "auto"), attempts to query LM Studio
     * for the actively loaded model.
     */
    suspend fun resolveModelName(
        baseUrl: String = "http://localhost:1234/v1",
        configuredModel: String?,
        apiKey: String? = null,
        logger: PluginLogger? = null
    ): String {
        val configured = configuredModel?.trim()
        val isGeneric = configured.isNullOrBlank() ||
            configured.equals("default-model", ignoreCase = true) ||
            configured.equals("auto", ignoreCase = true)

        if (!isGeneric) {
            return configured!!
        }

        val status = checkStatus(baseUrl = baseUrl, apiKey = apiKey, logger = logger)
        if (status.connected && !status.activeModel.isNullOrBlank()) {
            logger?.info("[LmStudioManager] Auto-selected active LM Studio model: ${status.activeModel}")
            return status.activeModel
        }

        return configured?.ifBlank { "default-model" } ?: "default-model"
    }

    /**
     * Creates a Koog-compatible OpenAILLMClient tailored for LM Studio with required capability overrides.
     */
    fun createKoogClient(
        baseUrl: String,
        apiKey: String,
        baseHttpClient: HttpClient
    ): OpenAILLMClient {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val key = apiKey.ifBlank { "lm-studio" }

        return object : OpenAILLMClient(
            apiKey = key,
            settings = OpenAIClientSettings(baseUrl = cleanUrl),
            baseClient = baseHttpClient
        ) {
            private val fullCapabilities = OpenAIModels.Chat.GPT4o.capabilities

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
        }
    }

    /**
     * Searches standard and common LM Studio folders for a matching GGUF model file.
     */
    fun findLmStudioModelFile(modelId: String): File? {
        val candidates = mutableListOf<File>()
        val targetName = modelId.trim()

        for (dir in getStandardModelDirectories()) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().maxDepth(5).filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }.forEach {
                    candidates.add(it)
                }
            }
        }

        return candidates.firstOrNull { file ->
            val nameWithoutExt = file.nameWithoutExtension
            file.name.equals("$targetName.gguf", ignoreCase = true) ||
                nameWithoutExt.equals(targetName, ignoreCase = true) ||
                (targetName.contains("bf16", ignoreCase = true) && nameWithoutExt.contains("bf16", ignoreCase = true)) ||
                (targetName.contains("q8_0", ignoreCase = true) && nameWithoutExt.contains("q8_0", ignoreCase = true)) ||
                (targetName.contains("q4_k_m", ignoreCase = true) && nameWithoutExt.contains("q4_k_m", ignoreCase = true)) ||
                (targetName.contains("iq2_m", ignoreCase = true) && nameWithoutExt.contains("iq2_m", ignoreCase = true))
        }
    }

    /**
     * Searches standard LM Studio directories for a multimodal projector (.gguf) file.
     */
    fun findLmStudioMmprojFile(): File? {
        for (dir in getStandardModelDirectories()) {
            if (dir.exists() && dir.isDirectory) {
                val direct = File(dir, "mmproj-Unlimited-OCR-F16.gguf")
                if (direct.exists()) return direct
                val found = dir.walkTopDown().maxDepth(5).firstOrNull {
                    it.isFile && it.name.startsWith("mmproj", ignoreCase = true) && it.extension.equals("gguf", ignoreCase = true)
                }
                if (found != null) return found
            }
        }
        return null
    }
}
