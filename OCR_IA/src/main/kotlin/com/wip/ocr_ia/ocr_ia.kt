package com.wip.ocr_ia

import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
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
    val googleApiKey: String = "",
)

@PluginInfo(
        id = "com.wip.ocr_ia",
        name = "OCR IA",
        version = "2.0.3",
        description = "Extract text from images using Google Gemma-4-31b-it via Koog (Kotlin)"
)
class OCR_IA(val settings: OcrIASettings) {
    @Capability(name = "ocr", description = "Performs OCR on an image or a folder of images using Google Gemma-4-31b-it")
    suspend fun ocr(
            @CapabilityParam(description = "Path to image or folder") input: String,
            @CapabilityParam(description = "Save output to a .txt file alongside each image", defaultValue = "true")
            save: Boolean,
            @CapabilityParam(description = "Custom output directory (optional)", defaultValue = "")
            outputDir: String,
            context: PluginContext
    ): String {
        val logger = context.logger
        logger.info("OCR IA v2.0.0 started.")
        logger.info("Input: $input | Save: $save | OutputDir: '${outputDir.ifBlank { "<same as image>" }}'")

        return try {
            val service = KoogOcrService(context)
            service.performOcr(input, save, outputDir, settings.googleApiKey)
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
