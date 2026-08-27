package com.wip.common.inference.llama

import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter

/**
 * Handle representing an active llama-server session.
 */
data class LlamaServerSession(
    val baseUrl: String,
    val modelPath: String,
    val process: LlamaServerProcess?,
    val isRemote: Boolean = false,
    val mmprojPath: String? = null
) : AutoCloseable {
    override fun close() {
        process?.close()
    }
}

/**
 * Central manager for discovering, downloading, starting, and stopping llama-server instances.
 */
class LlamaServerManager(
    private val downloader: LlamaBinaryDownloader = LlamaBinaryDownloader.Default,
    private val client: LlamaInferenceClient = LlamaInferenceClient.Default
) {
    companion object {
        val Default = LlamaServerManager()

        /**
         * Finds an available free TCP port on localhost.
         */
        fun findFreePort(): Int {
            return ServerSocket(0).use { it.localPort }
        }

        /**
         * Searches system PATH for the llama-server executable.
         */
        fun findInSystemPath(): String? {
            val exeName = LlamaBinaryDownloader.getExecutableName()
            val pathEnv = System.getenv("PATH") ?: return null
            for (dir in pathEnv.split(File.pathSeparator)) {
                val candidate = File(dir.trim(), exeName)
                if (candidate.exists() && candidate.canExecute()) {
                    return candidate.absolutePath
                }
            }
            return null
        }
    }

    private val mutex = Mutex()
    private var activeSession: LlamaServerSession? = null

    /**
     * Resolves the executable path to use for llama-server according to configuration and system state.
     */
    suspend fun resolveExecutable(
        config: LlamaServerConfig,
        fileSystem: PluginFileSystem,
        logger: PluginLogger? = null,
        progress: ProgressReporter? = null
    ): String {
        // 1. Custom path configured by user
        if (!config.customPath.isNullOrBlank()) {
            val customFile = File(config.customPath)
            if (customFile.exists() && customFile.canExecute()) {
                logger?.info("[LlamaServerManager] Using custom llama-server path: ${customFile.absolutePath}")
                return customFile.absolutePath
            }
            if (customFile.isDirectory) {
                val inDir = File(customFile, LlamaBinaryDownloader.getExecutableName())
                if (inDir.exists() && inDir.canExecute()) {
                    logger?.info("[LlamaServerManager] Found llama-server in custom directory: ${inDir.absolutePath}")
                    return inDir.absolutePath
                }
            }
            logger?.warn("[LlamaServerManager] Specified customPath '${config.customPath}' is invalid or not executable.")
            if (config.mode == LlamaServerMode.CUSTOM) {
                throw IllegalArgumentException("Custom llama-server path does not exist or is not executable: ${config.customPath}")
            }
        }

        // 2. System PATH check
        if (config.mode == LlamaServerMode.SYSTEM || config.mode == LlamaServerMode.AUTO) {
            val systemExe = findInSystemPath()
            if (systemExe != null) {
                logger?.info("[LlamaServerManager] Found llama-server in system PATH: $systemExe")
                return systemExe
            }
            if (config.mode == LlamaServerMode.SYSTEM) {
                throw IllegalStateException("llama-server not found in system PATH.")
            }
        }

        // 3. Local plugin storage check
        if (downloader.isInstalled(fileSystem, config.backend)) {
            val localExe = downloader.getExecutablePath(fileSystem, config.backend)
            logger?.info("[LlamaServerManager] Found installed llama-server in plugin storage: $localExe")
            return localExe
        }

        // 4. Download on-demand if in AUTO mode
        if (config.mode == LlamaServerMode.AUTO) {
            logger?.info("[LlamaServerManager] llama-server not found locally. Initiating managed download...")
            val downloadResult = downloader.downloadAndInstall(fileSystem, config.backend, logger, progress)
            if (downloadResult.isSuccess) {
                return downloadResult.getOrThrow()
            } else {
                throw IllegalStateException("Failed to download and install llama-server: ${downloadResult.exceptionOrNull()?.message}", downloadResult.exceptionOrNull())
            }
        }

        throw IllegalStateException("llama-server binary could not be resolved for mode: ${config.mode}")
    }

    /**
     * Ensures a llama-server instance is running with the specified GGUF model.
     * Reuses the current session if the same model is already active.
     */
    suspend fun getOrStartServer(
        modelPath: String,
        config: LlamaServerConfig,
        fileSystem: PluginFileSystem,
        logger: PluginLogger? = null,
        progress: ProgressReporter? = null
    ): LlamaServerSession = mutex.withLock {
        // If remote URL is provided or mode is REMOTE
        if (config.mode == LlamaServerMode.REMOTE || !config.remoteUrl.isNullOrBlank()) {
            val baseUrl = (config.remoteUrl ?: "http://${config.host}:${config.port}").trimEnd('/')
            logger?.info("[LlamaServerManager] Using remote llama-server URL: $baseUrl")
            val isHealthy = client.checkHealth(baseUrl, logger)
            if (!isHealthy) {
                logger?.warn("[LlamaServerManager] Remote server at $baseUrl did not pass initial health check.")
            }
            return@withLock LlamaServerSession(baseUrl = baseUrl, modelPath = modelPath, process = null, isRemote = true)
        }

        // Check if existing session can be reused
        val existing = activeSession
        if (existing != null && existing.process?.isRunning == true && existing.modelPath == modelPath && existing.mmprojPath == config.mmprojPath) {
            val isHealthy = client.checkHealth(existing.baseUrl, logger)
            if (isHealthy) {
                logger?.info("[LlamaServerManager] Reusing existing running llama-server session at ${existing.baseUrl}")
                return@withLock existing
            } else {
                logger?.warn("[LlamaServerManager] Existing session at ${existing.baseUrl} is unresponsive. Restarting...")
                existing.close()
                activeSession = null
            }
        } else if (existing != null) {
            logger?.info("[LlamaServerManager] Model/mmproj changed (previous: ${existing.modelPath} [${existing.mmprojPath}], new: $modelPath [${config.mmprojPath}]). Stopping previous server...")
            existing.close()
            activeSession = null
        }

        val executablePath = resolveExecutable(config, fileSystem, logger, progress)
        val port = if (config.port > 0) config.port else findFreePort()
        val effectiveConfig = config.copy(port = port)
        val baseUrl = "http://${effectiveConfig.host}:$port"

        logger?.info("[LlamaServerManager] Launching llama-server on $baseUrl with model: $modelPath")
        val process = LlamaServerProcess(
            executablePath = executablePath,
            modelPath = modelPath,
            config = effectiveConfig,
            logger = logger
        )
        process.start()

        // Wait for health check
        val deadline = System.currentTimeMillis() + (effectiveConfig.startupTimeoutSeconds * 1000L)
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (!process.isRunning) {
                throw IllegalStateException("llama-server process terminated prematurely during startup.")
            }
            if (client.checkHealth(baseUrl, logger)) {
                ready = true
                break
            }
            delay(500L)
        }

        if (!ready) {
            process.stop()
            throw IllegalStateException("llama-server failed to respond to health checks at $baseUrl within ${effectiveConfig.startupTimeoutSeconds}s.")
        }

        logger?.info("[LlamaServerManager] llama-server successfully started and verified at $baseUrl")
        val session = LlamaServerSession(baseUrl = baseUrl, modelPath = modelPath, process = process, isRemote = false, mmprojPath = effectiveConfig.mmprojPath)
        activeSession = session
        return@withLock session
    }

    /**
     * Stops the currently active server session if running.
     */
    suspend fun stopActiveServer() = mutex.withLock {
        activeSession?.close()
        activeSession = null
    }
}
