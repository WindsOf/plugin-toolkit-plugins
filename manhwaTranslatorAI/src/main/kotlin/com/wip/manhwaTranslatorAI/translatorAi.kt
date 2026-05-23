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

@PluginInfo(
        id = "com.wip.manhwa_translator_ai",
        name = "Manhwa Translator AI",
        version = "1.0.1",
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
            context: PluginContext
    ): List<String> {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        logger.info("Manhwa Translator AI started.")
        logger.info("Input size: ${input.size} | Dictionary size: ${effectiveDict.length}")

        return try {
            val service = KoogAITranslatorService(context)
            service.performTranslation(input, effectiveDict, settings.googleApiKey, settings.useStructuredOutput)
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
