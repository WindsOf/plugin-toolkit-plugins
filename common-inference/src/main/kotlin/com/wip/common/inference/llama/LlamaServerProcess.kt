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

    /**
     * Starts the llama-server subprocess.
     */
    fun start(): Process {
        val exeFile = File(executablePath)
        val workingDir = exeFile.parentFile ?: File(".")

        val command = mutableListOf<String>()
        command.add(executablePath)
        val exeName = exeFile.nameWithoutExtension.lowercase()
        if (exeName == "llama" || exeName == "llama-cli") {
            command.add("serve")
        }
        command.add("-m")
        command.add(modelPath)
        if (!config.mmprojPath.isNullOrBlank()) {
            command.add("--mmproj")
            command.add(config.mmprojPath)
        }
        if (config.contextSize > 0) {
            command.add("-c")
            command.add(config.contextSize.toString())
        }
        command.add("--host")
        command.add(config.host)
        command.add("--port")
        command.add(config.port.toString())
        command.add("-ngl")
        command.add(config.gpuLayers.toString())
        command.add("-t")
        command.add(config.threads.toString())

        if (config.extraArgs.isNotEmpty()) {
            command.addAll(config.extraArgs)
        }

        logger?.info("[LlamaServerProcess] Spawning: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(workingDir)

        // Propagate PATH / LD_LIBRARY_PATH so companion DLLs/.so files in the same directory are found
        val env = processBuilder.environment()
        val existingPath = env["PATH"] ?: ""
        env["PATH"] = "${workingDir.absolutePath}${File.pathSeparator}$existingPath"

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
        proc.destroy()
        try {
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                logger?.warn("[LlamaServerProcess] llama-server did not terminate within ${timeoutSeconds}s. Forcing kill...")
                proc.destroyForcibly()
                proc.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
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
