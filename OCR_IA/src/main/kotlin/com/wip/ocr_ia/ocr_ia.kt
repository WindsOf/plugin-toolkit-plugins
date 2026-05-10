package com.wip.ocr_ia

import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.Path
import kotlinx.io.buffered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.PluginSignal

@PluginInfo(
        id = "com.wip.ocr_ia",
        name = "OCR IA",
        version = "1.1.0",
        description = "Extract text from images using AI (Native Kotlin via Koog + Google Gemma-4-31b-it, or legacy Python)"
)

class OCR_IA {

    @Capability(name = "ocr", description = "Performs OCR on an image or a folder of images using native Kotlin (Koog)")
    suspend fun ocr(
            @CapabilityParam(description = "Path to image or folder") input: String,
            @CapabilityParam(description = "Save output to .txt file", defaultValue = "true")
            save: Boolean,
            @CapabilityParam(description = "Custom output directory", defaultValue = "")
            outputDir: String,
            @CapabilityParam(description = "Google GenAI API Key (optional)", defaultValue = "")
            apiKey: String,
            @CapabilityParam(description = "Use legacy Python implementation", defaultValue = "false")
            usePython: Boolean,
            context: PluginContext
    ): String {
        val logger = context.logger
        
        if (usePython) {
            logger.info("Using legacy Python implementation...")
            return runPythonOcr(input, save, outputDir, apiKey, context)
        }

        logger.info("Using native Kotlin (Koog) implementation...")
        val service = KoogOcrService(context)
        return service.performOcr(input, save, outputDir, apiKey)
    }

    private suspend fun runPythonOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        apiKey: String,
        context: PluginContext
    ): String {
        val logger = context.logger
        val fileSystem = context.fileSystem
        val progressReporter = context.progress
        val basePath = fileSystem.getBasePath()
        val pythonExe = findPythonExecutable(basePath)
        val mainScript = File(basePath, "main.py")

        if (!mainScript.exists()) {
            throw IllegalStateException(
                "main.py not found. Please run plugin setup."
            )
        }

        val command = mutableListOf<String>().apply {
            add(pythonExe)
            add(mainScript.absolutePath)
            add(input)
            if (save) add("-s")
            if (outputDir.isNotBlank()) {
                add("-o"); add(outputDir)
            }
            if (apiKey.isNotBlank()) {
                add("-k"); add(apiKey)
            }
        }

        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()

            context.signals.onSignal { signal ->
                if (signal == PluginSignal.CANCEL || signal == PluginSignal.PAUSE) {
                    process.destroyForcibly()
                }
            }

            val output = StringBuilder()
            var totalImages = 1
            var processedImages = 0

            process.inputStream.bufferedReader().use { reader ->
                reader.lines().forEach { line ->
                    logger.debug(line)
                    output.appendLine(line)
                    if (line.contains("Trovate ") && line.contains(" immagini")) {
                        val match = Regex("""Trovate (\d+) immagini""").find(line)
                        match?.let { totalImages = it.groupValues[1].toInt() }
                    }
                    if (line.startsWith("Elaborazione: ")) {
                        processedImages++
                        progressReporter.report((processedImages.toFloat() / totalImages).coerceIn(0f, 1f))
                    }
                }
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) throw RuntimeException("Python OCR failed ($exitCode)\n$output")
            "Python OCR completed. Processed $processedImages images.\n$output"
        }
    }


    private fun findPythonExecutable(basePath: String): String {
        // Try venv first (Windows)
        val venvPython = File(basePath, ".venv/Scripts/python.exe")
        if (venvPython.exists()) return venvPython.absolutePath

        // Try venv (Unix)
        val venvPythonUnix = File(basePath, ".venv/bin/python")
        if (venvPythonUnix.exists()) return venvPythonUnix.absolutePath

        // Fallback to system python
        return "python"
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        return try {
            val fileSystem = context.fileSystem
            val logger = context.logger
            logger.info("Starting OCR IA setup...")

            val resources = listOf("main.py")

            for (res in resources) {
                logger.info("Extracting $res...")
                val result = fileSystem.extractResource(res, res)
                if (result.isFailure) {
                    logger.warn("Failed to extract $res: ${result.exceptionOrNull()?.message}")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        return setup(context)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        val fileSystem = context.fileSystem
        val basePath = fileSystem.getBasePath()
        val mainScript = File(basePath, "main.py")

        return if (mainScript.exists()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("main.py missing. Please run setup."))
        }
    }
}
