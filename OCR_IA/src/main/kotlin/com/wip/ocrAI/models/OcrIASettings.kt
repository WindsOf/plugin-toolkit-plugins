package com.wip.ocrAI.models

import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.RequiresLock
import org.wip.plugintoolkit.api.annotations.RequiresSetting

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
    val anthropicApiKey: String? = "",

    @PluginSetting(
        description = "API Key for OpenAI services",
        required = false,
        secret = true
    )
    val openAIApiKey: String? = "",

    @PluginSetting(
        description = "URL for LM Studio (e.g. http://localhost:1234/v1)",
        required = false
    )
    val lmStudioUrl: String? = "http://localhost:1234/v1",

    @PluginSetting(
        description = "API Key for LM Studio",
        required = false,
        secret = true
    )
    val lmStudioApiKey: String? = "lm-studio",

    @PluginSetting(
        description = "The specific model name to request from LM Studio",
        required = false
    )
    val lmStudioModelName: String? = "default-model"
)

enum class AIModel(val id: String) {
    GEMMA_26B("gemma-4-26b-a4b-it"),
    GEMMA_31B("gemma-4-31b-it"),
    GEMINI_1_5_PRO("gemini-1.5-pro"),
    GEMINI_2_5_PRO("gemini-2.5-pro"),
    GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite"),
    @RequiresSetting(["anthropicApiKey"])
    CLAUDE_3_5_SONNET("claude-3-5-sonnet-20241022"),
    @RequiresSetting(["openAIApiKey"])
    GPT_4O("gpt-4o"),
    @RequiresSetting(["lmStudioModelName", "lmStudioApiKey", "lmStudioUrl"])
    LM_STUDIO("lm-studio"),
    @RequiresLock(locks = ["model:Unlimited-OCR"])
    UNLIMITED_OCR("Unlimited-OCR")
}

enum class OcrDownloadModel(val modelId: String) {
    UNLIMITED_OCR("Unlimited-OCR")
}
