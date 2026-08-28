package com.wip.common.inference.llama

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.wip.plugintoolkit.api.PluginLogger

/**
 * OpenAI-compatible HTTP client for interacting with llama-server vision and completion endpoints.
 */
class LlamaInferenceClient(
    private val httpClient: HttpClient = createDefaultHttpClient()
) {
    companion object {
        val jsonParser = Json { ignoreUnknownKeys = true }

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 600_000L // 10 minutes for large vision inferences
                    connectTimeoutMillis = 30_000L
                    socketTimeoutMillis = 300_000L
                }
            }
        }

        val Default = LlamaInferenceClient()
    }

    /**
     * Checks if the llama-server is healthy and ready to accept requests.
     */
    suspend fun checkHealth(baseUrl: String, logger: PluginLogger? = null): Boolean {
        val cleanUrl = baseUrl.trimEnd('/')
        return try {
            val healthResponse: HttpResponse = httpClient.get("$cleanUrl/health")
            if (healthResponse.status == HttpStatusCode.OK) {
                return true
            }
            val modelsResponse: HttpResponse = httpClient.get("$cleanUrl/v1/models")
            modelsResponse.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger?.info("[LlamaInferenceClient] Health check failed for $cleanUrl: ${e.message}")
            false
        }
    }

    /**
     * Executes vision OCR on an image file using the OpenAI-compatible /v1/chat/completions endpoint.
     */
    suspend fun executeVisionChat(
        baseUrl: String,
        imageFile: File,
        promptInstructions: String,
        jsonSchema: JsonObject? = null,
        temperature: Double = 0.1,
        maxTokens: Int = 4096,
        logger: PluginLogger? = null
    ): String = withContext(Dispatchers.IO) {
        val imageBytes = imageFile.readBytes()
        val mimeType = when (imageFile.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val dataUrl = "data:$mimeType;base64,$base64Image"

        executeVisionChatBase64(
            baseUrl = baseUrl,
            dataUrl = dataUrl,
            promptInstructions = promptInstructions,
            jsonSchema = jsonSchema,
            temperature = temperature,
            maxTokens = maxTokens,
            logger = logger
        )
    }

    /**
     * Executes vision chat with a base64 encoded data URL.
     */
    suspend fun executeVisionChatBase64(
        baseUrl: String,
        dataUrl: String,
        promptInstructions: String,
        jsonSchema: JsonObject? = null,
        temperature: Double = 0.1,
        maxTokens: Int = 4096,
        logger: PluginLogger? = null
    ): String {
        val cleanUrl = baseUrl.trimEnd('/')
        val requestBody = buildJsonObject {
            put("temperature", temperature)
            put("max_tokens", maxTokens)

            if (jsonSchema != null) {
                putJsonObject("response_format") {
                    put("type", "json_object")
                    put("schema", jsonSchema)
                }
            }

            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (promptInstructions.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", promptInstructions)
                            })
                        }
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", dataUrl)
                            }
                        })
                    }
                })
            }
        }

        logger?.info("[LlamaInferenceClient] Sending request to $cleanUrl/v1/chat/completions...")

        val response: HttpResponse = httpClient.post("$cleanUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        if (response.status != HttpStatusCode.OK) {
            val errBody = response.bodyAsText()
            logger?.error("[LlamaInferenceClient] Error from llama-server (HTTP ${response.status.value}): $errBody")
            throw IllegalStateException("llama-server returned HTTP ${response.status.value}: $errBody")
        }

        val responseText = response.bodyAsText()
        val parsedJson = jsonParser.parseToJsonElement(responseText).jsonObject

        val choices = parsedJson["choices"]?.jsonArray
        if (choices.isNullOrEmpty()) {
            throw IllegalStateException("llama-server response contains no choices: $responseText")
        }

        val firstChoice = choices[0].jsonObject
        val message = firstChoice["message"]?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content ?: ""

        logger?.info("[LlamaInferenceClient] Received response (${content.length} characters).")
        return content
    }
}
