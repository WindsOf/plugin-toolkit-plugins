package com.wip.common.inference.llama

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.wip.plugintoolkit.api.PluginLogger

/**
 * Encapsulates an active llama-server operating system subprocess, capturing standard I/O
 * and managing graceful shutdown.
 */
class LlamaServerProcess(
    val executablePath: String,
    val modelPath: String,
    val config: LlamaServerConfig,
    private val logger: PluginLogger? = null
) : AutoCloseable {

    private var process: Process? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning: Boolean
        get() = process?.isAlive == true

    val exitCode: Int?
        get() = try { process?.exitValue() } catch (_: Exception) { null }

    private val recentStderr = mutableListOf<String>()
    val lastStderr: String
        get() = synchronized(recentStderr) { recentStderr.joinToString("\n") }

    /**
     * Starts the llama-server subprocess.
     */
    fun start(): Process {
        val exeFile = File(executablePath)
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val isWindowsApp = isWindows && executablePath.contains("WindowsApps", ignoreCase = true)

        val workingDir = if (isWindowsApp) {
            File(System.getProperty("user.home", "."))
        } else {
            exeFile.parentFile ?: File(".")
        }

        val rawArgs = mutableListOf<String>()
        val exeName = exeFile.nameWithoutExtension.lowercase()
        if (exeName == "llama" || exeName == "llama-cli") {
            rawArgs.add("serve")
        }
        rawArgs.add("-m")
        rawArgs.add(modelPath)
        if (!config.mmprojPath.isNullOrBlank()) {
            rawArgs.add("--mmproj")
            rawArgs.add(config.mmprojPath)
        }
        if (config.contextSize > 0) {
            rawArgs.add("-c")
            rawArgs.add(config.contextSize.toString())
        }
        rawArgs.add("--host")
        rawArgs.add(config.host)
        rawArgs.add("--port")
        rawArgs.add(config.port.toString())
        rawArgs.add("-ngl")
        rawArgs.add(config.gpuLayers.toString())
        rawArgs.add("-t")
        rawArgs.add(config.threads.toString())

        if (config.extraArgs.isNotEmpty()) {
            rawArgs.addAll(config.extraArgs)
        }

        val command = mutableListOf<String>()
        if (isWindowsApp) {
            // Windows App Execution Aliases require cmd.exe /c invocation for AppExecLink package DLL resolution
            command.add("cmd.exe")
            command.add("/c")
            command.add(executablePath)
            command.addAll(rawArgs)
        } else {
            command.add(executablePath)
            command.addAll(rawArgs)
        }

        logger?.info("[LlamaServerProcess] Spawning: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDir)

        // Propagate PATH / LD_LIBRARY_PATH so companion DLLs/.so files in the same directory are found
        val env = processBuilder.environment()
        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val existingPath = env[pathKey] ?: ""
        env[pathKey] = "${workingDir.absolutePath}${File.pathSeparator}$existingPath"

        val existingLd = env["LD_LIBRARY_PATH"] ?: ""
        env["LD_LIBRARY_PATH"] = "${workingDir.absolutePath}${File.pathSeparator}$existingLd"

        val proc = processBuilder.start()
        this.process = proc

        stdoutJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        logger?.info("[llama-server:stdout] $line")
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {
                // Stream closed
            }
        }

        stderrJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        logger?.info("[llama-server:stderr] $line")
                        synchronized(recentStderr) {
                            if (recentStderr.size > 30) recentStderr.removeAt(0)
                            recentStderr.add(line)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (_: Exception) {
                // Stream closed
            }
        }

        return proc
    }

    /**
     * Gracefully stops the process, with a fallback to forcible destruction.
     */
    fun stop(timeoutSeconds: Long = 5L) {
        val proc = process ?: return
        if (!proc.isAlive) return

        logger?.info("[LlamaServerProcess] Stopping llama-server process (PID=${proc.pid()})...")
        try {
            proc.descendants().forEach { child ->
                try { child.destroy() } catch (_: Exception) {}
            }
            proc.destroy()
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                logger?.warn("[LlamaServerProcess] llama-server did not terminate within ${timeoutSeconds}s. Forcing kill...")
                proc.descendants().forEach { child ->
                    try { child.destroyForcibly() } catch (_: Exception) {}
                }
                proc.destroyForcibly()
                proc.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            proc.descendants().forEach { child ->
                try { child.destroyForcibly() } catch (_: Exception) {}
            }
            proc.destroyForcibly()
        }

        stdoutJob?.cancel()
        stderrJob?.cancel()
        logger?.info("[LlamaServerProcess] llama-server process stopped.")
    }

    override fun close() {
        stop()
    }
}
