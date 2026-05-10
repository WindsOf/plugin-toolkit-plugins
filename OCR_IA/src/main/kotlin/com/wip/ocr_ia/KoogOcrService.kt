package com.wip.ocr_ia

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.io.files.Path
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginSignal
import java.io.File

/**
 * Kotlin-native OCR service using Koog + Google Gemma-4-31b-it.
 * Handles single image files or entire directories.
 */
class KoogOcrService(private val context: PluginContext) {
    private val logger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

    init {
        context.signals.onSignal { signal ->
            if (signal == PluginSignal.CANCEL) {
                isCancelled = true
                logger.info("Cancellation signal received.")
            }
        }
    }

    suspend fun performOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        apiKey: String
    ): String {
        // ── Resolve files ──────────────────────────────────────────────
        val inputPath = java.nio.file.Paths.get(input)
        val files = mutableListOf<File>()
        val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")

        when {
            java.nio.file.Files.isRegularFile(inputPath) -> {
                val f = inputPath.toFile()
                if (f.extension.lowercase() in imageExtensions) {
                    files.add(f)
                } else {
                    logger.error("File '${f.name}' is not a supported image format ($imageExtensions).")
                    return "Error: unsupported file format '${f.extension}'."
                }
            }
            java.nio.file.Files.isDirectory(inputPath) -> {
                inputPath.toFile()
                    .listFiles { f -> f.extension.lowercase() in imageExtensions }
                    ?.sortedBy { it.name }
                    ?.let { files.addAll(it) }
            }
            else -> {
                logger.error("Path '$input' does not exist.")
                return "Error: path '$input' does not exist."
            }
        }

        if (files.isEmpty()) {
            logger.warn("No supported images found at '$input'.")
            return "No images found at '$input'."
        }

        logger.info("Found ${files.size} image(s) to process.")

        // ── API Key ────────────────────────────────────────────────────
        val effectiveApiKey = apiKey.ifBlank { System.getenv("API_KEY") ?: "" }
        if (effectiveApiKey.isBlank()) {
            val msg = "API Key not found. Pass it via 'apiKey' parameter or set the API_KEY environment variable."
            logger.error(msg)
            throw IllegalArgumentException(msg)
        }

        // ── Executor + Model ───────────────────────────────────────────
        val executor = simpleGoogleAIExecutor(effectiveApiKey)
        val model = LLModel(
            provider = LLMProvider.Google,
            id = "gemma-4-31b-it",
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Vision.Image,
            )
        )
        logger.info("Using model: ${model.id}")

        // ── Prompt ─────────────────────────────────────────────────────
        val promptInstructions =
            "Transcribe only the text contained in the image, maintaining the original layout. " +
            "DO NOT add introductions, notes, comments, or task analysis. " +
            "Return ONLY the extracted text."

        // ── Process each image ─────────────────────────────────────────
        val results = StringBuilder()
        val total = files.size

        files.forEachIndexed { index, file ->
            if (isCancelled) {
                logger.info("Processing stopped at image ${index + 1}/$total.")
                return@forEachIndexed
            }

            logger.info("Processing image ${index + 1}/$total: '${file.name}'")

            try {
                val ocrPrompt = prompt("ocr-task") {
                    user {
                        text(promptInstructions)
                        image(Path(file.absolutePath))
                    }
                }

                logger.debug("Sending request to Google AI...")
                val responses = executor.execute(ocrPrompt, model)
                logger.debug("Response received. Parts: ${responses.size}")

                var extractedText = responses.joinToString("\n") { it.content }.trim()

                // Strip thinking blocks (some models include them)
                extractedText = extractedText.replace(
                    Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
                ).trim()

                logger.info("Extracted ${extractedText.length} characters from '${file.name}'.")

                if (save) {
                    val outDir = if (outputDir.isNotBlank()) File(outputDir) else file.parentFile
                    if (!outDir.exists()) {
                        outDir.mkdirs()
                        logger.info("Created output directory: ${outDir.absolutePath}")
                    }
                    val outFile = File(outDir, "${file.nameWithoutExtension}_OCR.txt")
                    outFile.writeText(extractedText, Charsets.UTF_8)
                    logger.info("Saved OCR result to: ${outFile.absolutePath}")
                }

                results.appendLine("=== ${file.name} ===")
                results.appendLine(extractedText)
                results.appendLine()

            } catch (e: Exception) {
                logger.error("Error processing '${file.name}': ${e::class.simpleName}: ${e.message}")
                results.appendLine("=== ${file.name} (ERROR) ===")
                results.appendLine("${e::class.simpleName}: ${e.message}")
                results.appendLine()
            }

            progressReporter.report((index + 1).toFloat() / total.toFloat())
        }

        val status = if (isCancelled) "cancelled" else "completed"
        val summary = "OCR $status. Processed ${files.size} image(s)."
        logger.info(summary)
        return "$summary\n\n$results"
    }
}
