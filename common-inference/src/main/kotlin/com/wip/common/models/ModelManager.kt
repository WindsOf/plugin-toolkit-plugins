package com.wip.common.models

import com.wip.common.inference.lmstudio.LmStudioManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
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
        fun getModelDirectoryName(catalogEntry: ModelCatalogEntry): String {
            val remoteFolder = catalogEntry.yamlUrl.substringAfter("/models/").substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
            if (remoteFolder != null) return remoteFolder
            if (catalogEntry.id.startsWith("Unlimited-OCR", ignoreCase = true)) return "Unlimited-OCR"
            return catalogEntry.id
        }

        private fun pathExists(fileSystem: PluginFileSystem, relPath: RelativePath): Boolean {
            val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
            if (basePath.isNotBlank()) {
                val f = File(basePath, relPath.value)
                if (f.exists() && f.length() > 0) return true
            }
            return try {
                runBlocking { fileSystem.exists(relPath) }
            } catch (_: Exception) {
                false
            }
        }

        private fun listModelsFiles(fileSystem: PluginFileSystem, relPath: RelativePath): List<String> {
            return try {
                runBlocking { fileSystem.listFiles(relPath) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun findModelFileRelativePath(modelId: String, fileName: String, fileSystem: PluginFileSystem): RelativePath? {
            val simpleName = fileName.substringAfterLast('/')
            val catalogEntry = ModelCatalog.findById(modelId)
            val exactName = catalogEntry?.id ?: modelId.trim()
            val clean = exactName.lowercase()
            val remoteFolder = catalogEntry?.yamlUrl?.substringAfter("/models/")?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

            val candidateFolders = listOfNotNull(
                remoteFolder,
                remoteFolder?.lowercase(),
                if (exactName.startsWith("Unlimited-OCR", ignoreCase = true)) "Unlimited-OCR" else null,
                exactName,
                clean
            ).distinct()

            for (folder in candidateFolders) {
                val path = "$MODELS_DIR/$folder/$simpleName".toRelativePath().getOrNull()
                if (path != null && pathExists(fileSystem, path)) return path
                val lowerPath = "$MODELS_DIR/$folder/${simpleName.lowercase()}".toRelativePath().getOrNull()
                if (lowerPath != null && pathExists(fileSystem, lowerPath)) return lowerPath
            }

            // Root models dir check (for existing/legacy installations)
            val rootPath = "$MODELS_DIR/$simpleName".toRelativePath().getOrNull()
            if (rootPath != null && pathExists(fileSystem, rootPath)) return rootPath
            val lowerRoot = "$MODELS_DIR/${simpleName.lowercase()}".toRelativePath().getOrNull()
            if (lowerRoot != null && pathExists(fileSystem, lowerRoot)) return lowerRoot

            // Subdirectory scan on disk: check if any immediate subfolder of models/ has this file
            val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
            if (basePath.isNotBlank()) {
                val modelsDir = File(basePath, MODELS_DIR)
                if (modelsDir.exists() && modelsDir.isDirectory) {
                    val subdirs = modelsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
                    for (subdir in subdirs) {
                        val child = File(subdir, simpleName)
                        if (child.exists() && child.length() > 0) {
                            return "$MODELS_DIR/${subdir.name}/$simpleName".toRelativePath().getOrNull()
                        }
                    }
                }
            }

            // Virtual fileSystem list scan (for in-memory file systems or tests)
            val modelsRel = MODELS_DIR.toRelativePath().getOrNull()
            if (modelsRel != null) {
                val allInModels = listModelsFiles(fileSystem, modelsRel)
                val matching = allInModels.firstOrNull {
                    it.endsWith("/$simpleName", ignoreCase = true) || it.endsWith("\\$simpleName", ignoreCase = true)
                }
                if (matching != null) {
                    return matching.toRelativePath().getOrNull()
                }
            }

            return null
        }

        /**
         * Resolves the target subfolder inside models/ for a given filename based on ModelCatalog entries.
         */
        fun resolveTargetFolderForFile(fileName: String): String? {
            val simpleName = fileName.substringAfterLast('/')

            if (simpleName.contains("Qwen3-VL-4B", ignoreCase = true)) {
                return "Qwen3-VL-4B-Instruct-GGUF"
            }
            if (simpleName.contains("Qwen3-VL-8B", ignoreCase = true)) {
                return "Qwen3-VL-8B-Instruct-GGUF"
            }
            if (simpleName.contains("Unlimited-OCR", ignoreCase = true) || simpleName.contains("unlimited_ocr", ignoreCase = true)) {
                return "Unlimited-OCR"
            }
            if (simpleName.contains("zitspp", ignoreCase = true) || simpleName.equals("tsr.onnx", ignoreCase = true)) {
                return "zitspp"
            }
            if (simpleName.contains("zits", ignoreCase = true)) {
                return "zits"
            }
            if (simpleName.contains("anime-manga-big-lama", ignoreCase = true)) {
                return "anime-manga-big-lama"
            }
            if (simpleName.contains("big-lama", ignoreCase = true) || simpleName.equals("lama.onnx", ignoreCase = true) || simpleName.equals("lama.yaml", ignoreCase = true)) {
                return "big-lama"
            }
            if (simpleName.contains("Places_512_FullData_G", ignoreCase = true)) {
                return "Places_512_FullData_G"
            }
            if (simpleName.contains("places_512_G", ignoreCase = true)) {
                return "places_512_G"
            }
            if (simpleName.contains("diffusion", ignoreCase = true)) {
                return "diffusion"
            }
            if (simpleName.contains("migan", ignoreCase = true)) {
                return "migan_traced"
            }
            if (simpleName.contains("yolo-det-x", ignoreCase = true)) {
                return "yolo-det-x-best-v3"
            }
            if (simpleName.contains("rfdetr", ignoreCase = true)) {
                return "rfdetr-seg-2xlarge-ema-v3"
            }

            for (entry in ModelCatalog.ALL_MODELS) {
                val target = getModelDirectoryName(entry)
                val yamlName = entry.yamlUrl.substringAfterLast('/')
                val onnxName = entry.onnxUrl.substringAfterLast('/')
                if (simpleName.equals(yamlName, ignoreCase = true) ||
                    simpleName.equals(onnxName, ignoreCase = true) ||
                    simpleName.equals("${entry.id}.yaml", ignoreCase = true) ||
                    simpleName.equals("${entry.id}.onnx", ignoreCase = true) ||
                    simpleName.equals("${entry.id}.gguf", ignoreCase = true) ||
                    simpleName.equals("${entry.id}.onnx.data", ignoreCase = true) ||
                    entry.extraFileUrls.keys.any { it.substringAfterLast('/').equals(simpleName, ignoreCase = true) }
                ) {
                    return target
                }
            }

            return null
        }
    }

    /**
     * Checks if the model YAML descriptor and all required model files exist in the plugin file system.
     */
    suspend fun isModelInstalled(modelId: String, fileSystem: PluginFileSystem, logger: PluginLogger? = null): Boolean {
        logger?.info("[ModelManager] Checking installation status for model '$modelId'...")
        val modelSpec = getModelSpec(modelId, fileSystem, logger)
        val catalogEntry = ModelCatalog.findById(modelId)
        val exactName = catalogEntry?.id ?: modelId.trim()

        if (modelSpec != null) {
            val requiredFiles = modelSpec.getRequiredFileNames(modelId)
            if (requiredFiles.isEmpty()) {
                logger?.warn("[ModelManager] Model '$modelId' spec has no required files listed. Returning false.")
                return false
            }
            val allPresent = requiredFiles.all { fileName ->
                val found = findModelFileRelativePath(modelId, fileName, fileSystem) != null
                logger?.info("[ModelManager] Model '$modelId': file '$fileName' exists = $found")
                found
            }
            logger?.info("[ModelManager] Model '$modelId' installation check by spec: all files present = $allPresent")
            return allPresent
        }

        // Fallback check if YAML spec could not be parsed but files might be present
        val yamlFileName = catalogEntry?.yamlUrl?.substringAfterLast('/') ?: "$exactName.yaml"
        val hasYaml = findModelFileRelativePath(modelId, yamlFileName, fileSystem) != null ||
                findModelFileRelativePath(modelId, "$exactName.yaml", fileSystem) != null ||
                findModelFileRelativePath(modelId, "${exactName.lowercase()}.yaml", fileSystem) != null

        val modelFileName = catalogEntry?.onnxUrl?.substringAfterLast('/') ?: "$exactName.onnx"
        val hasWeights = findModelFileRelativePath(modelId, modelFileName, fileSystem) != null ||
                findModelFileRelativePath(modelId, "$exactName.onnx", fileSystem) != null ||
                findModelFileRelativePath(modelId, "$exactName.gguf", fileSystem) != null ||
                findModelFileRelativePath(modelId, "${exactName.lowercase()}.onnx", fileSystem) != null ||
                findModelFileRelativePath(modelId, "${exactName.lowercase()}.gguf", fileSystem) != null ||
                findModelFileRelativePath(modelId, "generator.onnx", fileSystem) != null

        val fallbackResult = hasYaml && hasWeights
        logger?.info("[ModelManager] Model '$modelId' fallback check = $fallbackResult (yaml=$hasYaml, weights=$hasWeights)")
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
    fun findLmStudioMmprojFile(modelId: String? = null, modelFile: File? = null): File? {
        return LmStudioManager.Default.findLmStudioMmprojFile(modelId, modelFile)
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
                val foundRel = findModelFileRelativePath(modelId, extra, fileSystem)
                if (foundRel != null) {
                    val f = File(basePath, foundRel.value)
                    if (f.exists() && f.length() > 0) return f.absolutePath
                }
            }
        }

        val defaultRel = findModelFileRelativePath(modelId, "mmproj-Unlimited-OCR-F16.gguf", fileSystem)
        if (defaultRel != null) {
            val f = File(basePath, defaultRel.value)
            if (f.exists() && f.length() > 0) return f.absolutePath
        }

        return null
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
        val clean = modelId.trim().lowercase()
        val catalogEntry = ModelCatalog.findById(modelId)
        val exactName = catalogEntry?.id ?: modelId.trim()
        val catalogId = exactName.lowercase()
        val yamlFileName = catalogEntry?.yamlUrl?.substringAfterLast('/') ?: "$exactName.yaml"

        val candidatePaths = listOfNotNull(
            findModelFileRelativePath(modelId, yamlFileName, fileSystem),
            findModelFileRelativePath(modelId, "$exactName.yaml", fileSystem),
            findModelFileRelativePath(modelId, "$clean.yaml", fileSystem),
            getModelYamlRelativePath(modelId),
            "$MODELS_DIR/$clean/$clean.yaml".toRelativePath().getOrNull(),
            "$MODELS_DIR/$clean.yaml".toRelativePath().getOrNull(),
            "$MODELS_DIR/$catalogId/$catalogId.yaml".toRelativePath().getOrNull(),
            "$MODELS_DIR/$catalogId.yaml".toRelativePath().getOrNull()
        ).distinct()
        var yamlText: String? = null
        for (candidate in candidatePaths) {
            if (fileSystem.exists(candidate)) {
                yamlText = try {
                    fileSystem.readTextFile(candidate)
                } catch (e: Exception) {
                    logger?.warn("[ModelManager] Failed reading text file at $candidate: ${e.message}")
                    null
                }
                if (yamlText != null) break
            }
        }

        if (yamlText == null) {
            val devDir = System.getProperty("wip.dev.models.dir") ?: System.getenv("WIP_DEV_MODELS_DIR")
            if (!devDir.isNullOrBlank()) {
                val localYamlCandidates = listOf(
                    File("$devDir/$clean/$clean.yaml"),
                    File("$devDir/$clean.yaml"),
                    File("$devDir/$clean/generator.yaml"),
                    File("$devDir/$clean/zits.yaml"),
                    File("$devDir/$clean/zitspp.yaml")
                )
                val localYaml = localYamlCandidates.firstOrNull { it.exists() && it.length() > 0 }
                if (localYaml != null) {
                    try {
                        yamlText = localYaml.readText()
                    } catch (e: Exception) {
                        logger?.warn("[ModelManager] Failed reading local YAML at ${localYaml.absolutePath}: ${e.message}")
                    }
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
        val onnxFileName = catalogEntry?.onnxUrl?.substringAfterLast('/')

        val fileNamesToCheck = listOfNotNull(
            onnxFileName,
            "$exactName.onnx",
            "$exactName.gguf",
            "${exactName.lowercase()}.onnx",
            "${exactName.lowercase()}.gguf",
            "generator.onnx"
        ).distinct()

        var foundRelPath: RelativePath? = null
        for (fileName in fileNamesToCheck) {
            val foundRel = findModelFileRelativePath(modelId, fileName, fileSystem)
            if (foundRel != null) {
                if (foundRelPath == null) foundRelPath = foundRel
                val f = File(basePath, foundRel.value)
                if (f.exists() && f.length() > 0) return f.absolutePath
            }
        }
        if (foundRelPath != null) {
            return if (basePath.isNotEmpty()) "$basePath/${foundRelPath.value}" else foundRelPath.value
        }

        val devDir = System.getProperty("wip.dev.models.dir") ?: System.getenv("WIP_DEV_MODELS_DIR")
        if (!devDir.isNullOrBlank()) {
            val clean = exactName.lowercase()
            val localCandidates = listOf(
                File("$devDir/$clean/generator.onnx"),
                File("$devDir/$clean/$clean.onnx"),
                File("$devDir/$clean.onnx"),
                File("$devDir/$clean/$clean.gguf"),
                File("$devDir/$clean.gguf"),
                File("$devDir/$exactName.onnx"),
                File("$devDir/$exactName.gguf")
            )
            val localMatch = localCandidates.firstOrNull { it.exists() && it.length() > 0 }
            if (localMatch != null) return localMatch.absolutePath
        }

        val targetFolder = if (catalogEntry != null) getModelDirectoryName(catalogEntry) else exactName
        val fallbackExt = if (catalogEntry?.format.equals("gguf", ignoreCase = true)) "gguf" else "onnx"
        val expectedFile = onnxFileName ?: "$exactName.$fallbackExt"
        return "$basePath/$MODELS_DIR/$targetFolder/$expectedFile"
    }

    /**
     * Resolves a companion/component file for a multi-stage or multi-component model.
     */
    fun getModelComponentFile(modelId: String, componentFile: String, fileSystem: PluginFileSystem): File? {
        val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
        val simpleName = componentFile.substringAfterLast('/')

        val foundRel = findModelFileRelativePath(modelId, simpleName, fileSystem)
        if (foundRel != null) {
            val f = File(basePath, foundRel.value)
            if (f.exists() && f.length() > 0) return f
            if (basePath.isNotEmpty()) return f
        }

        val clean = modelId.trim().lowercase()
        val localCandidates = mutableListOf<File>()
        if (basePath.isNotEmpty()) {
            localCandidates.add(File("$basePath/$MODELS_DIR/$clean/$simpleName"))
            localCandidates.add(File("$basePath/$MODELS_DIR/$clean/$componentFile"))
            localCandidates.add(File("$basePath/$MODELS_DIR/$componentFile"))
            localCandidates.add(File("$basePath/$MODELS_DIR/$simpleName"))
        }
        val devDir = System.getProperty("wip.dev.models.dir") ?: System.getenv("WIP_DEV_MODELS_DIR")
        if (!devDir.isNullOrBlank()) {
            localCandidates.add(File("$devDir/$clean/$simpleName"))
            localCandidates.add(File("$devDir/$clean/$componentFile"))
            localCandidates.add(File("$devDir/$componentFile"))
            localCandidates.add(File("$devDir/$simpleName"))
        }
        return localCandidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    /**
     * Creates an [OnnxInferenceSession] map for multi-component models (e.g. TSR, SSU, Generator).
     */
    suspend fun createInferenceSessions(
        modelId: String,
        fileSystem: PluginFileSystem,
        preferredDevice: ExecutionDevice = ExecutionDevice.AUTO,
        logger: PluginLogger? = null
    ): Map<String, OnnxInferenceSession> {
        val spec = getModelSpec(modelId, fileSystem, logger) ?: return emptyMap()
        val sessions = mutableMapOf<String, OnnxInferenceSession>()
        for ((compName, compSpec) in spec.components) {
            val f = getModelComponentFile(modelId, compSpec.file, fileSystem)
            if (f != null && f.exists() && f.length() > 0) {
                try {
                    val session = OnnxInferenceEngine.createSession(f.absolutePath, preferredDevice, logger)
                    sessions[compName] = session
                } catch (e: Exception) {
                    logger?.warn("[ModelManager] Failed creating session for component '$compName' (${f.absolutePath}): ${e.message}")
                }
            }
        }
        return sessions
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
        val targetFolderName = getModelDirectoryName(catalogEntry)
        val yamlFileName = catalogEntry.yamlUrl.substringAfterLast('/')

        val folderYamlRel = "$MODELS_DIR/$targetFolderName/$yamlFileName".toRelativePath().getOrNull()
        if (folderYamlRel != null) {
            fileSystem.writeTextFile(folderYamlRel, yamlText).getOrElse {
                return Result.failure(it)
            }
        }
        val yamlRelPath = getModelYamlRelativePath(catalogEntry.id)
        if (yamlRelPath != folderYamlRel) {
            fileSystem.writeTextFile(yamlRelPath, yamlText)
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

        for ((fileIdx, rawFileName) in requiredFiles.withIndex()) {
            val fileName = rawFileName.substringAfterLast('/')
            val fileUrl = if (catalogEntry.extraFileUrls.containsKey(rawFileName)) {
                catalogEntry.extraFileUrls[rawFileName]!!
            } else if (catalogEntry.extraFileUrls.containsKey(fileName)) {
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
            val effectiveRelPath = "$MODELS_DIR/$targetFolderName/$fileName"
            val fileRelPath = effectiveRelPath.toRelativePath().getOrElse {
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
        logger.info("Successfully installed model: ${catalogEntry.displayName} ($totalFiles files) to $MODELS_DIR/$targetFolderName/")
        return Result.success(modelSpec)
    }

    /**
     * Inspects the plugin's local models/ directory and moves loose, root-level model files
     * into organized model-specific subdirectories (e.g. Qwen3-VL-4B-Instruct-GGUF, zits, big-lama, etc.).
     *
     * If a target destination already contains an identical file, the loose duplicate is removed
     * to conserve storage space.
     *
     * @return Result containing the count of files successfully organized/moved.
     */
    suspend fun organizeModelsDirectory(
        fileSystem: PluginFileSystem,
        logger: PluginLogger? = null
    ): Result<Int> {
        var movedCount = 0
        try {
            logger?.info("[ModelManager] Checking models directory for loose files to organize...")
            val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
            val diskModelsDir = if (basePath.isNotBlank()) File(basePath, MODELS_DIR) else null

            if (diskModelsDir != null && diskModelsDir.exists() && diskModelsDir.isDirectory) {
                val looseFiles = diskModelsDir.listFiles { f -> f.isFile } ?: emptyArray()
                for (file in looseFiles) {
                    val targetSub = resolveTargetFolderForFile(file.name)
                    if (targetSub != null) {
                        val targetDir = File(diskModelsDir, targetSub)
                        if (!targetDir.exists()) {
                            targetDir.mkdirs()
                        }
                        val destFile = File(targetDir, file.name)
                        if (destFile.exists() && destFile.length() == file.length() && file.length() > 0) {
                            logger?.info("[ModelManager] Duplicate file in models root: ${file.name} already exists in $targetSub. Removing redundant root copy.")
                            file.delete()
                            movedCount++
                        } else {
                            logger?.info("[ModelManager] Moving ${file.name} -> $targetSub/${file.name}")
                            try {
                                Files.move(
                                    file.toPath(),
                                    destFile.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING
                                )
                                movedCount++
                            } catch (e: Exception) {
                                logger?.warn("[ModelManager] Failed moving ${file.name} to $destFile: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                // In-memory or virtual file system fallback (e.g. tests)
                val modelsRel = MODELS_DIR.toRelativePath().getOrNull()
                if (modelsRel != null) {
                    val allFiles = try { fileSystem.listFiles(modelsRel) } catch (_: Exception) { emptyList() }
                    for (filePath in allFiles) {
                        val sub = filePath.removePrefix("$MODELS_DIR/").removePrefix("$MODELS_DIR\\")
                        if (!sub.contains('/') && !sub.contains('\\') && sub.isNotEmpty()) {
                            val fileName = sub
                            val targetSub = resolveTargetFolderForFile(fileName)
                            if (targetSub != null) {
                                val srcRel = "$MODELS_DIR/$fileName".toRelativePath().getOrNull()
                                val destRel = "$MODELS_DIR/$targetSub/$fileName".toRelativePath().getOrNull()
                                if (srcRel != null && destRel != null) {
                                    if (fileSystem.exists(destRel)) {
                                        fileSystem.deleteFile(srcRel)
                                        movedCount++
                                    } else {
                                        val bytes = fileSystem.readFile(srcRel)
                                        if (bytes != null) {
                                            fileSystem.writeFile(destRel, bytes)
                                            fileSystem.deleteFile(srcRel)
                                            movedCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            logger?.info("[ModelManager] Models directory organization finished. Total files moved/organized: $movedCount")
            return Result.success(movedCount)
        } catch (e: Exception) {
            logger?.error("[ModelManager] Error organizing models directory: ${e.message}", e)
            return Result.failure(e)
        }
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
