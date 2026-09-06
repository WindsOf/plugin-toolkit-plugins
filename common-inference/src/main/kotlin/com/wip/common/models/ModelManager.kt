package com.wip.common.models

import com.wip.common.inference.lmstudio.LmStudioManager
import java.io.File
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
            val catalogEntry = ModelCatalog.findById(modelId)
            val exactName = catalogEntry?.id ?: modelId.trim()
            return "$MODELS_DIR/$exactName.yaml".toRelativePath().getOrThrow()
        }

        fun getModelOnnxRelativePath(modelId: String): RelativePath {
            val catalogEntry = ModelCatalog.findById(modelId)
            val exactName = catalogEntry?.id ?: modelId.trim()
            return "$MODELS_DIR/$exactName.onnx".toRelativePath().getOrThrow()
        }

        fun getModelFileRelativePath(fileName: String): RelativePath {
            return "$MODELS_DIR/$fileName".toRelativePath().getOrThrow()
        }
    }

    /**
     * Checks if the model YAML descriptor and all required model files exist in the plugin file system.
     */
    suspend fun isModelInstalled(modelId: String, fileSystem: PluginFileSystem, logger: PluginLogger? = null): Boolean {
        logger?.info("[ModelManager] Checking installation status for model '$modelId'...")
        val modelSpec = getModelSpec(modelId, fileSystem, logger)
        if (modelSpec != null) {
            val requiredFiles = modelSpec.getRequiredFileNames(modelId)
            if (requiredFiles.isEmpty()) {
                logger?.warn("[ModelManager] Model '$modelId' spec has no required files listed. Returning false.")
                return false
            }
            val allPresent = requiredFiles.all { fileName ->
                val relPath = "$MODELS_DIR/$fileName".toRelativePath().getOrNull()
                val lowerRelPath = "$MODELS_DIR/${fileName.lowercase()}".toRelativePath().getOrNull()
                val exists = (relPath != null && fileSystem.exists(relPath)) || (lowerRelPath != null && fileSystem.exists(lowerRelPath))
                logger?.info("[ModelManager] Model '$modelId': file '$fileName' exists = $exists")
                exists
            }
            logger?.info("[ModelManager] Model '$modelId' installation check by spec: all files present = $allPresent")
            return allPresent
        }

        val yamlRelPath = getModelYamlRelativePath(modelId)
        val onnxRelPath = getModelOnnxRelativePath(modelId)
        val yamlExists = fileSystem.exists(yamlRelPath)
        val onnxExists = fileSystem.exists(onnxRelPath)
        logger?.info("[ModelManager] Model '$modelId' fallback check: exact yamlExists=$yamlExists, onnxExists=$onnxExists")
        if (yamlExists && onnxExists) {
            return true
        }
        val lowerYaml = "$MODELS_DIR/${modelId.trim().lowercase()}.yaml".toRelativePath().getOrNull()
        val lowerOnnx = "$MODELS_DIR/${modelId.trim().lowercase()}.onnx".toRelativePath().getOrNull()
        val lowerGguf = "$MODELS_DIR/${modelId.trim().lowercase()}.gguf".toRelativePath().getOrNull()
        val lowerYamlExists = lowerYaml != null && fileSystem.exists(lowerYaml)
        val lowerOnnxExists = lowerOnnx != null && fileSystem.exists(lowerOnnx)
        val lowerGgufExists = lowerGguf != null && fileSystem.exists(lowerGguf)
        val fallbackResult = lowerYamlExists && (lowerOnnxExists || lowerGgufExists)
        logger?.info("[ModelManager] Model '$modelId' lowercase fallback result = $fallbackResult (yaml=$lowerYamlExists, onnx=$lowerOnnxExists, gguf=$lowerGgufExists)")
        return fallbackResult
    }

    /**
     * Searches standard LM Studio directories for local GGUF model weights.
     */
    fun findLmStudioModelFile(modelId: String): File? {
        val catalogEntry = ModelCatalog.findById(modelId)
        val targetName = catalogEntry?.id ?: modelId.trim()
        return LmStudioManager.Default.findLmStudioModelFile(targetName)
    }

    /**
     * Searches standard LM Studio directories or local plugin storage for the multimodal projector (mmproj).
     */
    fun findLmStudioMmprojFile(): File? {
        return LmStudioManager.Default.findLmStudioMmprojFile()
    }

    /**
     * Resolves the absolute path to the multimodal projector (.gguf) file.
     */
    fun getMmprojAbsolutePath(modelId: String, fileSystem: PluginFileSystem): String? {
        val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
        val catalogEntry = ModelCatalog.findById(modelId)
        val extraFiles = catalogEntry?.extraFileUrls?.keys ?: emptySet()
        for (extra in extraFiles) {
            if (extra.startsWith("mmproj", ignoreCase = true)) {
                val candidate = File("$basePath/$MODELS_DIR/$extra")
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        val defaultMmproj = File("$basePath/$MODELS_DIR/mmproj-Unlimited-OCR-F16.gguf")
        if (defaultMmproj.exists()) return defaultMmproj.absolutePath

        return findLmStudioMmprojFile()?.absolutePath
    }

    /**
     * Evaluates lock states for all registered catalog models.
     * Returns a map suitable for returning from a `@PluginLocks` function.
     */
    suspend fun getLocksState(fileSystem: PluginFileSystem, logger: PluginLogger? = null): Map<String, Boolean> {
        val locksMap = mutableMapOf<String, Boolean>()
        for (modelEntry in ModelCatalog.ALL_MODELS) {
            val installed = isModelInstalled(modelEntry.id, fileSystem, logger)
            locksMap[modelEntry.lockKey] = installed
            locksMap[modelEntry.id] = installed
        }
        logger?.info("[ModelManager] getLocksState completed. Total locks: ${locksMap.size}, unlocked count: ${locksMap.count { it.value }}")
        return locksMap
    }

    /**
     * Reads and parses the ModelSpec for a locally installed model.
     */
    suspend fun getModelSpec(modelId: String, fileSystem: PluginFileSystem, logger: PluginLogger? = null): ModelSpec? {
        val yamlRelPath = getModelYamlRelativePath(modelId)
        var yamlText = try {
            fileSystem.readTextFile(yamlRelPath)
        } catch (e: Exception) {
            logger?.warn("[ModelManager] Failed reading text file at $yamlRelPath: ${e.message}")
            null
        }
        if (yamlText == null) {
            val lowerYaml = "$MODELS_DIR/${modelId.trim().lowercase()}.yaml".toRelativePath().getOrNull()
            if (lowerYaml != null) {
                yamlText = try {
                    fileSystem.readTextFile(lowerYaml)
                } catch (e: Exception) {
                    logger?.warn("[ModelManager] Failed reading text file at $lowerYaml: ${e.message}")
                    null
                }
            }
        }
        if (yamlText == null) {
            logger?.info("[ModelManager] No YAML descriptor found on disk for model '$modelId'")
            return null
        }
        return try {
            val spec = ModelSpec.parseFromYaml(yamlText)
            logger?.info("[ModelManager] Successfully parsed ModelSpec for '$modelId' (type=${spec.type}, name=${spec.name})")
            spec
        } catch (e: Exception) {
            logger?.error("[ModelManager] Error parsing YAML descriptor for model '$modelId': ${e.message}", e)
            null
        }
    }

    /**
     * Reads the raw ONNX model binary weights from local storage.
     */
    suspend fun getModelBytes(modelId: String, fileSystem: PluginFileSystem): ByteArray? {
        val onnxRelPath = getModelOnnxRelativePath(modelId)
        val bytes = fileSystem.readFile(onnxRelPath)
        if (bytes != null && bytes.isNotEmpty()) return bytes
        val lowerOnnx = "$MODELS_DIR/${modelId.trim().lowercase()}.onnx".toRelativePath().getOrNull() ?: return null
        return fileSystem.readFile(lowerOnnx)
    }

    /**
     * Returns the absolute path to the locally saved model weights (.onnx or .gguf).
     */
    fun getModelAbsolutePath(modelId: String, fileSystem: PluginFileSystem): String {
        val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
        val catalogEntry = ModelCatalog.findById(modelId)
        val exactName = catalogEntry?.id ?: modelId.trim()
        val onnxPath = "$basePath/$MODELS_DIR/$exactName.onnx"
        if (File(onnxPath).exists()) return onnxPath
        val ggufPath = "$basePath/$MODELS_DIR/$exactName.gguf"
        if (File(ggufPath).exists()) return ggufPath
        val lowerOnnx = "$basePath/$MODELS_DIR/${modelId.trim().lowercase()}.onnx"
        if (File(lowerOnnx).exists()) return lowerOnnx
        val lowerGguf = "$basePath/$MODELS_DIR/${modelId.trim().lowercase()}.gguf"
        if (File(lowerGguf).exists()) return lowerGguf

        val lmStudioModel = findLmStudioModelFile(modelId)
        if (lmStudioModel != null && lmStudioModel.exists()) {
            return lmStudioModel.absolutePath
        }
        return onnxPath
    }

    /**
     * Creates an [OnnxInferenceSession] for the specified model ID.
     * Prefers loading directly from file path (zero JVM heap memory overhead) when the model
     * exists on disk, which also automatically loads companion `.onnx.data` external tensor files.
     */
    suspend fun createInferenceSession(
        modelId: String,
        fileSystem: PluginFileSystem,
        preferredDevice: ExecutionDevice = ExecutionDevice.AUTO,
        logger: PluginLogger? = null
    ): OnnxInferenceSession? {
        val modelPath = getModelAbsolutePath(modelId, fileSystem)
        val file = File(modelPath)
        if (file.exists() && file.length() > 0) {
            return try {
                OnnxInferenceEngine.createSession(file.absolutePath, preferredDevice, logger)
            } catch (e: Exception) {
                logger?.warn("Failed to create ONNX session from path '${file.absolutePath}': ${e.message}. Attempting bytes fallback.")
                null
            }
        }
        val lowerPath = "${fileSystem.getBasePath().trimEnd('/', '\\')}/$MODELS_DIR/${modelId.trim().lowercase()}.onnx"
        val lowerFile = File(lowerPath)
        if (lowerFile.exists() && lowerFile.length() > 0) {
            return try {
                OnnxInferenceEngine.createSession(lowerFile.absolutePath, preferredDevice, logger)
            } catch (e: Exception) {
                logger?.warn("Failed to create ONNX session from path '${lowerFile.absolutePath}': ${e.message}. Attempting bytes fallback.")
                null
            }
        }

        val bytes = getModelBytes(modelId, fileSystem) ?: return null
        if (bytes.isEmpty()) return null
        return try {
            OnnxInferenceEngine.createSession(bytes, preferredDevice, logger)
        } catch (e: Exception) {
            logger?.warn("Failed to create ONNX session from bytes for model '$modelId': ${e.message}")
            null
        }
    }

    /**
     * Downloads a single model (YAML descriptor and all companion weight files such as .onnx, .onnx.data, or .gguf)
     * and stores it in the plugin file system.
     */
    suspend fun downloadModel(modelId: String, context: PluginContext): Result<ModelSpec> {
        val logger: PluginLogger = context.logger
        val progress: ProgressReporter = context.progress
        val fileSystem: PluginFileSystem = context.fileSystem

        val catalogEntry = ModelCatalog.findById(modelId)
            ?: return Result.failure(IllegalArgumentException("Model with ID '$modelId' is not registered in the catalog."))

        if (isModelInstalled(modelId, fileSystem)) {
            logger.info("Model '${catalogEntry.displayName}' ($modelId) is already installed. Skipping download.")
            val existingSpec = getModelSpec(modelId, fileSystem)
            progress.report(1.0f)
            if (existingSpec != null) {
                return Result.success(existingSpec)
            }
        }

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

        // 2. Resolve all required companion files (.onnx, .onnx.data, .gguf, mmproj, etc.)
        val requiredFiles = mutableListOf<String>()
        requiredFiles.addAll(modelSpec.getRequiredFileNames(catalogEntry.id))
        for (extra in catalogEntry.extraFileUrls.keys) {
            if (!requiredFiles.contains(extra)) {
                requiredFiles.add(extra)
            }
        }
        val totalFiles = requiredFiles.size
        var totalBytesDownloaded: Long = 0

        for ((fileIdx, fileName) in requiredFiles.withIndex()) {
            val fileUrl = if (catalogEntry.extraFileUrls.containsKey(fileName)) {
                catalogEntry.extraFileUrls[fileName]!!
            } else if (fileName.equals(catalogEntry.onnxUrl.substringAfterLast('/'), ignoreCase = true)) {
                catalogEntry.onnxUrl
            } else {
                val baseUrl = if (catalogEntry.onnxUrl.isNotBlank()) catalogEntry.onnxUrl else catalogEntry.yamlUrl
                "${baseUrl.substringBeforeLast('/')}/$fileName"
            }

            logger.info("Downloading model file [${fileIdx + 1}/$totalFiles]: $fileName from $fileUrl")
            val fileBaseProgress = 0.15f + (fileIdx.toFloat() / totalFiles.toFloat()) * 0.80f
            val fileProgressRange = 0.80f / totalFiles.toFloat()

            val fileBytes = try {
                val response: HttpResponse = httpClient.get(fileUrl) {
                    onDownload { bytesSentTotal, contentLength ->
                        if (contentLength != null && contentLength > 0) {
                            val fileFrac = bytesSentTotal.toFloat() / contentLength.toFloat()
                            val overall = fileBaseProgress + fileFrac * fileProgressRange
                            progress.report(overall.coerceIn(0.15f, 0.95f))
                        }
                    }
                }
                if (response.status != HttpStatusCode.OK) {
                    return Result.failure(IllegalStateException("Failed to download $fileName: HTTP ${response.status.value}"))
                }
                response.readRawBytes()
            } catch (e: Exception) {
                logger.error("Error downloading file '$fileName': ${e.message}", e)
                return Result.failure(e)
            }

            totalBytesDownloaded += fileBytes.size
            val fileRelPath = "$MODELS_DIR/$fileName".toRelativePath().getOrElse {
                return Result.failure(it)
            }
            fileSystem.writeFile(fileRelPath, fileBytes).getOrElse {
                return Result.failure(it)
            }
        }

        // 3. Record installation in PluginStorage
        try {
            val storageObj = buildJsonObject {
                put("id", catalogEntry.id)
                put("name", modelSpec.name)
                put("type", modelSpec.type)
                put("format", modelSpec.format)
                put("installedAt", System.currentTimeMillis().toString())
                put("fileSize", totalBytesDownloaded)
                put("fileCount", totalFiles)
            }
            context.storage.put("$STORAGE_KEY_PREFIX${catalogEntry.id}", storageObj)
        } catch (e: Exception) {
            logger.warn("Could not save model metadata in PluginStorage: ${e.message}")
        }

        progress.report(1.0f)
        logger.info("Successfully installed model: ${catalogEntry.displayName} ($totalFiles files) to $MODELS_DIR/")
        return Result.success(modelSpec)
    }

    /**
     * Downloads a specific list of models by model ID sequentially.
     */
    suspend fun downloadModels(modelIds: List<String>, context: PluginContext): Result<List<ModelSpec>> {
        val results = mutableListOf<ModelSpec>()
        val total = modelIds.size
        for ((index, id) in modelIds.withIndex()) {
            context.logger.info("Downloading model [${index + 1}/$total]: $id")
            val result = downloadModel(id, context)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: RuntimeException("Failed to download $id"))
            }
            results.add(result.getOrThrow())
        }
        return Result.success(results)
    }

    /**
     * Downloads all models registered in the catalog sequentially.
     */
    suspend fun downloadAllModels(context: PluginContext): Result<List<ModelSpec>> {
        return downloadModels(ModelCatalog.ALL_MODELS.map { it.id }, context)
    }
}
