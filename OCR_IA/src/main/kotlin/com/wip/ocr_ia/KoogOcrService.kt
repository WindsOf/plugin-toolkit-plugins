package com.wip.ocr_ia

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.io.files.Path
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginSignal
import java.io.File

class KoogOcrService(private val context: PluginContext) {
    private val logger = context.logger
    private val progressReporter = context.progress
    private var isCancelled = false

    init {
        context.signals.onSignal { signal ->
            if (signal == PluginSignal.CANCEL) {
                isCancelled = true
            }
        }
    }

    suspend fun performOcr(
        input: String,
        save: Boolean,
        outputDir: String,
        apiKey: String
    ): String {
        val inputPath = java.nio.file.Paths.get(input)
        val files = mutableListOf<File>()

        if (java.nio.file.Files.isRegularFile(inputPath)) {
            files.add(inputPath.toFile())
        } else if (java.nio.file.Files.isDirectory(inputPath)) {
            val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")
            inputPath.toFile().listFiles { file ->
                file.extension.lowercase() in imageExtensions
            }?.sortedBy { it.name }?.let { files.addAll(it) }
        }

        if (files.isEmpty()) {
            return "No valid images found at $input"
        }

        val effectiveApiKey = apiKey.ifBlank { System.getenv("API_KEY") ?: "" }
        if (effectiveApiKey.isBlank()) {
            throw IllegalArgumentException("API Key not found. Provide it via parameter or environment variable.")
        }

        // Use SingleLLMPromptExecutor directly for single-shot OCR calls
        val executor = simpleGoogleAIExecutor(effectiveApiKey)
        // Custom model: gemma-4-31b-it via Google AI API
        // LLModel is built manually since GoogleModels only defines Gemini variants
        val model = LLModel(
            provider = LLMProvider.Google,
            id = "gemma-4-31b-it",
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Vision.Image,  // Required for OCR
            )
        )

        val promptInstructions = """
            Transcribe only the text contained in the image, maintaining the original layout. 
            DO NOT add introductions, notes, comments, or task analysis. 
            Return ONLY the extracted text.
        """.trimIndent()

        val results = StringBuilder()
        val totalFiles = files.size
        logger.info("Found $totalFiles images. Starting processing with Koog (${model.id})...")

        files.forEachIndexed { index, file ->
            if (isCancelled) {
                logger.info("OCR cancelled by user.")
                return@forEachIndexed
            }

            logger.info("Processing (${index + 1}/$totalFiles): ${file.name}...")

            try {
                val ocrPrompt = prompt("ocr-task") {
                    user {
                        text(promptInstructions)
                        image(Path(file.absolutePath))
                    }
                }

                // Execute the prompt directly without a full agent setup
                val responses = executor.execute(ocrPrompt, model)
                val rawText = responses.joinToString("\n") { it.content }
                var extractedText = rawText.trim()

                // Remove thinking blocks if present
                extractedText = extractedText.replace(
                    Regex("<(thought|thinking)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), ""
                ).trim()

                if (save) {
                    val outDir = if (outputDir.isNotBlank()) File(outputDir) else file.parentFile
                    if (!outDir.exists()) outDir.mkdirs()

                    val outFile = File(outDir, "${file.nameWithoutExtension}_OCR.txt")
                    outFile.writeText(extractedText)
                    logger.info("Saved to: ${outFile.absolutePath}")
                }

                results.appendLine("--- ${file.name} ---")
                results.appendLine(extractedText)
                results.appendLine("-".repeat(20))

                progressReporter.report((index + 1).toFloat() / totalFiles.toFloat())

            } catch (e: Exception) {
                logger.error("Error processing ${file.name}: ${e.message}")
                results.appendLine("--- ${file.name} (ERROR) ---")
                results.appendLine(e.message)
            }
        }

        val processed = if (isCancelled) "Cancelled" else "Completed"
        return "OCR $processed. Processed ${files.size} images.\n\n$results"
    }
}
