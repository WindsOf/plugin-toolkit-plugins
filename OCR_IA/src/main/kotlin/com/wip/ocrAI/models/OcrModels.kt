package com.wip.ocrAI.models

import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.PluginSetting

data class OcrIASettings(
    @PluginSetting(
        description = "API Key for Google services",
        required = true,
        secret = true
    )
    val googleApiKey: String = "",

    @PluginSetting(
        description = "API Key for Anthropic services",
        required = false,
        secret = true
    )
    val anthropicApiKey: String = "",

    @PluginSetting(
        description = "API Key for OpenAI services",
        required = false,
        secret = true
    )
    val openAIApiKey: String = "",

    @PluginSetting(
        description = "URL for LM Studio (e.g. http://localhost:1234/v1)",
        required = false
    )
    val lmStudioUrl: String = "http://localhost:1234/v1",

    @PluginSetting(
        description = "API Key for LM Studio",
        required = false,
        secret = true
    )
    val lmStudioApiKey: String = "lm-studio",

    @PluginSetting(
        description = "The specific model name to request from LM Studio",
        required = false
    )
    val lmStudioModelName: String = "default-model"
)

enum class AIModel(val id: String) {
    GEMMA_26B("gemma-4-26b-a4b-it"),
    GEMMA_31B("gemma-4-31b-it"),
    GEMINI_1_5_PRO("gemini-1.5-pro"),
    GEMINI_2_5_PRO("gemini-2.5-pro"),
    GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite"),
    CLAUDE_3_5_SONNET("claude-3-5-sonnet-20241022"),
    GPT_4O("gpt-4o"),
    LM_STUDIO("lm-studio")
}


