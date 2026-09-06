package com.wip.manhwaTranslatorAI

import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import com.wip.common.inference.lmstudio.LmStudioManager
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
    GEMINI_3_1_FLASH_LITE("gemini-3.1-flash-lite"),
    @RequiresSetting(["lmStudioModelName", "lmStudioApiKey", "lmStudioUrl"])
    LM_STUDIO("lm-studio")
}

@PluginInfo(
    id = "com.wip.manhwa_translator_ai",
    name = "Manhwa Translator AI",
    version = "1.4.2",
    description = "Translate text from Manhwa/Manga into Italian using Google AI via Koog",
    supportedOs = [OS.WINDOWS]
)
class TranslatorAI(val settings: TranslatorAISettings) {

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[TranslatorAI] onLoad: Initializing Manhwa Translator AI (has googleApiKey: ${settings.googleApiKey.isNotBlank()}, useStructuredOutput: ${settings.useStructuredOutput}, lmStudioUrl: ${settings.lmStudioUrl})")
        return Result.success(Unit)
    }

    @PluginAction(
        name = "Test LM Studio Connection",
        description = "Checks connectivity to LM Studio and discovers active and available models"
    )
    suspend fun testLmStudioConnection(context: PluginContext) {
        val logger = context.logger
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

    fun isHallucination(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return true
        val clean = rawText.trim()
            .replace(Regex("(?i)<\\|/?(?:ref|box|det|quad|grounding|image|text)[^>]*\\|>"), "")
            .replace(Regex("(?i)\\b(?:image|figure|table|header|footer|background|watermark)\\s*\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]"), "")
            .replace(Regex("(?i)^\\s*(?:text|balloon|speech|dialogue|caption|title|paragraph|line)\\s*\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]\\s*"), "")
            .trim()
        if (clean.isBlank()) return true
        if (!clean.any { it.isLetterOrDigit() }) return true

        val lower = clean.lowercase()
        val directMatches = setOf(
            "(no text)", "no text", "none", "n/a", "na", "empty", "nothing",
            "no dialogue", "no speech", "no speech bubble", "no speech bubbles",
            "no text detected", "no text found", "no visible text",
            "(nessun testo)", "nessun testo", "nessun dialogo",
            "1", "0", "null", "undefined"
        )
        if (lower in directMatches) return true

        val hallucinationRegexes = listOf(
            Regex("""(?i)^\s*\(?(?:no\s+text|nessun\s+testo|none|empty|nothing|no\s+dialogue|no\s+speech(?:\s+bubbles?)?)\)?\.?\s*$"""),
            Regex("""(?i)\b(?:the\s+image\s+contains\s+no\s+text|image\s+contains\s+no\s+visible\s+text|there\s+is\s+no\s+text\s+in\s+this\s+image|no\s+text\s+(?:found|detected|visible)\s+in\s+the\s+image)\b"""),
            Regex("""(?i)\b(?:the\s+ocr\s+result.*is\s+a\s+hallucination|does\s+not\s+correspond\s+to\s+any\s+content|absence\s+of\s+any\s+visible\s+text)\b"""),
            Regex("""(?i)\b(?:correct\s+ocr\s+output\s+must\s+reflect\s+the\s+absence\s+of|cannot\s+find\s+any\s+text\s+to\s+transcribe|no\s+transcription\s+available)\b""")
        )

        for (regex in hallucinationRegexes) {
            if (regex.containsMatchIn(lower)) {
                return true
            }
        }

        return false
    }

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
        context: PluginContext,
        hostFs: HostFileSystem
    ): OCRResult {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        val effectiveSummary = generateChapterSummary ?: true

        val validIndices = inputOcr.texts.indices.filter { !isHallucination(inputOcr.texts[it]) }
        if (validIndices.isEmpty()) {
            logger.info("Basic OCR Translation: No valid texts to translate after filtering empty/hallucinations.")
            return inputOcr.copy(texts = emptyList(), bb = emptyList(), pageNumbers = emptyList(), pageNames = emptyList())
        }

        val cleanOcr = OCRResult(
            texts = validIndices.map { inputOcr.texts[it] },
            bb = validIndices.map { inputOcr.bb.getOrElse(it) { emptyList() } },
            pageNumbers = validIndices.map { inputOcr.pageNumbers.getOrElse(it) { 1 } },
            pageNames = validIndices.map { inputOcr.pageNames.getOrElse(it) { "" } },
            failedFiles = inputOcr.failedFiles
        )

        logger.info("Manhwa Translator AI (Basic OCRResult) started. Model: ${model.id}")
        logger.info("Input size: ${cleanOcr.texts.size} (filtered from ${inputOcr.texts.size}) | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages | Global Summary: $effectiveSummary")

        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val translatedTexts = service.performTranslation(
                input = cleanOcr.texts,
                dictionary = effectiveDict,
                apiKey = settings.googleApiKey,
                useStructuredOutput = settings.useStructuredOutput,
                modelId = model.id,
                pageNames = cleanOcr.pageNames,
                inputFolder = inputFolder,
                outputDir = outputDir,
                tempSummaryDir = tempSummaryDir,
                useContextImages = effectiveContextImages,
                generateChapterSummary = effectiveSummary,
                save = save ?: true
            )
            logger.info("Basic OCR Translation completed.")
            cleanOcr.copy(texts = translatedTexts)
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
            val service = KoogAITranslatorService(context, settings, hostFs)
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
            context: PluginContext,
            hostFs: HostFileSystem
    ): AdvancedOCRResult {
        val logger = context.logger
        val effectiveDict = dictionary ?: ""
        val effectiveContextImages = useContextImages ?: false
        val effectiveSummary = generateChapterSummary ?: true

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
                pageNames = emptyList()
            )
        }

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
            failedFiles = inputOcr.failedFiles
        )

        logger.info("Manhwa Translator AI (Advanced OCR) started. Model: ${model.id}")
        logger.info("Input size: ${cleanOcr.texts.size} (filtered from ${inputOcr.texts.size}) | Dictionary size: ${effectiveDict.length} | Context Images: $effectiveContextImages | Global Summary: $effectiveSummary")

        return try {
            val service = KoogAITranslatorService(context, settings, hostFs)
            val translatedTexts = service.performTranslation(
                input = cleanOcr.texts,
                dictionary = effectiveDict,
                apiKey = settings.googleApiKey,
                useStructuredOutput = settings.useStructuredOutput,
                modelId = model.id,
                pageNames = cleanOcr.pageNames,
                inputFolder = inputFolder,
                outputDir = outputDir,
                tempSummaryDir = tempSummaryDir,
                useContextImages = effectiveContextImages,
                generateChapterSummary = effectiveSummary,
                save = save ?: true
            )
            logger.info("Advanced OCR Translation completed.")
            cleanOcr.copy(texts = translatedTexts)
        } catch (e: Throwable) {
            val msg = "Advanced OCR Translation failed: ${e::class.simpleName}: ${e.message}"
            logger.error(msg)
            if (e is Error) {
                logger.error("A critical Error occurred: ${e.stackTraceToString()}")
            }
            throw RuntimeException(msg, e)
        }
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
        logger.info("[TranslatorAI] validate: Validation passed successfully.")
        return Result.success(Unit)
    }
}
