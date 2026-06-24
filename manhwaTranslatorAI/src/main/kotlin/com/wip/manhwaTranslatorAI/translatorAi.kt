package com.wip.manhwaTranslatorAI

import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.CapabilityFileAccess
import org.wip.plugintoolkit.api.HostFileSystem

data class TranslatorAISettings(
    @PluginSetting(
        description = "API Key for Google services",
        required = true,
        secret = true
    )
    val googleApiKey: String = "",

    @PluginSetting(
        description = "Use structured output (JSON schema). Disable if the model does not support it.",
        defaultValue = "true"
    )
    val useStructuredOutput: Boolean = true
)

enum class AIModel(val id: String) {
    GEMMA_26B("gemma-4-26b-a4b-it"),
    GEMMA_31B("gemma-4-31b-it"),
    GEMINI_3_5_FLASH("gemini-3.5-flash"),
    GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite")
}

@PluginInfo(
        id = "com.wip.manhwa_translator_ai",
        name = "Manhwa Translator AI",
        version = "1.3.6",
        description = "Translate text from Manhwa/Manga into Italian using Google AI via Koog"
)
class TranslatorAI(val settings: TranslatorAISettings) {
    @Capability(
        name = "translate",
        description = "Translates a list of strings into Italian using Google AI")
    @CapabilityFileAccess(readsFiles = true, writesFiles = true)
    suspend fun translate(
            @CapabilityParam(
                description = "List of text strings to translate"
            )
            input: List<String>,
            @CapabilityParam(
                description = "Dictionary of words/actions to keep the translation coherent",
                defaultValue = ""
            )
            dictionary: String? = "",
            @CapabilityParam(
                description = "The Gemini Model ID to use",
                defaultValue = "GEMMA_31B"
            )
            model: AIModel,
            @CapabilityParam(
                description = "List of page names matching the input texts (from OCR)",
                defaultValue = "[]"
            )
            pageNames: List<String>? = emptyList(),
            @CapabilityParam(
                description = "Path to the folder containing the images",
                semanticTypes = ["path/folder"]
            )
            inputFolder: String,
            @CapabilityParam(
                description = "Directory to save translation result",
                semanticTypes = ["path/folder"]
            )
            outputDir: String,
            @CapabilityParam(
                description = "Temporary directory for summary images",
                semanticTypes = ["path/folder"]
            )
            tempSummaryDir: String,
            @CapabilityParam(
                description = "Send images to AI for visual context (Requires Gemini 1.5/Gemma)",
                defaultValue = "false"
            )
            useContextImages: Boolean? = false,
            @CapabilityParam(
                description = "Generate a global summary context from all chapter images using Gemini 3.1 Flash Lite",
                defaultValue = "true"
            )
            generateChapterSummary: Boolean? = true,
            @CapabilityParam(
                description = "Save the translation result in a json",
                defaultValue = "true"
            )
            save: Boolean? = true,
            context: PluginContext,
            hostFs: HostFileSystem
    ): List<String> {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        val effectiveSummary = generateChapterSummary ?: true
        logger.info("Manhwa Translator AI started. Model: ${model.id}")
        logger.info("Input size: ${input.size} | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages | Global Summary: $effectiveSummary")

        return try {
            val service = KoogAITranslatorService(context, hostFs)
            val result = service.performTranslation(input, effectiveDict, settings.googleApiKey, settings.useStructuredOutput, model.id, pageNames, inputFolder, outputDir, tempSummaryDir, effectiveContextImages, effectiveSummary, save ?: true)
            logger.info("Translation completed.")
            result
        } catch (e: Throwable) {
            val msg = "Translation failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        context.logger.info("Manhwa Translator AI setup complete.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("Manhwa Translator AI update complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        context.logger.info("Manhwa Translator AI validation passed.")
        return Result.success(Unit)
    }
}
