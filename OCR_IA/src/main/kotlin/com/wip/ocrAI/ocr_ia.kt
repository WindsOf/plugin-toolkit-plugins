package com.wip.ocrAI

import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate

data class OcrIASettings(
    @PluginSetting(
        description = "API Key for Google services",
        required = true,
        secret = true
    )
    val googleApiKey: String = ""
)

enum class AIModel(val id: String) {
    GEMMA_26B("gemma-4-26b-a4b-it"),
    GEMMA_31B("gemma-4-31b-it")
}

@Serializable
data class OCRResult(
    @CapabilityOutput(
        name = "extracted text",
        description = "a list of strings representing the extracted text")
    val texts: List<String>,
    @CapabilityOutput(
        name = "bounding box",
        description = "(xmin, ymin, xmax, ymax), a list of lists of doubles representing the bounding box coordinates for each extracted text",
        semanticTypes = ["wom/bounding-box"]
    )
    val bb: List<List<Double>>,
    @CapabilityOutput(
        name = "page number",
        description = "a list of integers representing the page number (1-indexed) for each extracted text"
    )
    val pageNumbers: List<Int>,
    @CapabilityOutput(
        name = "page name",
        description = "a list of strings representing the filename/page name for each extracted text"
    )
    val pageNames: List<String>,
    @CapabilityOutput(
        name = "failed files",
        description = "a list of strings representing the filenames that failed to process"
    )
    val failedFiles: List<String>
)

@PluginInfo(
        id = "com.wip.ocr_ia",
        name = "OCR IA",
        version = "2.3.2",
        description = "Advanced OCR plugin using Google AI (Gemma 4 31B/26B) via Koog"
)
class OCR_IA(val settings: OcrIASettings) {
    @Capability(
        name = "ocr",
        description = "Performs OCR on an image or a folder of images using Google Gemma-4-26b-a4b-it")
    suspend fun ocr(
            @CapabilityParam(
                description = "Path to image or folder",
                semanticTypes = ["sys/directory"]
            )
            input: String,
            @CapabilityParam(description = "Save output to a .txt file alongside each image", defaultValue = "true")
            save: Boolean,
            @CapabilityParam(description = "Custom output directory (optional)", defaultValue = "")
            outputDir: String? = "",
            @CapabilityParam(description = "Whether to use native structured output (might not be supported by all models)", defaultValue = "false")
            useStructuredOutput: Boolean,
            @CapabilityParam(description = "The Gemini Model ID to use", defaultValue = "GEMMA_26B")
            model: AIModel,
            context: PluginContext
    ): OCRResult {
        val logger = context.logger
        val effectiveOutputDir = outputDir ?: ""
        logger.info("OCR IA v2.3.1 started. Model: ${model.id}")
        logger.info("Input: $input | Save: $save | OutputDir: '${effectiveOutputDir.ifBlank { "<same as image>" }}' | StructuredOutput: $useStructuredOutput")

        return try {
            val service = KoogOcrService(context)
            val ocrResult = service.performOcr(input, save, effectiveOutputDir, settings.googleApiKey, useStructuredOutput, model.id)
            OCRResult(ocrResult.texts, ocrResult.bb, ocrResult.pageNumbers, ocrResult.pageNames, ocrResult.failedFiles)
        } catch (e: Throwable) {
            val msg = "OCR failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        context.logger.info("OCR IA setup complete. No external dependencies required.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("OCR IA update complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        context.logger.info("OCR IA validation passed.")
        return Result.success(Unit)
    }
}
