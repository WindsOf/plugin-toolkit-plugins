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
 * Result metadata from detecting a llama-server binary.
 */
data class LlamaDetectionResult(
    val found: Boolean,
    val executablePath: String? = null,
    val source: String? = null,
    val version: String? = null,
    val details: String? = null
)

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
         * Returns all possible binary names for llama-server or llama.
         */
        fun getExecutableCandidates(): List<String> {
            return if (LlamaBinaryDownloader.isWindows()) {
                listOf("llama-server.exe", "llama.exe", "llama-cli.exe")
            } else {
                listOf("llama-server", "llama", "llama-cli")
            }
        }

        /**
         * Searches system PATH for the llama-server or llama executable.
         */
        fun findInSystemPath(): String? {
            val pathEnv = System.getenv("PATH") ?: return null
            val exeCandidates = getExecutableCandidates()
            for (dir in pathEnv.split(File.pathSeparator)) {
                for (exeName in exeCandidates) {
                    val candidate = File(dir.trim(), exeName)
                    if (candidate.exists() && candidate.canExecute()) {
                        return candidate.absolutePath
                    }
                }
            }
            return null
        }

        /**
         * Searches common standard installation paths for the llama-server or llama executable.
         */
        fun findInCommonPaths(): String? {
            val exeCandidates = getExecutableCandidates()
            val userHome = System.getProperty("user.home", ".")
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"

            val candidateDirs = listOf(
                File(localAppData, "Microsoft/WindowsApps"),
                File(localAppData, "Microsoft/WindowsApps/llama.cpp"),
                File(localAppData, "Programs/llama.cpp"),
                File(localAppData, "llama.cpp"),
                File(userHome, ".llama/bin"),
                File(userHome, ".llama"),
                File(userHome, ".local/bin"),
                File(userHome, "llama.cpp"),
                File(userHome, "llama-server"),
                File("C:/llama.cpp"),
                File("C:/llama"),
                File(programFiles, "llama.cpp"),
                File(programFiles, "llama"),
                File("/usr/local/bin"),
                File("/usr/bin"),
                File("/opt/llama.cpp/bin"),
                File("/opt/llama/bin"),
                File("/opt/homebrew/bin"),
                File("/usr/local/opt/llama.cpp/bin")
            )

            for (dir in candidateDirs) {
                if (dir.exists()) {
                    for (exeName in exeCandidates) {
                        val directExe = File(dir, exeName)
                        if (directExe.exists() && directExe.canExecute()) {
                            return directExe.absolutePath
                        }
                    }
                }
            }
            return null
        }

        /**
         * Verifies whether an executable at [path] can run and returns execution metadata (version, build, backend).
         */
        fun verifyExecutable(path: String): Pair<Boolean, String?> {
            val file = File(path)
            if (!file.exists() || !file.canExecute()) {
                return false to null
            }
            val isWindows = LlamaBinaryDownloader.isWindows()
            val isWindowsApp = isWindows && path.contains("WindowsApps", ignoreCase = true)
            val exeName = file.nameWithoutExtension.lowercase()

            val baseArgs = if (exeName == "llama" || exeName == "llama-cli") {
                listOf(file.absolutePath, "serve", "--version")
            } else {
                listOf(file.absolutePath, "--version")
            }
            val versionArgs = if (isWindowsApp) listOf("cmd.exe", "/c") + baseArgs else baseArgs

            return try {
                val pb = ProcessBuilder(versionArgs)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val reader = proc.inputStream.bufferedReader()
                val output = StringBuilder()
                var line: String? = reader.readLine()
                var linesRead = 0
                while (line != null && linesRead < 10) {
                    output.append(line).append(" ")
                    linesRead++
                    line = reader.readLine()
                }
                proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                if (proc.isAlive) {
                    proc.descendants().forEach { it.destroyForcibly() }
                    proc.destroyForcibly()
                }
                val info = output.toString().trim()
                true to (if (info.isNotBlank()) info else "Executable OK")
            } catch (e: Exception) {
                try {
                    val helpBase = if (exeName == "llama" || exeName == "llama-cli") {
                        listOf(file.absolutePath, "serve", "--help")
                    } else {
                        listOf(file.absolutePath, "--help")
                    }
                    val helpArgs = if (isWindowsApp) listOf("cmd.exe", "/c") + helpBase else helpBase
                    val pb = ProcessBuilder(helpArgs)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (proc.isAlive) {
                        proc.descendants().forEach { it.destroyForcibly() }
                        proc.destroyForcibly()
                    }
                    true to "Executable OK"
                } catch (ex: Exception) {
                    true to "Executable present"
                }
            }
        }
    }

    private val mutex = Mutex()
    private var activeSession: LlamaServerSession? = null

    /**
     * Proactively detects any existing llama-server installations on the system, in plugin storage, or at a custom path.
     */
    fun detectInstallation(
        fileSystem: PluginFileSystem? = null,
        customPath: String? = null,
        logger: PluginLogger? = null
    ): LlamaDetectionResult {
        // 1. Custom path check
        if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath)
            if (customFile.exists() && customFile.canExecute()) {
                val (valid, ver) = verifyExecutable(customFile.absolutePath)
                if (valid) {
                    logger?.info("[LlamaServerManager] Detected llama-server in custom path: ${customFile.absolutePath} ($ver)")
                    return LlamaDetectionResult(
                        found = true,
                        executablePath = customFile.absolutePath,
                        source = "CUSTOM_PATH",
                        version = ver,
                        details = "Detected from configured custom path"
                    )
                }
            }
            if (customFile.isDirectory) {
                val inDir = File(customFile, LlamaBinaryDownloader.getExecutableName())
                if (inDir.exists() && inDir.canExecute()) {
                    val (valid, ver) = verifyExecutable(inDir.absolutePath)
                    if (valid) {
                        logger?.info("[LlamaServerManager] Detected llama-server in custom directory: ${inDir.absolutePath} ($ver)")
                        return LlamaDetectionResult(
                            found = true,
                            executablePath = inDir.absolutePath,
                            source = "CUSTOM_DIRECTORY",
                            version = ver,
                            details = "Detected from configured custom directory"
                        )
                    }
                }
            }
        }

        // 2. System PATH check
        val systemExe = findInSystemPath()
        if (systemExe != null) {
            val (valid, ver) = verifyExecutable(systemExe)
            if (valid) {
                logger?.info("[LlamaServerManager] Detected llama-server in system PATH: $systemExe ($ver)")
                return LlamaDetectionResult(
                    found = true,
                    executablePath = systemExe,
                    source = "SYSTEM_PATH",
                    version = ver,
                    details = "Detected in system PATH environment variable"
                )
            }
        }

        // 3. Common installation paths check
        val commonExe = findInCommonPaths()
        if (commonExe != null) {
            val (valid, ver) = verifyExecutable(commonExe)
            if (valid) {
                logger?.info("[LlamaServerManager] Detected llama-server in standard path: $commonExe ($ver)")
                return LlamaDetectionResult(
                    found = true,
                    executablePath = commonExe,
                    source = "STANDARD_DIRECTORY",
                    version = ver,
                    details = "Detected in standard application directory"
                )
            }
        }

        // 4. Local plugin storage check
        if (fileSystem != null) {
            for (backend in listOf(LlamaBackend.CUDA, LlamaBackend.VULKAN, LlamaBackend.CPU, LlamaBackend.AUTO)) {
                if (downloader.isInstalled(fileSystem, backend)) {
                    val localExe = downloader.getExecutablePath(fileSystem, backend)
                    val (valid, ver) = verifyExecutable(localExe)
                    if (valid) {
                        logger?.info("[LlamaServerManager] Detected llama-server in plugin storage: $localExe ($ver)")
                        return LlamaDetectionResult(
                            found = true,
                            executablePath = localExe,
                            source = "PLUGIN_STORAGE",
                            version = ver,
                            details = "Detected in local plugin storage ($backend backend)"
                        )
                    }
                }
            }
        }

        logger?.info("[LlamaServerManager] No existing llama-server installation found.")
        return LlamaDetectionResult(
            found = false,
            executablePath = null,
            source = null,
            version = null,
            details = "llama-server not found in PATH, standard paths, or plugin storage."
        )
    }

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
                val inDir = File(customFile , LlamaBinaryDownloader.getExecutableName())
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
            val commonExe = findInCommonPaths()
            if (commonExe != null) {
                logger?.info("[LlamaServerManager] Found llama-server in standard system directory: $commonExe")
                return commonExe
            }
            if (config.mode == LlamaServerMode.SYSTEM) {
                throw IllegalStateException("llama-server not found in system PATH or standard directories.")
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
        var consecutiveFailures = 0

        while (System.currentTimeMillis() < deadline) {
            if (client.checkHealth(baseUrl, logger)) {
                ready = true
                break
            }

            if (!process.isRunning) {
                val exitCode = process.exitCode
                if (exitCode != null && exitCode != 0) {
                    val stderrSnippet = process.lastStderr.ifBlank { "No stderr captured." }
                    logger?.error("[LlamaServerManager] llama-server process exited with code $exitCode during startup: $stderrSnippet")
                    throw IllegalStateException("llama-server process exited with code $exitCode during startup: $stderrSnippet")
                } else if (exitCode == 0) {
                    consecutiveFailures++
                    if (consecutiveFailures == 1) {
                        logger?.info("[LlamaServerManager] Launcher process completed with code 0 (detached/alias); waiting for server to bind $baseUrl...")
                    }
                }
            }

            delay(1000L)
        }

        if (!ready) {
            // Final health check check
            if (client.checkHealth(baseUrl, logger)) {
                ready = true
            } else {
                process.stop()
                val stderrSnippet = process.lastStderr
                val extraMsg = if (stderrSnippet.isNotBlank()) "\nRecent server output:\n$stderrSnippet" else ""
                throw IllegalStateException("llama-server failed to respond to health checks at $baseUrl within ${effectiveConfig.startupTimeoutSeconds}s.$extraMsg")
            }
        }

        logger?.info("[LlamaServerManager] llama-server successfully started and verified at $baseUrl")
        val session = LlamaServerSession(baseUrl = baseUrl, modelPath = modelPath, process = process, isRemote = false, mmprojPath = effectiveConfig.mmprojPath)
        activeSession = session
        return@withLock session
    }

    /**
     * Stops the currently active server session if running.
     */
    suspend fun stopActiveServer(logger: PluginLogger? = null) = mutex.withLock {
        val existing = activeSession
        if (existing != null) {
            logger?.info("[LlamaServerManager] Stopping active llama-server session at ${existing.baseUrl}...")
            existing.close()
            activeSession = null
        }
    }
}
