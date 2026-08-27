package com.wip.common.inference.llama

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import org.wip.plugintoolkit.api.RelativePath
import org.wip.plugintoolkit.api.toRelativePath

/**
 * Manages downloading, unpacking, and resolving precompiled llama-server binaries and companion libraries.
 */
class LlamaBinaryDownloader(
    private val httpClient: HttpClient = HttpClient()
) {
    companion object {
        const val BINARIES_DIR = "bin/llama-server"
        const val BASE_DOWNLOAD_URL = "https://www.windsofresub.cloud/binaries/llama-server"
        val Default = LlamaBinaryDownloader()

        fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")
        fun isLinux(): Boolean = System.getProperty("os.name", "").lowercase().contains("linux")
        fun isMac(): Boolean = System.getProperty("os.name", "").lowercase().contains("mac")

        fun getExecutableName(): String = if (isWindows()) "llama-server.exe" else "llama-server"

        /**
         * Resolves the canonical archive filename based on the OS and requested backend.
         */
        fun resolveArchiveName(backend: LlamaBackend): String {
            val osPrefix = when {
                isWindows() -> "llama-server-win"
                isLinux() -> "llama-server-linux"
                isMac() -> "llama-server-macos"
                else -> "llama-server-win"
            }

            val backendSuffix = when (backend) {
                LlamaBackend.CUDA -> "cuda-cu12"
                LlamaBackend.VULKAN -> "vulkan"
                LlamaBackend.CPU -> "cpu"
                LlamaBackend.AUTO -> if (isWindows() || isLinux()) "cuda-cu12" else "cpu"
            }

            return "$osPrefix-$backendSuffix.zip"
        }
    }

    /**
     * Checks if the llama-server executable exists in plugin storage.
     */
    fun isInstalled(fileSystem: PluginFileSystem, backend: LlamaBackend = LlamaBackend.AUTO): Boolean {
        val exePath = getExecutablePath(fileSystem, backend)
        val file = File(exePath)
        return file.exists() && file.canExecute()
    }

    /**
     * Returns the absolute path to the local llama-server executable within plugin storage.
     */
    fun getExecutablePath(fileSystem: PluginFileSystem, backend: LlamaBackend = LlamaBackend.AUTO): String {
        val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
        val backendDir = when (backend) {
            LlamaBackend.CUDA -> "cuda"
            LlamaBackend.VULKAN -> "vulkan"
            LlamaBackend.CPU -> "cpu"
            LlamaBackend.AUTO -> "default"
        }
        val exeName = getExecutableName()
        val pathWithBackend = "$basePath/$BINARIES_DIR/$backendDir/$exeName"
        if (File(pathWithBackend).exists()) return pathWithBackend
        return "$basePath/$BINARIES_DIR/$exeName"
    }

    /**
     * Downloads and extracts the precompiled llama-server distribution into plugin storage.
     */
    suspend fun downloadAndInstall(
        fileSystem: PluginFileSystem,
        backend: LlamaBackend = LlamaBackend.AUTO,
        logger: PluginLogger? = null,
        progress: ProgressReporter? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val archiveName = resolveArchiveName(backend)
        val downloadUrl = "$BASE_DOWNLOAD_URL/$archiveName"
        logger?.info("[LlamaBinaryDownloader] Downloading llama-server ($backend) from: $downloadUrl")
        progress?.report(0.05f)

        val zipBytes = try {
            val response: HttpResponse = httpClient.get(downloadUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null && contentLength > 0) {
                        val frac = bytesSentTotal.toFloat() / contentLength.toFloat()
                        progress?.report((0.05f + frac * 0.75f).coerceIn(0.05f, 0.80f))
                    }
                }
            }
            if (response.status != HttpStatusCode.OK) {
                return@withContext Result.failure(IllegalStateException("Failed to download llama-server from $downloadUrl: HTTP ${response.status.value}"))
            }
            response.readRawBytes()
        } catch (e: Exception) {
            logger?.error("[LlamaBinaryDownloader] Error downloading llama-server archive: ${e.message}", e)
            return@withContext Result.failure(e)
        }

        progress?.report(0.85f)
        logger?.info("[LlamaBinaryDownloader] Download completed (${zipBytes.size} bytes). Extracting...")

        val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
        val targetDir = File("$basePath/$BINARIES_DIR")
        if (!targetDir.exists()) targetDir.mkdirs()

        try {
            extractZip(zipBytes, targetDir)
            progress?.report(1.0f)
            val exePath = getExecutablePath(fileSystem, backend)
            logger?.info("[LlamaBinaryDownloader] Successfully extracted llama-server to: $exePath")
            Result.success(exePath)
        } catch (e: Exception) {
            logger?.error("[LlamaBinaryDownloader] Error extracting llama-server archive: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Resolves the standard system installation directory for llama-server.
     */
    fun getSystemInstallDirectory(): File {
        val userHome = System.getProperty("user.home", ".")
        return if (isWindows()) {
            val localAppData = System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local"
            File(localAppData, "Programs/llama.cpp")
        } else {
            File(userHome, ".local/bin")
        }
    }

    /**
     * Extracts zip archive bytes into a target directory.
     */
    fun extractZip(zipBytes: ByteArray, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    if (!isWindows() && (destFile.name == "llama-server" || destFile.name.endsWith(".so"))) {
                        destFile.setExecutable(true, false)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Registers a directory to the User PATH environment variable (Windows/Linux/macOS).
     */
    fun addDirectoryToUserPath(directory: File, logger: PluginLogger? = null): Boolean {
        val dirPath = directory.absolutePath
        if (isWindows()) {
            return try {
                val script = """
                    ${'$'}currentPath = [Environment]::GetEnvironmentVariable('Path', 'User')
                    if (-not ${'$'}currentPath) { ${'$'}currentPath = "" }
                    ${'$'}entries = ${'$'}currentPath.Split(';', [System.StringSplitOptions]::RemoveEmptyEntries)
                    if (${'$'}entries -notcontains '$dirPath') {
                        ${'$'}newPath = if (${'$'}currentPath) { "${'$'}currentPath;$dirPath" } else { '$dirPath' }
                        [Environment]::SetEnvironmentVariable('Path', ${'$'}newPath, 'User')
                        Write-Output "ADDED"
                    } else {
                        Write-Output "ALREADY_PRESENT"
                    }
                """.trimIndent()
                val pb = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                logger?.info("[LlamaBinaryDownloader] PATH update result: $output for $dirPath")
                true
            } catch (e: Exception) {
                logger?.error("[LlamaBinaryDownloader] Failed to add $dirPath to Windows User PATH: ${e.message}", e)
                false
            }
        } else {
            // Linux/macOS ~/.profile, ~/.bashrc, ~/.zshrc handling
            return try {
                val userHome = System.getProperty("user.home", ".")
                val exportLine = "\nexport PATH=\"\$PATH:$dirPath\"\n"
                val profileFiles = listOf(
                    File(userHome, ".bashrc"),
                    File(userHome, ".zshrc"),
                    File(userHome, ".profile")
                )
                for (pf in profileFiles) {
                    if (pf.exists()) {
                        val text = pf.readText()
                        if (!text.contains(dirPath)) {
                            pf.appendText(exportLine)
                            logger?.info("[LlamaBinaryDownloader] Appended PATH export to ${pf.absolutePath}")
                        }
                    }
                }
                true
            } catch (e: Exception) {
                logger?.error("[LlamaBinaryDownloader] Failed to register PATH on Unix: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Downloads and installs llama-server system-wide and in local plugin storage.
     */
    suspend fun downloadAndInstallSystem(
        backend: LlamaBackend = LlamaBackend.AUTO,
        fileSystem: PluginFileSystem? = null,
        addToUserPath: Boolean = true,
        logger: PluginLogger? = null,
        progress: ProgressReporter? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val archiveName = resolveArchiveName(backend)
        val url = "$BASE_DOWNLOAD_URL/$archiveName"
        logger?.info("[LlamaBinaryDownloader] Downloading system llama-server package ($backend): $url")
        progress?.report(0.05f)

        val response: HttpResponse = try {
            httpClient.get(url) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength != null && contentLength > 0L) {
                        val pct = 0.05f + (bytesSentTotal.toFloat() / contentLength.toFloat()) * 0.75f
                        progress?.report(pct.coerceIn(0.05f, 0.80f))
                    }
                }
            }
        } catch (e: Exception) {
            logger?.error("[LlamaBinaryDownloader] Network error downloading from $url: ${e.message}", e)
            return@withContext Result.failure(e)
        }

        if (response.status != HttpStatusCode.OK) {
            val err = "Server responded with status ${response.status} when downloading $url"
            logger?.error("[LlamaBinaryDownloader] $err")
            return@withContext Result.failure(RuntimeException(err))
        }

        val zipBytes = response.readRawBytes()
        logger?.info("[LlamaBinaryDownloader] Download complete (${zipBytes.size} bytes). Extracting...")
        progress?.report(0.85f)

        val sysDir = getSystemInstallDirectory()
        try {
            extractZip(zipBytes, sysDir)
            val sysExe = File(sysDir, getExecutableName())
            if (!isWindows()) {
                sysExe.setExecutable(true, false)
            }
            logger?.info("[LlamaBinaryDownloader] Extracted system binary to: ${sysExe.absolutePath}")

            if (addToUserPath) {
                addDirectoryToUserPath(sysDir, logger)
            }

            if (fileSystem != null) {
                val basePath = fileSystem.getBasePath().trimEnd('/', '\\')
                val pluginTargetDir = File("$basePath/$BINARIES_DIR")
                extractZip(zipBytes, pluginTargetDir)
                logger?.info("[LlamaBinaryDownloader] Also extracted to local plugin directory: $pluginTargetDir")
            }

            progress?.report(1.0f)
            Result.success(sysExe.absolutePath)
        } catch (e: Exception) {
            logger?.error("[LlamaBinaryDownloader] Error installing system llama-server: ${e.message}", e)
            Result.failure(e)
        }
    }
}
