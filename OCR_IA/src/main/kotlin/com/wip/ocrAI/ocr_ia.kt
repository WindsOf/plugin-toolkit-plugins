package com.wip.ocrAI

import com.wip.common.models.AIModel
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import com.wip.common.models.OcrIASettings
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.HostFileSystem

@PluginInfo(
    id = "com.wip.ocr_ia",
    name = "OCR IA",
    version = "2.4.8",
    description = "Advanced OCR plugin using Google AI, Anthropic, OpenAI, and LMStudio via Koog"
)
class OCR_IA(val settings: OcrIASettings = OcrIASettings()) {
    
    @Capability(
        name = "ocr",
        description = "Performs basic OCR on an image or a folder of images"
    )
    suspend fun ocr(
        @CapabilityInput(
            description = "Path to image or folder",
            semanticTypes = ["path/folder"]
        )
        input: String,
        @CapabilityParam(description = "Save output to a .txt file alongside each image", defaultValue = "true")
        save: Boolean,
        @CapabilityOutput(description = "Custom output directory", semanticTypes = ["path/folder"])
        outputDir: String,
        @CapabilityParam(description = "Whether to use native structured output (might not be supported by all models)", defaultValue = "false")
        useStructuredOutput: Boolean,
        @CapabilityParam(description = "Whether to save the thinking inside the json", defaultValue = "false")
        saveThinking: Boolean,
        @CapabilityParam(description = "The AI Model to use", defaultValue = "GEMMA_26B")
        model: AIModel,
        context: PluginContext,
        hostFs: HostFileSystem
    ): OCRResult {
        val logger = context.logger
        logger.info("OCR IA (Basic) v2.4.0 started. Model: ${model.id}")
        logger.info("Input: $input | Save: $save | OutputDir: '$outputDir' | StructuredOutput: $useStructuredOutput")

        return try {
            val service = KoogOcrService(context, settings, hostFs)
            val ocrResult = service.performOcr(input, save, outputDir, useStructuredOutput, saveThinking, model)
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

    @Capability(
        name = "advanced_ocr",
        description = "Performs advanced OCR extracting exact balloon and text boxes, shapes, text orientation, sparsity, and color."
    )
    suspend fun advancedOcr(
        @CapabilityInput(
            description = "Path to image or folder",
            semanticTypes = ["path/folder"]
        )
        input: String,
        @CapabilityParam(description = "Save output to a .json file alongside each image", defaultValue = "true")
        save: Boolean,
        @CapabilityOutput(description = "Custom output directory", semanticTypes = ["path/folder"])
        outputDir: String,
        @CapabilityParam(description = "Whether to use native structured output (might not be supported by all models)", defaultValue = "false")
        useStructuredOutput: Boolean,
        @CapabilityParam(description = "Whether to save the thinking inside the json", defaultValue = "false")
        saveThinking: Boolean,
        @CapabilityParam(description = "The AI Model to use", defaultValue = "GEMMA_31B")
        model: AIModel,
        context: PluginContext,
        hostFs: HostFileSystem
    ): AdvancedOCRResult {
        val logger = context.logger
        logger.info("OCR IA (Advanced) v2.4.0 started. Model: ${model.id}")
        logger.info("Input: $input | Save: $save | OutputDir: '$outputDir' | StructuredOutput: $useStructuredOutput")

        return try {
            val service = KoogOcrService(context, settings, hostFs)
            val ocrResult = service.performAdvancedOcr(input, save, outputDir, useStructuredOutput, saveThinking, model)
            AdvancedOCRResult(
                texts = ocrResult.texts,
                balloonBoxes = ocrResult.balloonBoxes,
                textBoxes = ocrResult.textBoxes,
                shapes = ocrResult.shapes,
                fontStyles = ocrResult.fontStyles,
                fontFamilies = ocrResult.fontFamilies,
                textAngles = ocrResult.textAngles,
                isSparse = ocrResult.isSparse,
                textColors = ocrResult.textColors,
                hasBorder = ocrResult.hasBorder,
                borderColors = ocrResult.borderColors,
                pageNumbers = ocrResult.pageNumbers,
                pageNames = ocrResult.pageNames,
                failedFiles = ocrResult.failedFiles
            )
        } catch (e: Throwable) {
            val msg = "Advanced OCR failed: ${e::class.simpleName}: ${e.message}"
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
