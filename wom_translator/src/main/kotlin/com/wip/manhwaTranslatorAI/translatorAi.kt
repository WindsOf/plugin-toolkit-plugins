package com.wip.manhwaTranslatorAI

import com.wip.common.inference.deepseek.DeepSeekManager
import com.wip.common.inference.llm.ReasoningEffortLevel
import com.wip.common.inference.lmstudio.LmStudioManager
import com.wip.common.inference.zai.ZaiManager
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import com.wip.common.models.OcrTextFilter
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.wip.plugintoolkit.api.ConditionOperator
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.DependsOn
import org.wip.plugintoolkit.api.annotations.PluginAction
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginLoad
import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.RequiresSetting

data class TranslatorAISettings(
    @PluginSetting(
        description = "API Key for Google services",
        required = true,
        secret = true
    )
    val googleApiKey: String = "",

    @PluginSetting(
        description = "Use structured output (JSON schema). Disable if the model does not support it.",
        defaultValue = "true",
        required = true
    )
    val useStructuredOutput: Boolean = true,

    @PluginSetting(
        description = "Maximum number of dialogue lines per translation chunk (default 25)",
        defaultValue = "25",
        required = false
    )
    val chunkSize: Int? = 25,

    @PluginSetting(
        description = "Print raw LLM model prompts and responses to logs for debugging",
        defaultValue = "false",
        required = false
    )
    val debugLogging: Boolean? = false,

    @PluginSetting(
        description = "API Key for DeepSeek services",
        required = false,
        secret = true
    )
    val deepseekApiKey: String? = "",

    @PluginSetting(
        description = "Base URL for DeepSeek API (e.g. https://api.deepseek.com)",
        defaultValue = "https://api.deepseek.com",
        required = false
    )
    val deepseekBaseUrl: String? = "https://api.deepseek.com",

    @PluginSetting(
        description = "API Key for Z.AI (GLM) services",
        required = false,
        secret = true
    )
    val zaiApiKey: String? = "",

    @PluginSetting(
        description = "Base URL for Z.AI API (e.g. https://api.z.ai/api/paas/v4)",
        defaultValue = "https://api.z.ai/api/paas/v4",
        required = false
    )
    val zaiBaseUrl: String? = "https://api.z.ai/api/paas/v4",

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
    GEMINI_3_5_FLASH("gemini-3.5-flash"),
    GEMINI_3_6_FLASH("gemini-3.6-flash"),
    GEMINI_3_7_FLASH("gemini-3.7-flash"),
    GEMINI_3_8_FLASH("gemini-3.8-flash"),
    GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite"),

    @RequiresSetting(["deepseekApiKey"])
    DEEPSEEK_FLASH("deepseek-flash"),

    @RequiresSetting(["deepseekApiKey"])
    DEEPSEEK_PRO("deepseek-v4-pro"),

    @RequiresSetting(["zaiApiKey"])
    GLM_5_3_FLASH("glm-5.3-flash"),

    @RequiresSetting(["zaiApiKey"])
    GLM_5_3_FLASHX("glm-5.3-flashx"),

    @RequiresSetting(["zaiApiKey"])
    GLM_4_7_FLASH("glm-4.7-flash"),

    @RequiresSetting(["lmStudioModelName", "lmStudioApiKey", "lmStudioUrl"])
    LM_STUDIO("lm-studio");

    companion object {
        val ZAI_GLM_5_3_FLASH: AIModel get() = GLM_5_3_FLASH
        val ZAI_GLM_5_3_FLASHX: AIModel get() = GLM_5_3_FLASHX
        val ZAI_GLM_4_7_FLASH: AIModel get() = GLM_4_7_FLASH

        fun fromId(id: String): AIModel? {
            val clean = id.trim().lowercase()
            return entries.firstOrNull { it.id.equals(clean, ignoreCase = true) }
        }
    }
}

enum class ApiProvider(val displayName: String) {
    DEEPSEEK("DeepSeek"),
    GOOGLE("Google Gemini"),
    LM_STUDIO("LM Studio"),
    ZAI("Z.AI")
}

enum class StructuredOutputMode(val displayName: String) {
    DEFAULT("Default (Use Settings)"),
    ENABLED("Enabled"),
    DISABLED("Disabled")
}

/**
 * Text element categories targeted for translation.
 */
enum class TranslationTargetClass {
    speech,
    sfx,
    none
}

@PluginInfo(
    id = "com.wip.manhwa_translator_ai",
    name = "WOM Translator",
    version = "1.8.0",
    description = "Translate text from Manhwa/Manga into Italian using Google AI, DeepSeek, or Z.AI via Koog",
    supportedOs = [OS.WINDOWS]
)
class TranslatorAI(val settings: TranslatorAISettings) {

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[TranslatorAI] onLoad: Initializing Manhwa Translator AI (has googleApiKey: ${settings.googleApiKey.isNotBlank()}, has deepseekApiKey: ${!settings.deepseekApiKey.isNullOrBlank()}, has zaiApiKey: ${!settings.zaiApiKey.isNullOrBlank()}, useStructuredOutput: ${settings.useStructuredOutput}, chunkSize: ${settings.chunkSize}, debugLogging: ${settings.debugLogging}, lmStudioUrl: ${settings.lmStudioUrl})")
        return Result.success(Unit)
    }

    @PluginAction(
        name = "Test API Connection",
        description = "Checks connectivity to the selected AI provider (DeepSeek, Google, or LM Studio) and verifies active API keys"
    )
    suspend fun testApiConnection(
        @CapabilityParam(
            description = "Select the API provider to test",
            defaultValue = "DEEPSEEK"
        )
        provider: ApiProvider? = ApiProvider.DEEPSEEK,
        context: PluginContext
    ) {
        val logger = context.logger
        val effectiveProvider = provider ?: ApiProvider.DEEPSEEK
        when (effectiveProvider) {
            ApiProvider.DEEPSEEK -> {
                val key = settings.deepseekApiKey?.ifBlank { System.getenv("DEEPSEEK_API_KEY") ?: "" } ?: ""
                val url = settings.deepseekBaseUrl?.ifBlank { DeepSeekManager.DEFAULT_BASE_URL } ?: DeepSeekManager.DEFAULT_BASE_URL
                if (key.isBlank()) {
                    val msg = "DeepSeek API key is not configured. Please set deepseekApiKey in settings."
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                    return
                }
                logger.info("[TranslatorAI] Testing DeepSeek connection at: $url")
                val status = DeepSeekManager.Default.checkStatus(baseUrl = url, apiKey = key, logger = logger)
                if (status.connected) {
                    val modelsDesc = if (status.models.isNotEmpty()) " (Models: ${status.models.joinToString()})" else " (Models: deepseek-flash, deepseek-v4-pro)"
                    val msg = "Connected to DeepSeek at $url successfully!$modelsDesc"
                    logger.info("[TranslatorAI] $msg")
                    context.showToast(msg)
                } else {
                    val err = status.errorMessage ?: "Connection failed"
                    val msg = "Failed to connect to DeepSeek at $url: $err"
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                }
            }
            ApiProvider.GOOGLE -> {
                val key = settings.googleApiKey.ifBlank { System.getenv("API_KEY") ?: "" }
                if (key.isBlank()) {
                    val msg = "Google API key is not configured. Please set googleApiKey in settings."
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                    return
                }
                logger.info("[TranslatorAI] Testing Google Gemini connection...")
                try {
                    val client = DeepSeekManager.createDefaultHttpClient()
                    val modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
                    val response = client.get(modelsUrl)
                    if (response.status == HttpStatusCode.OK) {
                        val msg = "Connected to Google Gemini successfully! Available models: Gemini 3.8 Flash, Gemini 3.7 Flash, Gemma 31B."
                        logger.info("[TranslatorAI] $msg")
                        context.showToast(msg)
                    } else {
                        val msg = "Failed to connect to Google Gemini (HTTP ${response.status.value}): ${response.status.description}"
                        logger.warn("[TranslatorAI] $msg")
                        context.showToast(msg)
                    }
                } catch (e: Exception) {
                    val msg = "Failed to connect to Google Gemini: ${e.message ?: "Network error"}"
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                }
            }
            ApiProvider.LM_STUDIO -> {
                val url = settings.lmStudioUrl?.ifBlank { "http://localhost:1234/v1" } ?: "http://localhost:1234/v1"
                logger.info("[TranslatorAI] Testing LM Studio connection at: $url")
                val status = LmStudioManager.Default.checkStatus(baseUrl = url, apiKey = settings.lmStudioApiKey, logger = logger)
                if (status.connected) {
                    val modelDesc = if (!status.activeModel.isNullOrBlank()) " (Active model: ${status.activeModel})" else ""
                    val msg = "Connected to LM Studio at $url successfully!$modelDesc"
                    logger.info("[TranslatorAI] $msg")
                    context.showToast(msg)
                } else {
                    val err = status.errorMessage ?: "Connection refused or unreachable"
                    val msg = "Failed to connect to LM Studio at $url: $err"
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                }
            }
            ApiProvider.ZAI -> {
                val key = settings.zaiApiKey?.ifBlank { System.getenv("ZAI_API_KEY") ?: "" } ?: ""
                val url = settings.zaiBaseUrl?.ifBlank { ZaiManager.DEFAULT_BASE_URL } ?: ZaiManager.DEFAULT_BASE_URL
                if (key.isBlank()) {
                    val msg = "Z.AI API key is not configured. Please set zaiApiKey in settings or ZAI_API_KEY environment variable."
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                    return
                }
                logger.info("[TranslatorAI] Testing Z.AI connection at: $url")
                val status = ZaiManager.Default.checkStatus(baseUrl = url, apiKey = key, logger = logger)
                if (status.connected) {
                    val modelsDesc = if (status.models.isNotEmpty()) " (Models: ${status.models.joinToString()})" else " (Models: glm-5.3-flash, glm-5.3-flashx, glm-4.7-flash)"
                    val msg = "Connected to Z.AI at $url successfully!$modelsDesc"
                    logger.info("[TranslatorAI] $msg")
                    context.showToast(msg)
                } else {
                    val err = status.errorMessage ?: "Connection failed"
                    val msg = "Failed to connect to Z.AI at $url: $err"
                    logger.warn("[TranslatorAI] $msg")
                    context.showToast(msg)
                }
            }
        }
    }

    fun isHallucination(rawText: String?): Boolean = OcrTextFilter.isHallucinationOrEmpty(rawText)

    @Capability(
        name = "translate_ocr",
        description = "Translates an OCRResult into Italian using Google AI"
    )
    suspend fun translateOcr(
        @CapabilityParam(
            description = "The OCR Result to translate"
        )
        inputOcr: OCRResult,
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
        @CapabilityInput(
            description = "Path to the folder containing the images",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String? = "",
        @CapabilityOutput(
            description = "Directory to save translation result",
            autogeneratedPattern = "{model}/translation",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityOutput(
            description = "Temporary directory for summary images",
            autogeneratedPattern = "{model}/temp_summary",
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
        @CapabilityParam(
            description = "Enable thinking/reasoning process for compatible models",
            defaultValue = "true",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        enableThinking: Boolean? = true,
        @CapabilityParam(
            description = "Reasoning effort level for reasoning-capable models (DEFAULT, LOW, MEDIUM, HIGH)",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        @CapabilityParam(
            description = "Structured output mode (JSON). DEFAULT uses the global plugin setting.",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        structuredOutput: StructuredOutputMode? = StructuredOutputMode.DEFAULT,
        @CapabilityParam(
            description = "Print raw LLM model responses to the log for debugging",
            defaultValue = "false",
            isAdvanced = true
        )
        debugLogging: Boolean? = false,
        @CapabilityParam(
            description = "Element categories to include in translation",
            defaultValue = "[\"speech\", \"sfx\"]"
        )
        target_classes: List<TranslationTargetClass> = listOf(
            TranslationTargetClass.speech,
            TranslationTargetClass.sfx
        ),
        context: PluginContext,
        hostFs: HostFileSystem
    ): OCRResult {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        val effectiveSummary = generateChapterSummary ?: true
        val effectiveStructuredOutput = when (structuredOutput ?: StructuredOutputMode.DEFAULT) {
            StructuredOutputMode.DEFAULT -> settings.useStructuredOutput
            StructuredOutputMode.ENABLED -> true
            StructuredOutputMode.DISABLED -> false
        }
        val effectiveDebug = debugLogging ?: settings.debugLogging ?: false

        val validIndices = inputOcr.texts.indices.filter { !isHallucination(inputOcr.texts[it]) }
        if (validIndices.isEmpty()) {
            logger.info("Basic OCR Translation: No valid texts to translate after filtering empty/hallucinations.")
            return inputOcr.copy(
                texts = emptyList(),
                bb = emptyList(),
                pageNumbers = emptyList(),
                pageNames = emptyList(),
                categories = emptyList()
            )
        }

        val cleanCategories = validIndices.map { inputOcr.categories.getOrElse(it) { "none" } }

        val cleanOcr = OCRResult(
            texts = validIndices.map { inputOcr.texts[it] },
            bb = validIndices.map { inputOcr.bb.getOrElse(it) { emptyList() } },
            pageNumbers = validIndices.map { inputOcr.pageNumbers.getOrElse(it) { 1 } },
            pageNames = validIndices.map { inputOcr.pageNames.getOrElse(it) { "" } },
            failedFiles = inputOcr.failedFiles,
            categories = cleanCategories
        )

        val allowedCategoryNames = target_classes.map { it.name.lowercase() }.toSet()
        val translationIndices = cleanOcr.texts.indices.filter { idx ->
            cleanCategories.getOrElse(idx) { "none" }.lowercase() in allowedCategoryNames
        }

        if (translationIndices.isEmpty()) {
            logger.info("Basic OCR Translation: No texts match target_classes filter ($target_classes). Skipping translation.")
            return cleanOcr
        }

        val textsToTranslate = translationIndices.map { cleanOcr.texts[it] }
        val pagesToTranslate = translationIndices.map { cleanOcr.pageNames[it] }

        logger.info("Manhwa Translator AI (Basic OCRResult) started. Model: ${model.id}")
        logger.info("Input size: ${textsToTranslate.size} to translate (total: ${cleanOcr.texts.size}, raw: ${inputOcr.texts.size}) | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages | Global Summary: $effectiveSummary")

        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val translatedSubTexts = service.performTranslation(
                input = textsToTranslate,
                dictionary = effectiveDict,
                apiKey = settings.googleApiKey,
                useStructuredOutput = effectiveStructuredOutput,
                modelId = model.id,
                pageNames = pagesToTranslate,
                inputFolder = inputFolder,
                outputDir = outputDir,
                tempSummaryDir = tempSummaryDir,
                useContextImages = effectiveContextImages,
                generateChapterSummary = effectiveSummary,
                save = save ?: true,
                enableThinking = enableThinking ?: true,
                reasoningEffort = reasoningEffort ?: ReasoningEffortLevel.DEFAULT,
                debugLogging = effectiveDebug
            )
            logger.info("Basic OCR Translation completed.")
            val finalTexts = cleanOcr.texts.toMutableList()
            for ((subIdx, origIdx) in translationIndices.withIndex()) {
                if (subIdx < translatedSubTexts.size) {
                    finalTexts[origIdx] = translatedSubTexts[subIdx]
                }
            }
            cleanOcr.copy(texts = finalTexts, categories = cleanCategories)
        } catch (e: Throwable) {
            val msg = "Basic OCR Translation failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    @Capability(
        name = "translate",
        description = "Translates a list of strings into Italian using Google AI"
    )
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
        @CapabilityInput(
            description = "Path to the folder containing the images",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String,
        @CapabilityOutput(
            description = "Directory to save translation result",
            autogeneratedPattern = "{model}/translation",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityOutput(
            description = "Temporary directory for summary images",
            autogeneratedPattern = "{model}/temp_summary",
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
        @CapabilityParam(
            description = "Enable thinking/reasoning process for compatible models",
            defaultValue = "true",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        enableThinking: Boolean? = true,
        @CapabilityParam(
            description = "Reasoning effort level for reasoning-capable models (DEFAULT, LOW, MEDIUM, HIGH)",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        @CapabilityParam(
            description = "Structured output mode (JSON). DEFAULT uses the global plugin setting.",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        structuredOutput: StructuredOutputMode? = StructuredOutputMode.DEFAULT,
        @CapabilityParam(
            description = "Print raw LLM model responses to the log for debugging",
            defaultValue = "false",
            isAdvanced = true
        )
        debugLogging: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): List<String> {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        val effectiveSummary = generateChapterSummary ?: true
        val effectiveStructuredOutput = when (structuredOutput ?: StructuredOutputMode.DEFAULT) {
            StructuredOutputMode.DEFAULT -> settings.useStructuredOutput
            StructuredOutputMode.ENABLED -> true
            StructuredOutputMode.DISABLED -> false
        }
        val effectiveDebug = debugLogging ?: settings.debugLogging ?: false
        logger.info("Manhwa Translator AI started. Model: ${model.id}")
        logger.info("Input size: ${input.size} | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages | Global Summary: $effectiveSummary")

        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val result = service.performTranslation(
                input,
                effectiveDict,
                settings.googleApiKey,
                effectiveStructuredOutput,
                model.id,
                pageNames,
                inputFolder,
                outputDir,
                tempSummaryDir,
                effectiveContextImages,
                effectiveSummary,
                save ?: true,
                enableThinking = enableThinking ?: true,
                reasoningEffort = reasoningEffort ?: ReasoningEffortLevel.DEFAULT,
                debugLogging = effectiveDebug
            )
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

    @Capability(
        name = "translate_advanced_ocr",
        description = "Translates an AdvancedOCRResult into Italian using Google AI"
    )
    suspend fun translateAdvancedOcr(
        @CapabilityParam(
            description = "The Advanced OCR Result to translate"
        )
        inputOcr: AdvancedOCRResult,
        @CapabilityParam(
            description = "Dictionary of words/actions to keep the translation coherent",
            defaultValue = ""
        )
        dictionary: String? = "",
        @CapabilityParam(
            description = "The AI Model to use",
            defaultValue = "GEMMA_31B"
        )
        model: AIModel,
        @CapabilityInput(
            description = "Path to the folder containing the images",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String? = "",
        @CapabilityOutput(
            description = "Directory to save translation result",
            autogeneratedPattern = "{model}/translation",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityOutput(
            description = "Temporary directory for summary images",
            autogeneratedPattern = "{model}/temp_summary",
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
        @CapabilityParam(
            description = "Enable thinking/reasoning process for compatible models",
            defaultValue = "true",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        enableThinking: Boolean? = true,
        @CapabilityParam(
            description = "Reasoning effort level for reasoning-capable models (DEFAULT, LOW, MEDIUM, HIGH)",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        @CapabilityParam(
            description = "Structured output mode (JSON). DEFAULT uses the global plugin setting.",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        structuredOutput: StructuredOutputMode? = StructuredOutputMode.DEFAULT,
        @CapabilityParam(
            description = "Print raw LLM model responses to the log for debugging",
            defaultValue = "false",
            isAdvanced = true
        )
        debugLogging: Boolean? = false,
        @CapabilityParam(
            description = "Element categories to include in translation",
            defaultValue = "[\"speech\", \"sfx\"]"
        )
        target_classes: List<TranslationTargetClass> = listOf(
            TranslationTargetClass.speech,
            TranslationTargetClass.sfx
        ),
        context: PluginContext,
        hostFs: HostFileSystem
    ): AdvancedOCRResult {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        val effectiveSummary = generateChapterSummary ?: true
        val effectiveStructuredOutput = when (structuredOutput ?: StructuredOutputMode.DEFAULT) {
            StructuredOutputMode.DEFAULT -> settings.useStructuredOutput
            StructuredOutputMode.ENABLED -> true
            StructuredOutputMode.DISABLED -> false
        }
        val effectiveDebug = debugLogging ?: settings.debugLogging ?: false

        val validIndices = inputOcr.texts.indices.filter { !isHallucination(inputOcr.texts[it]) }
        if (validIndices.isEmpty()) {
            logger.info("Advanced OCR Translation: No valid texts to translate after filtering empty/hallucinations.")
            return inputOcr.copy(
                texts = emptyList(),
                balloonBoxes = emptyList(),
                textBoxes = emptyList(),
                shapes = emptyList(),
                fontStyles = emptyList(),
                fontFamilies = emptyList(),
                textAngles = emptyList(),
                isSparse = emptyList(),
                textColors = emptyList(),
                hasBorder = emptyList(),
                borderColors = emptyList(),
                pageNumbers = emptyList(),
                pageNames = emptyList(),
                categories = emptyList()
            )
        }

        val cleanCategories = validIndices.map { inputOcr.categories.getOrElse(it) { "none" } }

        val cleanOcr = AdvancedOCRResult(
            texts = validIndices.map { inputOcr.texts[it] },
            balloonBoxes = validIndices.map { inputOcr.balloonBoxes.getOrElse(it) { emptyList() } },
            textBoxes = validIndices.map { inputOcr.textBoxes.getOrElse(it) { emptyList() } },
            shapes = validIndices.map { inputOcr.shapes.getOrElse(it) { "oval" } },
            fontStyles = validIndices.map { inputOcr.fontStyles.getOrElse(it) { "normal" } },
            fontFamilies = validIndices.map { inputOcr.fontFamilies.getOrElse(it) { "AnimeAce2.0BB" } },
            textAngles = validIndices.map { inputOcr.textAngles.getOrElse(it) { 0.0 } },
            isSparse = validIndices.map { inputOcr.isSparse.getOrElse(it) { false } },
            textColors = validIndices.map { inputOcr.textColors.getOrElse(it) { "#000000" } },
            hasBorder = validIndices.map { inputOcr.hasBorder.getOrElse(it) { false } },
            borderColors = validIndices.map { inputOcr.borderColors.getOrElse(it) { "#FFFFFF" } },
            pageNumbers = validIndices.map { inputOcr.pageNumbers.getOrElse(it) { 1 } },
            pageNames = validIndices.map { inputOcr.pageNames.getOrElse(it) { "" } },
            failedFiles = inputOcr.failedFiles,
            categories = cleanCategories
        )

        val allowedCategoryNames = target_classes.map { it.name.lowercase() }.toSet()
        val translationIndices = cleanOcr.texts.indices.filter { idx ->
            cleanCategories.getOrElse(idx) { "none" }.lowercase() in allowedCategoryNames
        }

        if (translationIndices.isEmpty()) {
            logger.info("Advanced OCR Translation: No texts match target_classes filter ($target_classes). Skipping translation.")
            return cleanOcr
        }

        val textsToTranslate = translationIndices.map { cleanOcr.texts[it] }
        val pagesToTranslate = translationIndices.map { cleanOcr.pageNames[it] }

        logger.info("Manhwa Translator AI (Advanced OCR) started. Model: ${model.id}")
        logger.info("Input size: ${textsToTranslate.size} to translate (total: ${cleanOcr.texts.size}, raw: ${inputOcr.texts.size}) | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages | Global Summary: $effectiveSummary")

        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val translatedSubTexts = service.performTranslation(
                input = textsToTranslate,
                dictionary = effectiveDict,
                apiKey = settings.googleApiKey,
                useStructuredOutput = effectiveStructuredOutput,
                modelId = model.id,
                pageNames = pagesToTranslate,
                inputFolder = inputFolder,
                outputDir = outputDir,
                tempSummaryDir = tempSummaryDir,
                useContextImages = effectiveContextImages,
                generateChapterSummary = effectiveSummary,
                save = save ?: true,
                enableThinking = enableThinking ?: true,
                reasoningEffort = reasoningEffort ?: ReasoningEffortLevel.DEFAULT,
                debugLogging = effectiveDebug
            )
            logger.info("Advanced OCR Translation completed.")
            val finalTexts = cleanOcr.texts.toMutableList()
            for ((subIdx, origIdx) in translationIndices.withIndex()) {
                if (subIdx < translatedSubTexts.size) {
                    finalTexts[origIdx] = translatedSubTexts[subIdx]
                }
            }
            cleanOcr.copy(texts = finalTexts, categories = cleanCategories)
        } catch (e: Throwable) {
            val msg = "Advanced OCR Translation failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    @Capability(
        name = "update_dictionary",
        description = "Generates or updates a dictionary/lore context for a new chapter using Lore Master AI"
    )
    suspend fun updateDictionary(
        @CapabilityParam(
            description = "Path to current dictionary markdown file or raw markdown content",
            defaultValue = ""
        )
        currentDictionary: String? = "",
        @CapabilityParam(
            description = "Summary/analysis of the new chapter (text or path to file)",
            defaultValue = ""
        )
        chapterSummary: String? = "",
        @CapabilityParam(
            description = "Optional chapter number or title (e.g. '242' or 'Cap. 242')",
            defaultValue = ""
        )
        chapterNumber: JsonElement? = null,
        @CapabilityParam(
            description = "Optional original OCR text/dialogue strings from the new chapter",
            defaultValue = "[]"
        )
        originalTexts: List<String>? = emptyList(),
        @CapabilityParam(
            description = "Optional translated text/dialogue strings from the new chapter",
            defaultValue = "[]"
        )
        translatedTexts: List<String>? = emptyList(),
        @CapabilityParam(
            description = "Optional legacy list of text/dialogue strings (used as fallback for translatedTexts)",
            defaultValue = "[]"
        )
        chapterTexts: List<String>? = emptyList(),
        @CapabilityInput(
            description = "Optional path to folder containing chapter images (used to generate summary if chapterSummary is empty)",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String? = "",
        @CapabilityParam(
            description = "The AI Model to use (DEEPSEEK_PRO recommended for deep lore consistency, GEMINI_3_8_FLASH)",
            defaultValue = "GEMINI_3_8_FLASH"
        )
        model: AIModel = AIModel.GEMINI_3_8_FLASH,
        @CapabilityParam(
            description = "Save updated dictionary and changelog files to outputDir",
            defaultValue = "true"
        )
        outputFile: Boolean? = true,
        @CapabilityOutput(
            description = "Directory to save the updated dictionary and changelog files",
            autogeneratedPattern = "{model}/dictionary",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "Enable thinking/reasoning process for compatible models",
            defaultValue = "true",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        enableThinking: Boolean? = true,
        @CapabilityParam(
            description = "Reasoning effort level for reasoning-capable models (DEFAULT, LOW, MEDIUM, HIGH)",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val logger = context.logger
        logger.info("Manhwa Translator AI (update_dictionary) started with model: ${model.id}")
        val effectiveTrans = if (!translatedTexts.isNullOrEmpty()) translatedTexts else chapterTexts
        val resolvedChapterNumber = when (chapterNumber) {
            null -> null
            is JsonPrimitive -> chapterNumber.content.trim().ifEmpty { null }
            else -> chapterNumber.toString().trim().ifEmpty { null }
        }
        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val result = service.updateDictionary(
                currentDictionary = currentDictionary,
                chapterSummary = chapterSummary,
                chapterNumber = resolvedChapterNumber,
                originalTexts = originalTexts,
                translatedTexts = effectiveTrans,
                inputFolder = inputFolder,
                modelId = model.id,
                outputFile = outputFile,
                outputDir = outputDir,
                enableThinking = enableThinking ?: true,
                reasoningEffort = reasoningEffort ?: ReasoningEffortLevel.DEFAULT
            )
            logger.info("Dictionary update completed.")
            result.updatedDictionary
        } catch (e: Throwable) {
            val msg = "Dictionary update failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    suspend fun updateDictionary(
        currentDictionary: String? = "",
        chapterSummary: String? = "",
        chapterNumber: String?,
        originalTexts: List<String>? = emptyList(),
        translatedTexts: List<String>? = emptyList(),
        chapterTexts: List<String>? = emptyList(),
        inputFolder: String? = "",
        model: AIModel = AIModel.GEMINI_3_8_FLASH,
        outputFile: Boolean? = true,
        outputDir: String,
        enableThinking: Boolean? = true,
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String = updateDictionary(
        currentDictionary = currentDictionary,
        chapterSummary = chapterSummary,
        chapterNumber = chapterNumber?.let { JsonPrimitive(it) },
        originalTexts = originalTexts,
        translatedTexts = translatedTexts,
        chapterTexts = chapterTexts,
        inputFolder = inputFolder,
        model = model,
        outputFile = outputFile,
        outputDir = outputDir,
        enableThinking = enableThinking,
        reasoningEffort = reasoningEffort,
        context = context,
        hostFs = hostFs
    )

    @Capability(
        name = "update_dictionary_ocr",
        description = "Generates or updates a dictionary/lore context for a new chapter using original and/or translated OCRResult"
    )
    suspend fun updateDictionaryOcr(
        @CapabilityParam(
            description = "Path to current dictionary markdown file or raw markdown content",
            defaultValue = ""
        )
        currentDictionary: String? = "",
        @CapabilityParam(
            description = "Summary/analysis of the new chapter (text or path to file)",
            defaultValue = ""
        )
        chapterSummary: String? = "",
        @CapabilityParam(
            description = "Optional chapter number or title (e.g. '242' or 'Cap. 242')",
            defaultValue = ""
        )
        chapterNumber: JsonElement? = null,
        @CapabilityParam(
            description = "Original OCRResult containing raw source texts from the new chapter"
        )
        originalOcr: OCRResult? = null,
        @CapabilityParam(
            description = "Translated OCRResult containing Italian translations from the new chapter"
        )
        translatedOcr: OCRResult? = null,
        @CapabilityInput(
            description = "Optional path to folder containing chapter images (used to generate summary if chapterSummary is empty)",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String? = "",
        @CapabilityParam(
            description = "The AI Model to use (DEEPSEEK_PRO recommended for deep lore consistency, GEMINI_3_8_FLASH)",
            defaultValue = "GEMINI_3_8_FLASH"
        )
        model: AIModel = AIModel.GEMINI_3_8_FLASH,
        @CapabilityParam(
            description = "Save updated dictionary and changelog files to outputDir",
            defaultValue = "true"
        )
        outputFile: Boolean? = true,
        @CapabilityOutput(
            description = "Directory to save the updated dictionary and changelog files",
            autogeneratedPattern = "{model}/dictionary",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "Enable thinking/reasoning process for compatible models",
            defaultValue = "true",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        enableThinking: Boolean? = true,
        @CapabilityParam(
            description = "Reasoning effort level for reasoning-capable models (DEFAULT, LOW, MEDIUM, HIGH)",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        @CapabilityParam(
            description = "Print raw LLM model responses to the log for debugging",
            defaultValue = "false",
            isAdvanced = true
        )
        debugLogging: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val logger = context.logger
        val effectiveDebug = debugLogging ?: settings.debugLogging ?: false
        logger.info("Manhwa Translator AI (update_dictionary_ocr) started with model: ${model.id}")
        val origTexts = originalOcr?.texts?.filter { !isHallucination(it) } ?: emptyList()
        val transTexts = translatedOcr?.texts?.filter { !isHallucination(it) } ?: emptyList()
        val resolvedChapterNumber = when (chapterNumber) {
            null -> null
            is JsonPrimitive -> chapterNumber.content.trim().ifEmpty { null }
            else -> chapterNumber.toString().trim().ifEmpty { null }
        }
        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val result = service.updateDictionary(
                currentDictionary = currentDictionary,
                chapterSummary = chapterSummary,
                chapterNumber = resolvedChapterNumber,
                originalTexts = origTexts,
                translatedTexts = transTexts,
                inputFolder = inputFolder,
                modelId = model.id,
                outputFile = outputFile,
                outputDir = outputDir,
                enableThinking = enableThinking ?: true,
                reasoningEffort = reasoningEffort ?: ReasoningEffortLevel.DEFAULT,
                debugLogging = effectiveDebug
            )
            logger.info("Dictionary update (OCRResult) completed.")
            result.updatedDictionary
        } catch (e: Throwable) {
            val msg = "Dictionary update (OCRResult) failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    suspend fun updateDictionaryOcr(
        currentDictionary: String? = "",
        chapterSummary: String? = "",
        chapterNumber: String?,
        originalOcr: OCRResult? = null,
        translatedOcr: OCRResult? = null,
        inputFolder: String? = "",
        model: AIModel = AIModel.GEMINI_3_8_FLASH,
        outputFile: Boolean? = true,
        outputDir: String,
        enableThinking: Boolean? = true,
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        debugLogging: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String = updateDictionaryOcr(
        currentDictionary = currentDictionary,
        chapterSummary = chapterSummary,
        chapterNumber = chapterNumber?.let { JsonPrimitive(it) },
        originalOcr = originalOcr,
        translatedOcr = translatedOcr,
        inputFolder = inputFolder,
        model = model,
        outputFile = outputFile,
        outputDir = outputDir,
        enableThinking = enableThinking,
        reasoningEffort = reasoningEffort,
        debugLogging = debugLogging,
        context = context,
        hostFs = hostFs
    )

    @Capability(
        name = "update_dictionary_advanced_ocr",
        description = "Generates or updates a dictionary/lore context for a new chapter using original and/or translated AdvancedOCRResult"
    )
    suspend fun updateDictionaryAdvancedOcr(
        @CapabilityParam(
            description = "Path to current dictionary markdown file or raw markdown content",
            defaultValue = ""
        )
        currentDictionary: String? = "",
        @CapabilityParam(
            description = "Summary/analysis of the new chapter (text or path to file)",
            defaultValue = ""
        )
        chapterSummary: String? = "",
        @CapabilityParam(
            description = "Optional chapter number or title (e.g. '242' or 'Cap. 242')",
            defaultValue = ""
        )
        chapterNumber: JsonElement? = null,
        @CapabilityParam(
            description = "Original AdvancedOCRResult containing raw source texts from the new chapter"
        )
        originalOcr: AdvancedOCRResult? = null,
        @CapabilityParam(
            description = "Translated AdvancedOCRResult containing Italian translations from the new chapter"
        )
        translatedOcr: AdvancedOCRResult? = null,
        @CapabilityInput(
            description = "Optional path to folder containing chapter images (used to generate summary if chapterSummary is empty)",
            semanticTypes = ["path/folder"]
        )
        inputFolder: String? = "",
        @CapabilityParam(
            description = "The AI Model to use (DEEPSEEK_PRO recommended for deep lore consistency, GEMINI_3_8_FLASH)",
            defaultValue = "GEMINI_3_8_FLASH"
        )
        model: AIModel = AIModel.GEMINI_3_8_FLASH,
        @CapabilityParam(
            description = "Save updated dictionary and changelog files to outputDir",
            defaultValue = "true"
        )
        outputFile: Boolean? = true,
        @CapabilityOutput(
            description = "Directory to save the updated dictionary and changelog files",
            autogeneratedPattern = "{model}/dictionary",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "Enable thinking/reasoning process for compatible models",
            defaultValue = "true",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        enableThinking: Boolean? = true,
        @CapabilityParam(
            description = "Reasoning effort level for reasoning-capable models (DEFAULT, LOW, MEDIUM, HIGH)",
            defaultValue = "DEFAULT",
            isAdvanced = true
        )
        @DependsOn(
            param = "model",
            operator = ConditionOperator.IN,
            values = ["GEMINI_3_7_FLASH", "GEMINI_3_8_FLASH", "DEEPSEEK_FLASH", "DEEPSEEK_PRO", "LM_STUDIO", "GLM_5_3_FLASH", "GLM_5_3_FLASHX", "GLM_4_7_FLASH"]
        )
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        @CapabilityParam(
            description = "Print raw LLM model responses to the log for debugging",
            defaultValue = "false",
            isAdvanced = true
        )
        debugLogging: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val logger = context.logger
        val effectiveDebug = debugLogging ?: settings.debugLogging ?: false
        logger.info("Manhwa Translator AI (update_dictionary_advanced_ocr) started with model: ${model.id}")
        val origTexts = originalOcr?.texts?.filter { !isHallucination(it) } ?: emptyList()
        val transTexts = translatedOcr?.texts?.filter { !isHallucination(it) } ?: emptyList()
        val resolvedChapterNumber = when (chapterNumber) {
            null -> null
            is JsonPrimitive -> chapterNumber.content.trim().ifEmpty { null }
            else -> chapterNumber.toString().trim().ifEmpty { null }
        }
        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val result = service.updateDictionary(
                currentDictionary = currentDictionary,
                chapterSummary = chapterSummary,
                chapterNumber = resolvedChapterNumber,
                originalTexts = origTexts,
                translatedTexts = transTexts,
                inputFolder = inputFolder,
                modelId = model.id,
                outputFile = outputFile,
                outputDir = outputDir,
                enableThinking = enableThinking ?: true,
                reasoningEffort = reasoningEffort ?: ReasoningEffortLevel.DEFAULT,
                debugLogging = effectiveDebug
            )
            logger.info("Dictionary update (AdvancedOCRResult) completed.")
            result.updatedDictionary
        } catch (e: Throwable) {
            val msg = "Dictionary update (AdvancedOCRResult) failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
    }

    suspend fun updateDictionaryAdvancedOcr(
        currentDictionary: String? = "",
        chapterSummary: String? = "",
        chapterNumber: String?,
        originalOcr: AdvancedOCRResult? = null,
        translatedOcr: AdvancedOCRResult? = null,
        inputFolder: String? = "",
        model: AIModel = AIModel.GEMINI_3_8_FLASH,
        outputFile: Boolean? = true,
        outputDir: String,
        enableThinking: Boolean? = true,
        reasoningEffort: ReasoningEffortLevel? = ReasoningEffortLevel.DEFAULT,
        debugLogging: Boolean? = false,
        context: PluginContext,
        hostFs: HostFileSystem
    ): String = updateDictionaryAdvancedOcr(
        currentDictionary = currentDictionary,
        chapterSummary = chapterSummary,
        chapterNumber = chapterNumber?.let { JsonPrimitive(it) },
        originalOcr = originalOcr,
        translatedOcr = translatedOcr,
        inputFolder = inputFolder,
        model = model,
        outputFile = outputFile,
        outputDir = outputDir,
        enableThinking = enableThinking,
        reasoningEffort = reasoningEffort,
        debugLogging = debugLogging,
        context = context,
        hostFs = hostFs
    )

    @Capability(
        name = "save_translations",
        description = "Saves all translations into a single formatted file (TXT, Markdown, Bilingual, or JSON)"
    )
    suspend fun saveTranslations(
        @CapabilityParam(
            description = "List of translated text strings"
        )
        translations: List<String>,
        @CapabilityParam(
            description = "Optional list of original text strings",
            defaultValue = "[]"
        )
        originalTexts: List<String>? = emptyList(),
        @CapabilityParam(
            description = "Optional list of page names matching the translations",
            defaultValue = "[]"
        )
        pageNames: List<String>? = emptyList(),
        @CapabilityParam(
            description = "Optional list of page numbers matching the translations",
            defaultValue = "[]"
        )
        pageNumbers: List<Int>? = emptyList(),
        @CapabilityInput(
            description = "Optional specific file path to save translations to",
            semanticTypes = ["path/file"]
        )
        outputFile: String? = "",
        @CapabilityOutput(
            description = "Directory to save translation file if outputFile is not specified",
            autogeneratedPattern = "translations",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "File name if outputDir is used",
            defaultValue = "translations.txt"
        )
        fileName: String? = "translations.txt",
        @CapabilityParam(
            description = "Output format: 'txt', 'markdown', 'bilingual', or 'json'",
            defaultValue = "txt"
        )
        format: String? = "txt",
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val service = KoogAITranslatorService(context, settings, hostFs)
        return service.saveTranslationsToFile(
            translations = translations,
            originalTexts = originalTexts,
            pageNames = pageNames,
            pageNumbers = pageNumbers,
            outputFile = outputFile,
            outputDir = outputDir,
            fileName = fileName,
            format = format
        )
    }

    @Capability(
        name = "save_ocr_translations",
        description = "Saves translations from an OCRResult into a single formatted file"
    )
    suspend fun saveOcrTranslations(
        @CapabilityParam(
            description = "The OCRResult containing translated texts, page names and numbers"
        )
        ocrResult: OCRResult,
        @CapabilityInput(
            description = "Optional specific file path to save translations to",
            semanticTypes = ["path/file"]
        )
        outputFile: String? = "",
        @CapabilityOutput(
            description = "Directory to save translation file if outputFile is not specified",
            autogeneratedPattern = "translations",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "File name if outputDir is used",
            defaultValue = "translations.txt"
        )
        fileName: String? = "translations.txt",
        @CapabilityParam(
            description = "Output format: 'txt', 'markdown', 'bilingual', or 'json'",
            defaultValue = "txt"
        )
        format: String? = "txt",
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val service = KoogAITranslatorService(context, settings, hostFs)
        return service.saveTranslationsToFile(
            translations = ocrResult.texts,
            originalTexts = emptyList(),
            pageNames = ocrResult.pageNames,
            pageNumbers = ocrResult.pageNumbers,
            outputFile = outputFile,
            outputDir = outputDir,
            fileName = fileName,
            format = format
        )
    }

    @Capability(
        name = "save_advanced_ocr_translations",
        description = "Saves translations from an AdvancedOCRResult into a single formatted file"
    )
    suspend fun saveAdvancedOcrTranslations(
        @CapabilityParam(
            description = "The AdvancedOCRResult containing translated texts, page names and numbers"
        )
        ocrResult: AdvancedOCRResult,
        @CapabilityInput(
            description = "Optional specific file path to save translations to",
            semanticTypes = ["path/file"]
        )
        outputFile: String? = "",
        @CapabilityOutput(
            description = "Directory to save translation file if outputFile is not specified",
            autogeneratedPattern = "translations",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(
            description = "File name if outputDir is used",
            defaultValue = "translations.txt"
        )
        fileName: String? = "translations.txt",
        @CapabilityParam(
            description = "Output format: 'txt', 'markdown', 'bilingual', or 'json'",
            defaultValue = "txt"
        )
        format: String? = "txt",
        context: PluginContext,
        hostFs: HostFileSystem
    ): String {
        val service = KoogAITranslatorService(context, settings, hostFs)
        return service.saveTranslationsToFile(
            translations = ocrResult.texts,
            originalTexts = emptyList(),
            pageNames = ocrResult.pageNames,
            pageNumbers = ocrResult.pageNumbers,
            outputFile = outputFile,
            outputDir = outputDir,
            fileName = fileName,
            format = format
        )
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[TranslatorAI] setup: Starting Manhwa Translator AI setup...")
        logger.info("[TranslatorAI] setup: Setup completed successfully.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[TranslatorAI] update: Manhwa Translator AI update complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[TranslatorAI] validate: Validating Manhwa Translator AI requirements...")
        logger.info("[TranslatorAI] validate: Google API Key configured: ${settings.googleApiKey.isNotBlank()}")
        logger.info("[TranslatorAI] validate: DeepSeek API Key configured: ${!settings.deepseekApiKey.isNullOrBlank()}")
        logger.info("[TranslatorAI] validate: Validation passed successfully.")
        return Result.success(Unit)
    }
}
