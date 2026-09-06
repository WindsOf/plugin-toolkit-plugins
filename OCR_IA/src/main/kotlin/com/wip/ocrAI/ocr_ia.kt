package com.wip.ocrAI

import com.wip.common.inference.llama.LlamaBackend
import com.wip.common.inference.llama.LlamaBinaryDownloader
import com.wip.common.inference.llama.LlamaServerManager
import com.wip.common.inference.lmstudio.LmStudioManager
import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.OCRResult
import com.wip.common.models.OcrVisionMerger
import com.wip.common.models.VisionResult
import com.wip.ocrAI.models.AIModel
import com.wip.ocrAI.models.OcrDownloadModel
import com.wip.ocrAI.models.OcrIASettings
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityInput
import org.wip.plugintoolkit.api.annotations.CapabilityOutput
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.PluginAction
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginLoad
import org.wip.plugintoolkit.api.annotations.PluginLocks
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginUpdate
import org.wip.plugintoolkit.api.annotations.PluginValidate
import java.io.File

@PluginInfo(
    id = "com.wip.ocr_ia",
    name = "OCR IA",
    version = "2.7.2",
    description = "Advanced OCR plugin using Google AI, Anthropic, OpenAI, and LMStudio via Koog",
    supportedOs = [OS.WINDOWS]
)
class OCR_IA(val settings: OcrIASettings = OcrIASettings()) {

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[OCR_IA] onLoad: Initializing OCR IA (has googleApiKey: ${settings.googleApiKey.isNotBlank()}, anthropicApiKey: ${!settings.anthropicApiKey.isNullOrBlank()}, openAIApiKey: ${!settings.openAIApiKey.isNullOrBlank()})")
        return Result.success(Unit)
    }

    @PluginLocks
    suspend fun checkLocks(context: PluginContext): Map<String, Boolean> {
        val logger = context.logger
        logger.info("[OCR_IA] checkLocks: Checking OCR model locks...")
        val locks = mutableMapOf<String, Boolean>()
        for (m in OcrDownloadModel.entries) {
            val installed = ModelManager.Default.isModelInstalled(m.modelId, context.fileSystem, logger)
            logger.info("[OCR_IA] checkLocks: Model ${m.name} (${m.modelId}) installed: $installed")
            locks["model:${m.modelId}"] = installed
            locks[m.modelId] = installed
            locks["model:${m.modelId.lowercase()}"] = installed
            locks[m.modelId.lowercase()] = installed
            locks[ModelCatalog.getLockKey(m.modelId)] = installed
        }
        logger.info("[OCR_IA] checkLocks: Completed OCR locks check: $locks")
        return locks
    }

    @PluginAction(
        name = "Download Model",
        description = "Downloads a specific OCR model and descriptor to local plugin storage"
    )
    suspend fun downloadModel(
        @CapabilityParam(description = "Select OCR model to download", defaultValue = "\"UNLIMITED_OCR_Q4_K_M\"")
        model: OcrDownloadModel? = OcrDownloadModel.UNLIMITED_OCR_Q4_K_M,
        context: PluginContext
    ) {
        val logger = context.logger
        val targetModel = model ?: OcrDownloadModel.UNLIMITED_OCR_Q4_K_M
        logger.info("[OCR_IA] downloadModel action triggered for: ${targetModel.name} (${targetModel.modelId})")
        val result = ModelManager.Default.downloadModel(targetModel.modelId, context)
        if (result.isFailure) {
            val errMsg = result.exceptionOrNull()?.message ?: "Unknown error"
            logger.error("[OCR_IA] downloadModel action failed for ${targetModel.modelId}: $errMsg")
            context.showToast("Failed to download model ${targetModel.name}: $errMsg")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download model ${targetModel.modelId}")
        }
        logger.info("[OCR_IA] downloadModel action succeeded for: ${targetModel.modelId}")
        context.showToast("Downloaded model: ${targetModel.name} (${targetModel.modelId}) successfully!")
    }

    @PluginAction(
        name = "Download All Models",
        description = "Downloads all required ONNX or GGUF OCR models and descriptors to local plugin storage"
    )
    suspend fun downloadAllModels(context: PluginContext) {
        val logger = context.logger
        val ocrIds = OcrDownloadModel.entries.map { it.modelId }
        val result = ModelManager.Default.downloadModels(ocrIds, context)
        if (result.isFailure) {
            val errMsg = result.exceptionOrNull()?.message ?: "Unknown error"
            logger.error("[OCR_IA] downloadAllModels failed: $errMsg")
            context.showToast("Failed to download all OCR models: $errMsg")
            throw result.exceptionOrNull() ?: RuntimeException("Failed to download all models")
        }
        logger.info("[OCR_IA] downloadAllModels succeeded")
        context.showToast("All OCR models downloaded successfully!")
    }

    @PluginAction(
        name = "Check Installed Models",
        description = "Scans plugin storage to verify and report the installation and lock status of all OCR models"
    )
    suspend fun checkInstalledModels(context: PluginContext) {
        val logger = context.logger
        logger.info("[OCR_IA] checkInstalledModels action triggered. Rechecking plugin storage...")
        val locks = checkLocks(context)
        logger.info("[OCR_IA] ===========================================")
        logger.info("[OCR_IA]       OCR INSTALLED MODELS REPORT          ")
        logger.info("[OCR_IA] ===========================================")
        for (m in OcrDownloadModel.entries) {
            val isInstalled = locks[m.modelId] == true || locks["model:${m.modelId}"] == true
            val status = if (isInstalled) "[INSTALLED - UNLOCKED]" else "[NOT INSTALLED - LOCKED]"
            logger.info("[OCR_IA] • ${m.name.padEnd(20)} ($status)")
        }
        logger.info("[OCR_IA] ===========================================")
        val installedCount = OcrDownloadModel.entries.count { locks[it.modelId] == true || locks["model:${it.modelId}"] == true }
        context.showToast("OCR Models: $installedCount / ${OcrDownloadModel.entries.size} installed")
    }

    @PluginAction(
        name = "Install Llama Server",
        description = "Downloads and installs precompiled llama-server binaries (CUDA, Vulkan, or CPU) locally and to the system PATH"
    )
    suspend fun installLlamaServer(
        @CapabilityParam(description = "Select hardware acceleration backend", defaultValue = "\"AUTO\"")
        backend: LlamaBackend? = LlamaBackend.AUTO,
        @CapabilityParam(description = "Install system-wide and register directory to User PATH", defaultValue = "true")
        systemWide: Boolean? = true,
        context: PluginContext
    ) {
        val logger = context.logger
        val progress = context.progress
        val targetBackend = backend ?: settings.llamaServerBackend ?: LlamaBackend.AUTO
        val isSystemWide = systemWide ?: true
        logger.info("[OCR_IA] installLlamaServer action triggered (backend=$targetBackend, systemWide=$isSystemWide)")

        val result = if (isSystemWide) {
            LlamaBinaryDownloader.Default.downloadAndInstallSystem(
                backend = targetBackend,
                fileSystem = context.fileSystem,
                addToUserPath = true,
                logger = logger,
                progress = progress
            )
        } else {
            LlamaBinaryDownloader.Default.downloadAndInstall(
                fileSystem = context.fileSystem,
                backend = targetBackend,
                logger = logger,
                progress = progress
            )
        }

        if (result.isFailure) {
            val err = result.exceptionOrNull()
            val errMsg = err?.message ?: "Unknown error"
            logger.error("[OCR_IA] installLlamaServer failed: $errMsg", err)
            context.showToast("Failed to install llama-server: $errMsg")
            throw err ?: RuntimeException("Failed to install llama-server")
        }
        logger.info("[OCR_IA] installLlamaServer succeeded: ${result.getOrNull()}")
        context.showToast("llama-server ($targetBackend) installed successfully!")
    }

    @PluginAction(
        name = "Detect Llama Server",
        description = "Scans system PATH, standard directories, and local storage to automatically detect existing llama-server installations"
    )
    suspend fun detectLlamaServer(context: PluginContext) {
        val logger = context.logger
        logger.info("[OCR_IA] detectLlamaServer action triggered. Scanning system and plugin storage...")
        val detection = LlamaServerManager.Default.detectInstallation(
            fileSystem = context.fileSystem,
            customPath = settings.llamaServerCustomPath?.ifBlank { null },
            logger = logger
        )
        if (detection.found) {
            logger.info("[OCR_IA] === LLAMA SERVER DETECTED ===")
            logger.info("[OCR_IA] Executable: ${detection.executablePath}")
            logger.info("[OCR_IA] Source:     ${detection.source}")
            logger.info("[OCR_IA] Details:    ${detection.details}")
            if (!detection.version.isNullOrBlank()) {
                logger.info("[OCR_IA] Version:    ${detection.version}")
            }
            logger.info("[OCR_IA] ===============================")
            context.showToast("llama-server detected: ${detection.source} (${detection.version ?: "OK"})")
        } else {
            logger.warn("[OCR_IA] llama-server was not detected on this system or plugin storage. Use the 'Install Llama Server' action to download and configure it.")
            context.showToast("llama-server was not detected on system or plugin storage.")
        }
    }

    @PluginAction(
        name = "Stop Llama Server",
        description = "Gracefully stops any active local llama-server instance and lingering orphan processes"
    )
    suspend fun stopLlamaServer(context: PluginContext) {
        val logger = context.logger
        logger.info("[OCR_IA] stopLlamaServer action triggered.")
        LlamaServerManager.Default.stopAllServers(logger)
        logger.info("[OCR_IA] Active and lingering llama-server instances have been stopped.")
        context.showToast("Active and lingering llama-server instances stopped.")
    }

    @PluginAction(
        name = "Test LM Studio Connection",
        description = "Checks connectivity to LM Studio and discovers active and available models"
    )
    suspend fun testLmStudioConnection(context: PluginContext) {
        val logger = context.logger
        val url = settings.lmStudioUrl?.ifBlank { "http://localhost:1234/v1" } ?: "http://localhost:1234/v1"
        logger.info("[OCR_IA] Testing LM Studio connection at: $url")
        val status = LmStudioManager.Default.checkStatus(baseUrl = url, apiKey = settings.lmStudioApiKey, logger = logger)
        if (status.connected) {
            val modelDesc = if (!status.activeModel.isNullOrBlank()) " (Active model: ${status.activeModel})" else ""
            val msg = "Connected to LM Studio at $url successfully!$modelDesc"
            logger.info("[OCR_IA] $msg")
            context.showToast(msg)
        } else {
            val err = status.errorMessage ?: "Connection refused or unreachable"
            val msg = "Failed to connect to LM Studio at $url: $err"
            logger.warn("[OCR_IA] $msg")
            context.showToast(msg)
        }
    }
    
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
        @CapabilityOutput(
            description = "Custom output directory",
            autogeneratedPattern = "{input}/ocr_result/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Whether to use native structured output (might not be supported by all models)", defaultValue = "false")
        useStructuredOutput: Boolean,
        @CapabilityParam(description = "Whether to save the thinking inside the json", defaultValue = "false")
        saveThinking: Boolean,
        @CapabilityParam(description = "The AI Model to use", defaultValue = "GEMMA_26B")
        model: AIModel,
        @CapabilityParam(description = "Optional Chapter Vision segmentation result to run OCR on cropped regions of interest")
        chapterVisionResult: ChapterVisionResult? = null,
        @CapabilityParam(description = "Padding in pixels around detected regions for cutout OCR", defaultValue = "100")
        cropPadding: Int = 100,
        context: PluginContext,
        hostFs: HostFileSystem
    ): OCRResult {
        val logger = context.logger
        logger.info("OCR IA (Basic) v2.4.0 started. Model: ${model.id}")
        logger.info("Input: $input | Save: $save | OutputDir: '$outputDir' | StructuredOutput: $useStructuredOutput | VisionAssisted: ${chapterVisionResult != null}")

        return try {
            if (model in setOf(AIModel.UNLIMITED_OCR_BF16, AIModel.UNLIMITED_OCR_Q8_0, AIModel.UNLIMITED_OCR_Q4_K_M, AIModel.UNLIMITED_OCR_IQ2_M)) {
                val runner = UnlimitedOcrRunner(context, hostFs, settings)
                return runner.performOcr(input, save, outputDir, useStructuredOutput, saveThinking, targetModelId = model.id, chapterVisionResult = chapterVisionResult, cropPadding = cropPadding)
            }
            val service = KoogOcrService(context, settings, hostFs)
            val ocrResult = service.performOcr(input, save, outputDir, useStructuredOutput, saveThinking, model, chapterVisionResult = chapterVisionResult, cropPadding = cropPadding)
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
        @CapabilityOutput(
            description = "Custom output directory",
            autogeneratedPattern = "{input}/ocr_result/",
            semanticTypes = ["path/folder"]
        )
        outputDir: String,
        @CapabilityParam(description = "Whether to use native structured output (might not be supported by all models)", defaultValue = "false")
        useStructuredOutput: Boolean,
        @CapabilityParam(description = "Whether to save the thinking inside the json", defaultValue = "false")
        saveThinking: Boolean,
        @CapabilityParam(description = "The AI Model to use", defaultValue = "GEMMA_31B")
        model: AIModel,
        @CapabilityParam(description = "Optional Chapter Vision segmentation result to run OCR on cropped regions of interest")
        chapterVisionResult: ChapterVisionResult? = null,
        @CapabilityParam(description = "Padding in pixels around detected regions for cutout OCR", defaultValue = "100")
        cropPadding: Int = 100,
        context: PluginContext,
        hostFs: HostFileSystem
    ): AdvancedOCRResult {
        val logger = context.logger
        logger.info("OCR IA (Advanced) v2.4.0 started. Model: ${model.id}")
        logger.info("Input: $input | Save: $save | OutputDir: '$outputDir' | StructuredOutput: $useStructuredOutput | VisionAssisted: ${chapterVisionResult != null}")

        return try {
            if (model in setOf(AIModel.UNLIMITED_OCR_BF16, AIModel.UNLIMITED_OCR_Q8_0, AIModel.UNLIMITED_OCR_Q4_K_M, AIModel.UNLIMITED_OCR_IQ2_M)) {
                val runner = UnlimitedOcrRunner(context, hostFs, settings)
                return runner.performAdvancedOcr(input, save, outputDir, useStructuredOutput, saveThinking, targetModelId = model.id, chapterVisionResult = chapterVisionResult, cropPadding = cropPadding)
            }
            val service = KoogOcrService(context, settings, hostFs)
            val ocrResult = service.performAdvancedOcr(input, save, outputDir, useStructuredOutput, saveThinking, model, chapterVisionResult = chapterVisionResult, cropPadding = cropPadding)
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

    @Capability(
        name = "merge_ocr_with_vision",
        description = "Merges multi-line OCR text entries that fall within the same speech balloon using Vision instance segmentation"
    )
    suspend fun mergeOcrWithVision(
        @CapabilityParam(description = "The OCR Result to merge")
        ocrData: OCRResult,
        @CapabilityParam(description = "Chapter Vision segmentation result containing balloon instances")
        chapterVisionResult: ChapterVisionResult,
        context: PluginContext
    ): OCRResult {
        context.logger.info("Merging OCR Result using ChapterVisionResult (total vision pages: ${chapterVisionResult.results.size}, total OCR items: ${ocrData.texts.size})")
        return OcrVisionMerger.mergeChapterOcrResult(
            ocrData = ocrData,
            chapterVisionResult = chapterVisionResult,
            separator = " "
        )
    }

    @Capability(
        name = "merge_advanced_ocr_with_vision",
        description = "Merges multi-line Advanced OCR text entries that fall within the same speech balloon using Vision instance segmentation"
    )
    suspend fun mergeAdvancedOcrWithVision(
        @CapabilityParam(description = "The Advanced OCR Result to merge")
        ocrData: AdvancedOCRResult,
        @CapabilityParam(description = "Chapter Vision segmentation result containing balloon instances")
        chapterVisionResult: ChapterVisionResult,
        context: PluginContext
    ): AdvancedOCRResult {
        context.logger.info("Merging Advanced OCR Result using ChapterVisionResult (total vision pages: ${chapterVisionResult.results.size}, total OCR items: ${ocrData.texts.size})")
        return OcrVisionMerger.mergeChapterAdvancedOcrResult(
            ocrData = ocrData,
            chapterVisionResult = chapterVisionResult,
            separator = " "
        )
    }

    @Capability(
        name = "merge_single_ocr_with_vision",
        description = "Merges multi-line single image OCR text entries that fall within the same speech balloon using Vision instance segmentation"
    )
    suspend fun mergeSingleOcrWithVision(
        @CapabilityParam(description = "The OCR Result to merge")
        ocrData: OCRResult,
        @CapabilityParam(description = "Single image Vision segmentation result containing balloon instances")
        visionResult: VisionResult,
        context: PluginContext
    ): OCRResult {
        context.logger.info("Merging single OCR Result using VisionResult (total vision objects: ${visionResult.objects.size}, total OCR items: ${ocrData.texts.size})")
        return OcrVisionMerger.mergeOcrResult(
            ocrData = ocrData,
            visionResult = visionResult,
            separator = " "
        )
    }

    @Capability(
        name = "merge_single_advanced_ocr_with_vision",
        description = "Merges multi-line single image Advanced OCR text entries that fall within the same speech balloon using Vision instance segmentation"
    )
    suspend fun mergeSingleAdvancedOcrWithVision(
        @CapabilityParam(description = "The Advanced OCR Result to merge")
        ocrData: AdvancedOCRResult,
        @CapabilityParam(description = "Single image Vision segmentation result containing balloon instances")
        visionResult: VisionResult,
        context: PluginContext
    ): AdvancedOCRResult {
        context.logger.info("Merging single Advanced OCR Result using VisionResult (total vision objects: ${visionResult.objects.size}, total OCR items: ${ocrData.texts.size})")
        return OcrVisionMerger.mergeAdvancedOcrResult(
            ocrData = ocrData,
            visionResult = visionResult,
            separator = " "
        )
    }

    @PluginSetup
    suspend fun setup(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[OCR_IA] setup: Starting OCR IA plugin setup...")
        logger.info("[OCR_IA] setup: Setup finished successfully.")
        return Result.success(Unit)
    }

    @PluginUpdate
    suspend fun update(context: PluginContext): Result<Unit> {
        context.logger.info("[OCR_IA] update: OCR IA update hook complete.")
        return Result.success(Unit)
    }

    @PluginValidate
    suspend fun validate(context: PluginContext): Result<Unit> {
        val logger = context.logger
        logger.info("[OCR_IA] validate: Validating OCR IA plugin requirements...")
        logger.info("[OCR_IA] validate: Google API Key configured: ${settings.googleApiKey.isNotBlank()}")
        logger.info("[OCR_IA] validate: Validation passed successfully.")
        return Result.success(Unit)
    }
}
