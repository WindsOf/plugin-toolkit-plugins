package com.wip.manhwaTranslatorAI

import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate

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
    GEMINI_3_5_FLASH("gemini-3.5-flash")
}

@PluginInfo(
        id = "com.wip.manhwa_translator_ai",
        name = "Manhwa Translator AI",
        version = "1.3.3",
        description = "Translate text from Manhwa/Manga into Italian using Google AI via Koog"
)
class TranslatorAI(val settings: TranslatorAISettings) {
    @Capability(
        name = "translate",
        description = "Translates a list of strings into Italian using Google AI")
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
                defaultValue = "\"\""
            )
            inputFolder: String? = "",
            @CapabilityParam(
                description = "Send images to AI for visual context (Requires Gemini 1.5/Gemma)",
                defaultValue = "false"
            )
            useContextImages: Boolean? = false,
            context: PluginContext
    ): List<String> {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        logger.info("Manhwa Translator AI started. Model: ${model.id}")
        logger.info("Input size: ${input.size} | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages")

        return try {
            val service = KoogAITranslatorService(context)
            service.performTranslation(input, effectiveDict, settings.googleApiKey, settings.useStructuredOutput, model.id, pageNames, inputFolder, effectiveContextImages)
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
