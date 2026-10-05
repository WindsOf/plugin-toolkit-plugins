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

    fun isBalloonContainer(label: String): Boolean {
        val l = label.lowercase()
        if (l == "text" || l == "watermark" || l == "sfx" || l == "non_text" || l == "none") return false
        return l.contains("balloon") || l.contains("bubble") || l.contains("circular") ||
            l.contains("irregular") || l.contains("jagged") || l.contains("rectangular") ||
            l.contains("spiky")
    }

    fun findEnclosingContainer(textBox: com.wip.common.models.DetectionBox, containers: List<SegmentedObject>): SegmentedObject? {
        val centerX = (textBox.xmin + textBox.xmax) / 2.0
        val centerY = (textBox.ymin + textBox.ymax) / 2.0
        val textArea = (textBox.xmax - textBox.xmin) * (textBox.ymax - textBox.ymin)

        val matching = containers.filter { c ->
            val cBox = c.box
            val interXmin = max(textBox.xmin, cBox.xmin)
            val interYmin = max(textBox.ymin, cBox.ymin)
            val interXmax = min(textBox.xmax, cBox.xmax)
            val interYmax = min(textBox.ymax, cBox.ymax)
            val interArea = max(0.0, interXmax - interXmin) * max(0.0, interYmax - interYmin)
            val containment = if (textArea > 0) interArea / textArea else 0.0

            val centerInside = centerX in cBox.xmin..cBox.xmax && centerY in cBox.ymin..cBox.ymax
            centerInside || containment >= 0.35
        }

        return matching.minByOrNull { (it.box.xmax - it.box.xmin) * (it.box.ymax - it.box.ymin) }
    }

    suspend fun reclassifyTextElements(
        image: BufferedImage,
        objects: List<SegmentedObject>,
        mode: VisionReclassificationMode,
        context: PluginContext,
        debugCropsDir: File? = null,
        pageName: String? = null
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
        val containerCandidates = objects.filter { isBalloonContainer(it.label) }

        for (idx in textIndices) {
            val obj = updatedObjects[idx]
            val box = obj.box

            val enclosingContainer = findEnclosingContainer(box, containerCandidates)

            val cropXmin: Int
            val cropYmin: Int
            val cropXmax: Int
            val cropYmax: Int

            if (enclosingContainer != null) {
                val cBox = enclosingContainer.box
                val unionXmin = min(box.xmin, cBox.xmin)
                val unionYmin = min(box.ymin, cBox.ymin)
                val unionXmax = max(box.xmax, cBox.xmax)
                val unionYmax = max(box.ymax, cBox.ymax)

                val spanW = unionXmax - unionXmin
                val spanH = unionYmax - unionYmin
                val marginX = spanW * 0.05
                val marginY = spanH * 0.05

                cropXmin = max(0, ((unionXmin - marginX) * imgW).toInt())
                cropYmin = max(0, ((unionYmin - marginY) * imgH).toInt())
                cropXmax = min(imgW, ((unionXmax + marginX) * imgW).toInt())
                cropYmax = min(imgH, ((unionYmax + marginY) * imgH).toInt())
            } else {
                val yminPx = (box.ymin * imgH).toInt()
                val xminPx = (box.xmin * imgW).toInt()
                val ymaxPx = (box.ymax * imgH).toInt()
                val xmaxPx = (box.xmax * imgW).toInt()

                val padX = max(20, ((xmaxPx - xminPx) * 0.35).toInt())
                val padY = max(20, ((ymaxPx - yminPx) * 0.35).toInt())

                cropXmin = max(0, xminPx - padX)
                cropYmin = max(0, yminPx - padY)
                cropXmax = min(imgW, xmaxPx + padX)
                cropYmax = min(imgH, ymaxPx + padY)
            }

            val cropW = max(1, cropXmax - cropXmin)
            val cropH = max(1, cropYmax - cropYmin)

            val cropImg = image.getSubimage(cropXmin, cropYmin, cropW, cropH)

            val base64 = withContext(Dispatchers.IO) {
                val baos = ByteArrayOutputStream()
                ImageIO.write(cropImg, "png", baos)
                Base64.getEncoder().encodeToString(baos.toByteArray())
            }
            val dataUrl = "data:image/png;base64,$base64"

            val containerHint = if (enclosingContainer != null) {
                "\nContext hint: Structural detection confirms this text is positioned inside a speech/dialogue balloon or container ('${enclosingContainer.label}'). As stated in the rule, text inside a balloon/container must be classified as 'speech'."
            } else ""

            val prompt = """
            You are an expert manga/comic text classifier.
            Inspect this close-up crop of comic/manga artwork.
            Focus specifically on the central text element in this crop (ignore peripheral text that may be partially visible at the edges).
            $containerHint
            Classify the central element into exactly one category:
            - "speech": regular dialogue, narration, spoken words, thoughts, and any character utterances (including screams, grunts, moans, gasps, sighs, or non-word vocal sounds like "HNNGH", "HAA...", "UGH", "AAAH", "KYAA") that are enclosed within speech/thought balloons, speech bubbles, or dialogue boxes.
              * RULE: If text is inside a balloon or dedicated dialogue container, classify it as "speech" regardless of whether the text is a word, groan, or onomatopoeic utterance.
            - "sfx": sound effects, environmental onomatopoeia, stylized action lettering, impacts, and ambient sound words drawn freeform outside of speech balloons/containers.
            - "non_text": drawing details, screentone patterns, textures, character features, background art, or non-text artifacts mistakenly identified as text.
            
            Respond strictly with JSON:
            {"category": "speech" | "sfx" | "non_text"}
            """.trimIndent()

            var classifiedCategory: String? = null
            try {
                val rawResponse = LlamaInferenceClient.Default.executeVisionChatBase64(
                    baseUrl = server.baseUrl,
                    dataUrl = dataUrl,
                    promptInstructions = prompt,
                    temperature = 0.1,
                    maxTokens = 64,
                    logger = logger
                )

                classifiedCategory = parseCategoryFromResponse(rawResponse)
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

            if (debugCropsDir != null) {
                try {
                    withContext(Dispatchers.IO) {
                        if (!debugCropsDir.exists()) debugCropsDir.mkdirs()
                        val prefix = pageName?.ifBlank { "page" } ?: "page"
                        val filename = "${prefix}_crop_${idx}_${obj.label}_as_${classifiedCategory ?: "unknown"}.png"
                        val cropFile = File(debugCropsDir, filename)
                        ImageIO.write(cropImg, "png", cropFile)
                        logger.info("[VisionTextReclassifier] Saved reclassification debug crop: ${cropFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    logger.warn("[VisionTextReclassifier] Failed to save debug crop for index $idx: ${e.message}")
                }
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
