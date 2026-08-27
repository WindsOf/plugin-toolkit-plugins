package com.wip.ocrAI.models

import com.wip.common.inference.llama.LlamaBackend
import com.wip.common.inference.llama.LlamaServerMode
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
    val lmStudioModelName: String? = "default-model",

    @PluginSetting(
        description = "llama-server resolution mode: AUTO (detect or download), SYSTEM (PATH only), CUSTOM (custom path), or REMOTE",
        defaultValue = "\"AUTO\"",
        required = false
    )
    val llamaServerMode: LlamaServerMode? = LlamaServerMode.AUTO,

    @PluginSetting(
        description = "Custom path to llama-server binary executable or directory",
        defaultValue = "\"\"",
        required = false
    )
    val llamaServerCustomPath: String? = "",

    @PluginSetting(
        description = "Hardware acceleration backend for local llama-server: AUTO, CUDA, VULKAN, or CPU",
        defaultValue = "\"AUTO\"",
        required = false
    )
    val llamaServerBackend: LlamaBackend? = LlamaBackend.AUTO,

    @PluginSetting(
        description = "Number of model layers to offload to GPU in llama-server (e.g. 99 for full GPU offload, 0 for CPU)",
        defaultValue = "99",
        required = false
    )
    val llamaServerGpuLayers: Int? = 99,

    @PluginSetting(
        description = "TCP port for local llama-server process (0 for dynamic free port)",
        defaultValue = "8080",
        required = false
    )
    val llamaServerPort: Int? = 8080,

    @PluginSetting(
        description = "Context size in tokens for local llama-server (default 8192 for Unlimited-OCR)",
        defaultValue = "8192",
        required = false
    )
    val llamaServerContextSize: Int? = 8192
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
    UNLIMITED_OCR("Unlimited-OCR"),
    @RequiresLock(locks = ["model:Unlimited-OCR-BF16"])
    UNLIMITED_OCR_BF16("Unlimited-OCR-BF16"),
    @RequiresLock(locks = ["model:Unlimited-OCR-Q8_0"])
    UNLIMITED_OCR_Q8_0("Unlimited-OCR-Q8_0"),
    @RequiresLock(locks = ["model:Unlimited-OCR-Q4_K_M"])
    UNLIMITED_OCR_Q4_K_M("Unlimited-OCR-Q4_K_M"),
    @RequiresLock(locks = ["model:Unlimited-OCR-IQ2_M"])
    UNLIMITED_OCR_IQ2_M("Unlimited-OCR-IQ2_M")
}

enum class OcrDownloadModel(val modelId: String) {
    UNLIMITED_OCR_BF16("Unlimited-OCR-BF16"),
    UNLIMITED_OCR_Q8_0("Unlimited-OCR-Q8_0"),
    UNLIMITED_OCR_Q4_K_M("Unlimited-OCR-Q4_K_M"),
    UNLIMITED_OCR_IQ2_M("Unlimited-OCR-IQ2_M")
}
