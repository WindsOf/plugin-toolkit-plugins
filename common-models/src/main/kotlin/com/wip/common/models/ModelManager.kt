package com.wip.common.models

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import org.wip.plugintoolkit.api.RelativePath
import org.wip.plugintoolkit.api.toRelativePath

/**
 * Manages downloading, local storage, lock status verification, and retrieval of ONNX models.
 */
class ModelManager(
    private val httpClient: HttpClient = createDefaultHttpClient()
) {
    companion object {
        const val MODELS_DIR = "models"
        const val STORAGE_KEY_PREFIX = "installed_model_"

        /**
         * Default Ktor HTTP client configured with timeouts for slow/resilient connections.
         */
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 600_000L // 10 minutes
                    connectTimeoutMillis = 60_000L  // 1 minute
                    socketTimeoutMillis = 120_000L  // 2 minutes
                }
            }
        }

        val Default = ModelManager()

        fun getModelYamlRelativePath(modelId: String): RelativePath {
            return "$MODELS_DIR/${modelId.trim().lowercase()}.yaml".toRelativePath().getOrThrow()
        }

        fun getModelOnnxRelativePath(modelId: String): RelativePath {
            return "$MODELS_DIR/${modelId.trim().lowercase()}.onnx".toRelativePath().getOrThrow()
        }
    }

    /**
     * Checks if both the model YAML descriptor and ONNX weights exist in the plugin file system.
     */
    suspend fun isModelInstalled(modelId: String, fileSystem: PluginFileSystem): Boolean {
        val yamlRelPath = getModelYamlRelativePath(modelId)
        val onnxRelPath = getModelOnnxRelativePath(modelId)
        return fileSystem.exists(yamlRelPath) && fileSystem.exists(onnxRelPath)
    }

    /**
     * Evaluates lock states for all registered catalog models.
     * Returns a map suitable for returning from a `@PluginLocks` function.
     */
    suspend fun getLocksState(fileSystem: PluginFileSystem): Map<String, Boolean> {
        val locksMap = mutableMapOf<String, Boolean>()
        for (modelEntry in ModelCatalog.ALL_MODELS) {
            val installed = isModelInstalled(modelEntry.id, fileSystem)
            locksMap[modelEntry.lockKey] = installed
            locksMap[modelEntry.id] = installed
        }
        return locksMap
    }

    /**
     * Reads and parses the ModelSpec for a locally installed model.
     */
    suspend fun getModelSpec(modelId: String, fileSystem: PluginFileSystem): ModelSpec? {
        val yamlRelPath = getModelYamlRelativePath(modelId)
        val yamlText = fileSystem.readTextFile(yamlRelPath) ?: return null
        return try {
            ModelSpec.parseFromYaml(yamlText)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads the raw ONNX model binary weights from local storage.
     */
    suspend fun getModelBytes(modelId: String, fileSystem: PluginFileSystem): ByteArray? {
        val onnxRelPath = getModelOnnxRelativePath(modelId)
        return fileSystem.readFile(onnxRelPath)
    }

    /**
     * Returns the absolute path to the locally saved ONNX model weights.
     */
    fun getModelAbsolutePath(modelId: String, fileSystem: PluginFileSystem): String {
        val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
        return "$basePath/$MODELS_DIR/${modelId.trim().lowercase()}.onnx"
    }

    /**
     * Downloads a single model (YAML descriptor and ONNX weights) and stores it in the plugin file system.
     */
    suspend fun downloadModel(modelId: String, context: PluginContext): Result<ModelSpec> {
        val logger: PluginLogger = context.logger
        val progress: ProgressReporter = context.progress
        val fileSystem: PluginFileSystem = context.fileSystem

        val catalogEntry = ModelCatalog.findById(modelId)
            ?: return Result.failure(IllegalArgumentException("Model with ID '$modelId' is not registered in the catalog."))

        logger.info("Starting download for model: ${catalogEntry.displayName} (${catalogEntry.id})")
        progress.report(0.05f)

        // 1. Download YAML descriptor
        val yamlText = try {
            logger.info("Downloading model descriptor from: ${catalogEntry.yamlUrl}")
            val response: HttpResponse = httpClient.get(catalogEntry.yamlUrl)
            if (response.status != HttpStatusCode.OK) {
                return Result.failure(IllegalStateException("Failed to download model YAML: HTTP ${response.status.value}"))
            }
            response.bodyAsText()
        } catch (e: Exception) {
            logger.error("Error downloading YAML descriptor: ${e.message}", e)
            return Result.failure(e)
        }

        val modelSpec = try {
            ModelSpec.parseFromYaml(yamlText)
        } catch (e: Exception) {
            logger.error("Failed to parse model descriptor YAML: ${e.message}", e)
            return Result.failure(e)
        }

        // Save YAML to plugin file system
        val yamlRelPath = getModelYamlRelativePath(catalogEntry.id)
        fileSystem.writeTextFile(yamlRelPath, yamlText).getOrElse {
            return Result.failure(it)
        }
        progress.report(0.15f)

        // 2. Download ONNX model weights
        val onnxBytes = try {
            logger.info("Downloading ONNX model weights from: ${catalogEntry.onnxUrl}")
            val response: HttpResponse = httpClient.get(catalogEntry.onnxUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null && contentLength > 0) {
                        val dlProgress = 0.15f + (bytesSentTotal.toFloat() / contentLength.toFloat()) * 0.80f
                        progress.report(dlProgress.coerceIn(0.15f, 0.95f))
                    }
                }
            }
            if (response.status != HttpStatusCode.OK) {
                return Result.failure(IllegalStateException("Failed to download model ONNX: HTTP ${response.status.value}"))
            }
            response.readRawBytes()
        } catch (e: Exception) {
            logger.error("Error downloading ONNX weights: ${e.message}", e)
            return Result.failure(e)
        }

        // Save ONNX to plugin file system
        val onnxRelPath = getModelOnnxRelativePath(catalogEntry.id)
        fileSystem.writeFile(onnxRelPath, onnxBytes).getOrElse {
            return Result.failure(it)
        }

        // 3. Record installation in PluginStorage
        try {
            val storageObj = buildJsonObject {
                put("id", catalogEntry.id)
                put("name", modelSpec.name)
                put("type", modelSpec.type)
                put("installedAt", System.currentTimeMillis().toString())
                put("fileSize", onnxBytes.size.toLong())
            }
            context.storage.put("$STORAGE_KEY_PREFIX${catalogEntry.id}", storageObj)
        } catch (e: Exception) {
            logger.warn("Could not save model metadata in PluginStorage: ${e.message}")
        }

        progress.report(1.0f)
        logger.info("Successfully installed model: ${catalogEntry.displayName} to $onnxRelPath")
        return Result.success(modelSpec)
    }

    /**
     * Downloads all models registered in the catalog sequentially.
     */
    suspend fun downloadAllModels(context: PluginContext): Result<List<ModelSpec>> {
        val results = mutableListOf<ModelSpec>()
        val total = ModelCatalog.ALL_MODELS.size
        for ((index, entry) in ModelCatalog.ALL_MODELS.withIndex()) {
            context.logger.info("Downloading model [${index + 1}/$total]: ${entry.id}")
            val result = downloadModel(entry.id, context)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: RuntimeException("Failed to download ${entry.id}"))
            }
            results.add(result.getOrThrow())
        }
        return Result.success(results)
    }
}
