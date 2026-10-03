package com.wip.vision

import com.wip.common.inference.llama.LlamaBackend
import com.wip.common.inference.llama.LlamaInferenceClient
import com.wip.common.inference.llama.LlamaServerConfig
import com.wip.common.inference.llama.LlamaServerManager
import com.wip.common.inference.llama.LlamaServerMode
import com.wip.common.models.ModelCatalog
import com.wip.common.models.ModelManager
import com.wip.common.models.SegmentedObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.regex.Pattern
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wip.plugintoolkit.api.PluginContext

object VisionTextReclassifier {

    suspend fun reclassifyTextElements(
        image: BufferedImage,
        objects: List<SegmentedObject>,
        mode: VisionReclassificationMode,
        context: PluginContext
    ): List<SegmentedObject> {
        if (mode == VisionReclassificationMode.NONE) return objects

        val textIndices = objects.indices.filter {
            objects[it].label.contains("text", ignoreCase = true)
        }
        if (textIndices.isEmpty()) return objects

        val logger = context.logger

        // Identify installed Qwen model
        val candidateGgufIds = listOf(
            ModelCatalog.QWEN3_VL_4B_Q4_K_M_ID,
            ModelCatalog.QWEN3_VL_4B_Q8_0_ID,
            ModelCatalog.QWEN3_VL_8B_Q4_K_M_ID,
            ModelCatalog.QWEN3_VL_8B_Q8_0_ID,
            ModelCatalog.QWEN3_VL_4B_ID,
            ModelCatalog.QWEN3_VL_8B_ID
        )

        val installedId = candidateGgufIds.firstOrNull {
            ModelManager.Default.isModelInstalled(it, context.fileSystem, logger)
        }

        if (installedId == null) {
            logger.warn("[VisionTextReclassifier] No Qwen3-VL model installed in plugin storage. Skipping text reclassification.")
            return objects
        }

        val modelPath = ModelManager.Default.getModelAbsolutePath(installedId, context.fileSystem)
        if (!File(modelPath).exists() || !modelPath.endsWith(".gguf", ignoreCase = true)) {
            logger.warn("[VisionTextReclassifier] Model file '$modelPath' is invalid. Skipping reclassification.")
            return objects
        }

        val mmprojPath = ModelManager.Default.getMmprojAbsolutePath(installedId, context.fileSystem)
        val llamaConfig = LlamaServerConfig(
            mode = LlamaServerMode.AUTO,
            backend = LlamaBackend.AUTO,
            gpuLayers = 99,
            port = 8080,
            contextSize = 4096,
            mmprojPath = mmprojPath
        )

        val server = try {
            LlamaServerManager.Default.getOrStartServer(
                modelPath = modelPath,
                config = llamaConfig,
                fileSystem = context.fileSystem,
                logger = logger
            )
        } catch (e: Exception) {
            logger.warn("[VisionTextReclassifier] Failed to start llama-server for '$modelPath': ${e.message}")
            null
        } ?: return objects

        val imgW = image.width
        val imgH = image.height
        val updatedObjects = objects.toMutableList()

        for (idx in textIndices) {
            val obj = updatedObjects[idx]
            val box = obj.box

            // Compute crop box with padding
            val yminPx = (box.ymin * imgH).toInt()
            val xminPx = (box.xmin * imgW).toInt()
            val ymaxPx = (box.ymax * imgH).toInt()
            val xmaxPx = (box.xmax * imgW).toInt()

            val padX = max(10, ((xmaxPx - xminPx) * 0.1).toInt())
            val padY = max(10, ((ymaxPx - yminPx) * 0.1).toInt())

            val cropXmin = max(0, xminPx - padX)
            val cropYmin = max(0, yminPx - padY)
            val cropXmax = min(imgW, xmaxPx + padX)
            val cropYmax = min(imgH, ymaxPx + padY)

            val cropW = max(1, cropXmax - cropXmin)
            val cropH = max(1, cropYmax - cropYmin)

            val cropImg = image.getSubimage(cropXmin, cropYmin, cropW, cropH)

            val base64 = withContext(Dispatchers.IO) {
                val baos = ByteArrayOutputStream()
                ImageIO.write(cropImg, "png", baos)
                Base64.getEncoder().encodeToString(baos.toByteArray())
            }
            val dataUrl = "data:image/png;base64,$base64"

            val prompt = """
            You are an expert manga/comic text classifier.
            Inspect this close-up crop of comic/manga artwork.
            Focus specifically on the central text element in this crop (ignore peripheral text that may be partially visible at the edges).
            Classify the central element into exactly one category:
            - "speech": regular dialogue, narration, spoken words, thoughts inside or outside balloons.
            - "sfx": sound effects, onomatopoeia, stylized action lettering, ambient sound words.
            - "non_text": drawing details, screentone patterns, textures, character features, background art, or non-text artifacts mistakenly identified as text.

            Respond strictly with JSON:
            {"category": "speech" | "sfx" | "non_text"}
            """.trimIndent()

            try {
                val rawResponse = LlamaInferenceClient.Default.executeVisionChatBase64(
                    baseUrl = server.baseUrl,
                    dataUrl = dataUrl,
                    promptInstructions = prompt,
                    temperature = 0.1,
                    maxTokens = 64,
                    logger = logger
                )

                val classifiedCategory = parseCategoryFromResponse(rawResponse)
                if (classifiedCategory != null) {
                    val newLabel = when (classifiedCategory) {
                        "speech" -> "speech"
                        "sfx" -> "sfx"
                        "non_text" -> "non_text"
                        else -> obj.label
                    }
                    if (newLabel != obj.label) {
                        val newBox = box.copy(label = newLabel)
                        updatedObjects[idx] = obj.copy(label = newLabel, box = newBox)
                    }
                }
            } catch (e: Exception) {
                logger.warn("[VisionTextReclassifier] Failed to reclassify object at index $idx: ${e.message}")
            }
        }

        return updatedObjects
    }

    fun parseCategoryFromResponse(raw: String): String? {
        val jsonPattern = Pattern.compile("\\{[\\s\\S]*?\"category\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]*?\\}", Pattern.CASE_INSENSITIVE)
        val matcher = jsonPattern.matcher(raw)
        if (matcher.find()) {
            val cat = matcher.group(1).trim().lowercase()
            return when (cat) {
                "speech" -> "speech"
                "sfx" -> "sfx"
                "non_text" -> "non_text"
                else -> null
            }
        }
        val clean = raw.trim().lowercase()
        return when {
            clean.contains("non_text") || clean.contains("non-text") || clean.contains("non text") -> "non_text"
            clean.contains("speech") && !clean.contains("sfx") -> "speech"
            clean.contains("sfx") && !clean.contains("speech") -> "sfx"
            else -> null
        }
    }
}
