package com.wip.common.models

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.annotations.CapabilityResult
import org.wip.plugintoolkit.api.annotations.ComplexObject
import org.wip.plugintoolkit.api.annotations.RequiresLock

/**
 * Supported model architecture families.
 */
enum class ModelType {
    YOLO_V10,
    RFDETR_SEG,
    INPAINTING,
    OCR,
    UNKNOWN;

    companion object {
        fun fromString(type: String): ModelType {
            return when (type.trim().lowercase()) {
                "yolov10", "yolo_v10", "yolo" -> YOLO_V10
                "rfdetr_seg", "rfdetr-seg", "rfdetr" -> RFDETR_SEG
                "inpainting", "lama", "mat", "manga_inpainting" -> INPAINTING
                "ocr", "deepseek_ocr", "deepseek_ocr_decoder", "unlimited_ocr", "unlimited-ocr" -> OCR
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Model specification loaded from .yaml descriptor files.
 */
@Serializable
data class PipelineConfig(
    @SerialName("num_timesteps")
    val numTimesteps: Int = 1000,
    @SerialName("default_inference_steps")
    val defaultInferenceSteps: Int = 50,
    @SerialName("beta_schedule")
    val betaSchedule: String = "linear",
    @SerialName("channels")
    val channels: Int = 3,
    @SerialName("embed_dim")
    val embedDim: Int = 256
)

@Serializable
data class TensorIoSpec(
    @SerialName("name")
    val name: String = "",
    @SerialName("shape")
    val shape: List<String> = emptyList(),
    @SerialName("type")
    val type: String = "float32",
    @SerialName("dtype")
    val dtype: String = "float32",
    @SerialName("description")
    val description: String = ""
) {
    val effectiveType: String
        get() = if (type.isNotBlank() && type != "float32") type else dtype
}

@Serializable
data class ComponentSpec(
    @SerialName("file")
    val file: String = "",
    @SerialName("inputs")
    val inputs: List<TensorIoSpec> = emptyList(),
    @SerialName("outputs")
    val outputs: List<TensorIoSpec> = emptyList()
)

/**
 * Model specification loaded from .yaml descriptor files.
 */
@Serializable
data class ModelSpec(
    @SerialName("type")
    val type: String = "",
    @SerialName("model_type")
    val modelTypeRaw: String = "",
    @SerialName("format")
    val format: String = "onnx",
    @SerialName("pipeline_type")
    val pipelineType: String = "single_pass",
    @SerialName("name")
    val name: String = "",
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("model_path")
    val modelPath: String = "",
    @SerialName("onnx_file")
    val onnxFile: String = "",
    @SerialName("data_file")
    val dataFile: String = "",
    @SerialName("gguf_file")
    val ggufFile: String = "",
    @SerialName("external_data")
    val externalData: Boolean = false,
    @SerialName("description")
    val description: String = "",
    @SerialName("repo")
    val repo: String = "",
    @SerialName("vocab_size")
    val vocabSize: Int = 0,
    @SerialName("hidden_size")
    val hiddenSize: Int = 0,
    @SerialName("num_layers")
    val numLayers: Int = 0,
    @SerialName("input_width")
    val inputWidth: Int = 640,
    @SerialName("input_height")
    val inputHeight: Int = 640,
    @SerialName("input_resolution")
    val inputResolution: List<Int> = emptyList(),
    @SerialName("dynamic_shape")
    val dynamicShape: Boolean = false,
    @SerialName("dynamic_axes")
    val dynamicAxes: Boolean = false,
    @SerialName("norm_mode")
    val normMode: String = "",
    @SerialName("mask_mode")
    val maskMode: String = "",
    @SerialName("input_names")
    val inputNames: List<String> = emptyList(),
    @SerialName("output_names")
    val outputNames: List<String> = emptyList(),
    @SerialName("opset_version")
    val opsetVersion: Int = 17,
    @SerialName("score_threshold")
    val scoreThreshold: Double = 0.25,
    @SerialName("conf_threshold")
    val confThreshold: Double = 0.25,
    @SerialName("iou_threshold")
    val iouThreshold: Double = 0.45,
    @SerialName("classes")
    val classes: List<String> = emptyList(),
    @SerialName("files")
    val files: Map<String, String> = emptyMap(),
    @SerialName("components")
    val components: Map<String, ComponentSpec> = emptyMap(),
    @SerialName("inputs")
    val inputs: List<TensorIoSpec> = emptyList(),
    @SerialName("outputs")
    val outputs: List<TensorIoSpec> = emptyList(),
    @SerialName("pipeline_config")
    val pipelineConfig: PipelineConfig = PipelineConfig()
) {
    val effectiveType: String
        get() = if (type.isNotBlank()) type else modelTypeRaw

    val modelType: ModelType
        get() = ModelType.fromString(effectiveType)

    val effectiveWidth: Int
        get() = if (inputResolution.size >= 2) inputResolution[0] else inputWidth

    val effectiveHeight: Int
        get() = if (inputResolution.size >= 2) inputResolution[1] else inputHeight

    fun getRequiredFileNames(defaultModelId: String): List<String> {
        val list = mutableListOf<String>()
        if (files.isNotEmpty()) {
            list.addAll(files.values)
        } else {
            if (onnxFile.isNotBlank()) list.add(onnxFile)
            if (dataFile.isNotBlank()) list.add(dataFile)
            if (ggufFile.isNotBlank()) list.add(ggufFile)
            if (modelPath.isNotBlank()) list.add(modelPath)
            if (list.isEmpty()) {
                if (format.equals("gguf", ignoreCase = true)) {
                    list.add("$defaultModelId.gguf")
                } else {
                    list.add("$defaultModelId.onnx")
                    if (externalData) {
                        list.add("$defaultModelId.onnx.data")
                    }
                }
            }
        }
        return list.distinct()
    }

    companion object {
        private val yamlParser = Yaml(
            configuration = YamlConfiguration(
                strictMode = false
            )
        )

        /**
         * Parse a ModelSpec from a YAML string.
         */
        fun parseFromYaml(yamlContent: String): ModelSpec {
            return yamlParser.decodeFromString(serializer(), yamlContent)
        }
    }
}

/**
 * Entry in the model catalog containing remote URLs and metadata.
 */
@Serializable
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val yamlUrl: String,
    val onnxUrl: String = "",
    val lockKey: String,
    val description: String,
    val type: ModelType,
    val format: String = "onnx",
    val extraFileUrls: Map<String, String> = emptyMap()
)

/**
 * Built-in catalog of known remote models.
 */
object ModelCatalog {
    const val YOLO_DET_X_ID = "yolo-det-x-best-v3"
    const val RFDETR_SEG_2XLARGE_ID = "rfdetr-seg-2xlarge-ema-v3"
    const val LAMA_ID = "big-lama"
    const val MAT_ID = "Places_512_FullData_G"
    const val MANGA_ID = "anime-manga-big-lama"
    const val LDM_ID = "diffusion"
    const val ZITS_ID = "zits-inpaint-0717"
    const val FCF_ID = "places_512_G"
    const val MIGAN_ID = "migan_traced"
    const val UNLIMITED_OCR_ID = "Unlimited-OCR"
    const val UNLIMITED_OCR_BF16_ID = "Unlimited-OCR-BF16"
    const val UNLIMITED_OCR_Q4_K_M_ID = "Unlimited-OCR-Q4_K_M"
    const val UNLIMITED_OCR_IQ2_M_ID = "Unlimited-OCR-IQ2_M"

    val YOLO_DET_X = ModelCatalogEntry(
        id = YOLO_DET_X_ID,
        displayName = "YOLO Det X Best v3",
        yamlUrl = "https://www.windsofresub.cloud/models/yolo-det-x-best-v3.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/yolo-det-x-best-v3.onnx",
        lockKey = "model:$YOLO_DET_X_ID",
        description = "Object detection model (balloon, text, watermark)",
        type = ModelType.YOLO_V10
    )

    val RFDETR_SEG_2XLARGE = ModelCatalogEntry(
        id = RFDETR_SEG_2XLARGE_ID,
        displayName = "RF-DETR Seg 2XLarge EMA v3",
        yamlUrl = "https://www.windsofresub.cloud/models/rfdetr-seg-2xlarge-ema-v3.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/rfdetr-seg-2xlarge-ema-v3.onnx",
        lockKey = "model:$RFDETR_SEG_2XLARGE_ID",
        description = "Instance segmentation model for speech balloon shapes and text",
        type = ModelType.RFDETR_SEG
    )

    val LAMA = ModelCatalogEntry(
        id = LAMA_ID,
        displayName = "LaMa",
        yamlUrl = "https://www.windsofresub.cloud/models/big-lama.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/big-lama.onnx",
        lockKey = "model:$LAMA_ID",
        description = "Large Mask Inpainting ONNX model for high-resolution background restoration",
        type = ModelType.INPAINTING
    )

    val MAT = ModelCatalogEntry(
        id = MAT_ID,
        displayName = "MAT (Places 512)",
        yamlUrl = "https://www.windsofresub.cloud/models/Places_512_FullData_G.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/Places_512_FullData_G.onnx",
        lockKey = "model:$MAT_ID",
        description = "Mask-Aware Transformer inpainting ONNX model",
        type = ModelType.INPAINTING
    )

    val MANGA = ModelCatalogEntry(
        id = MANGA_ID,
        displayName = "Manga (Anime LaMa)",
        yamlUrl = "https://www.windsofresub.cloud/models/anime-manga-big-lama.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/anime-manga-big-lama.onnx",
        lockKey = "model:$MANGA_ID",
        description = "Anime & Manga screentone inpainting ONNX model",
        type = ModelType.INPAINTING
    )

    val LDM = ModelCatalogEntry(
        id = LDM_ID,
        displayName = "Diffusion (LDM)",
        yamlUrl = "https://www.windsofresub.cloud/models/diffusion.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/diffusion.onnx",
        lockKey = "model:$LDM_ID",
        description = "Latent Diffusion Model inpainting ONNX model",
        type = ModelType.INPAINTING
    )

    val ZITS = ModelCatalogEntry(
        id = ZITS_ID,
        displayName = "ZITS (0717)",
        yamlUrl = "https://www.windsofresub.cloud/models/zits-inpaint-0717.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/zits-inpaint-0717.onnx",
        lockKey = "model:$ZITS_ID",
        description = "Incremental Transformer Structure inpainting ONNX model",
        type = ModelType.INPAINTING
    )

    val FCF = ModelCatalogEntry(
        id = FCF_ID,
        displayName = "FCF (Places 512)",
        yamlUrl = "https://www.windsofresub.cloud/models/places_512_G.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/places_512_G.onnx",
        lockKey = "model:$FCF_ID",
        description = "Fast Co-Modulated Flow inpainting ONNX model",
        type = ModelType.INPAINTING
    )

    val MIGAN = ModelCatalogEntry(
        id = MIGAN_ID,
        displayName = "MIGAN",
        yamlUrl = "https://www.windsofresub.cloud/models/migan_traced.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/migan_traced.onnx",
        lockKey = "model:$MIGAN_ID",
        description = "Manga Inpainting GAN ONNX model",
        type = ModelType.INPAINTING
    )

    val UNLIMITED_OCR = ModelCatalogEntry(
        id = UNLIMITED_OCR_ID,
        displayName = "Unlimited-OCR",
        yamlUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR.onnx",
        lockKey = "model:$UNLIMITED_OCR_ID",
        description = "Baidu Unlimited-OCR high-precision Vision-Language text extraction ONNX model with external data",
        type = ModelType.OCR,
        format = "onnx"
    )

    val UNLIMITED_OCR_BF16 = ModelCatalogEntry(
        id = UNLIMITED_OCR_BF16_ID,
        displayName = "Unlimited-OCR (BF16)",
        yamlUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR-BF16.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR-BF16.onnx",
        lockKey = "model:$UNLIMITED_OCR_BF16_ID",
        description = "Baidu Unlimited-OCR high-precision Vision-Language text extraction model with external data",
        type = ModelType.OCR,
        format = "onnx"
    )

    val UNLIMITED_OCR_Q4_K_M = ModelCatalogEntry(
        id = UNLIMITED_OCR_Q4_K_M_ID,
        displayName = "Unlimited-OCR (Q4_K_M)",
        yamlUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR-Q4_K_M.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR-Q4_K_M.gguf",
        lockKey = "model:$UNLIMITED_OCR_Q4_K_M_ID",
        description = "Baidu Unlimited-OCR 4-bit quantized GGUF model for low-VRAM environments",
        type = ModelType.OCR,
        format = "gguf"
    )

    val UNLIMITED_OCR_IQ2_M = ModelCatalogEntry(
        id = UNLIMITED_OCR_IQ2_M_ID,
        displayName = "Unlimited-OCR (IQ2_M)",
        yamlUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR-IQ2_M.yaml",
        onnxUrl = "https://www.windsofresub.cloud/models/Unlimited-OCR-IQ2_M.gguf",
        lockKey = "model:$UNLIMITED_OCR_IQ2_M_ID",
        description = "Baidu Unlimited-OCR 2-bit quantized GGUF model for ultra low-spec systems",
        type = ModelType.OCR,
        format = "gguf"
    )

    val ALL_MODELS: List<ModelCatalogEntry> = listOf(
        YOLO_DET_X,
        RFDETR_SEG_2XLARGE,
        LAMA,
        MAT,
        MANGA,
        LDM,
        ZITS,
        FCF,
        MIGAN,
        UNLIMITED_OCR,
        UNLIMITED_OCR_BF16,
        UNLIMITED_OCR_Q4_K_M,
        UNLIMITED_OCR_IQ2_M
    )

    fun findById(id: String): ModelCatalogEntry? {
        val clean = id.trim().lowercase()
        return ALL_MODELS.firstOrNull {
            it.id.equals(clean, ignoreCase = true) ||
            (clean == "lama" && it.id == LAMA_ID) ||
            (clean == "manga" && it.id == MANGA_ID) ||
            (clean == "migan" && it.id == MIGAN_ID) ||
            (clean == "ldm" && it.id == LDM_ID) ||
            (clean == "diffusion" && it.id == LDM_ID) ||
            (clean == "mat" && it.id == MAT_ID) ||
            (clean == "places_512_fulldata_g" && it.id == MAT_ID) ||
            (clean == "fcf" && it.id == FCF_ID) ||
            (clean == "places_512_g" && it.id == FCF_ID) ||
            (clean == "zits" && it.id == ZITS_ID) ||
            (clean == "zits-inpaint-0717" && it.id == ZITS_ID) ||
            (clean == "big-lama" && it.id == LAMA_ID) ||
            (clean == "anime-manga-big-lama" && it.id == MANGA_ID) ||
            (clean == "migan_traced" && it.id == MIGAN_ID) ||
            (clean == "unlimited-ocr" && it.id == UNLIMITED_OCR_ID) ||
            (clean == "unlimited-ocr-bf16" && it.id == UNLIMITED_OCR_BF16_ID) ||
            (clean == "unlimited-ocr-q4_k_m" && it.id == UNLIMITED_OCR_Q4_K_M_ID) ||
            (clean == "unlimited-ocr-iq2_m" && it.id == UNLIMITED_OCR_IQ2_M_ID)
        }
    }

    fun getLockKey(modelId: String): String = "model:${modelId.trim().lowercase()}"
}

/**
 * Inpainting models supported by Cleaner plugin with lock constraints.
 */
enum class InpaintingModel(val modelId: String, val displayName: String) {
    @RequiresLock(locks = ["model:lama"])
    LAMA("lama", "LaMa"),

    @RequiresLock(locks = ["model:manga"])
    MANGA("manga", "Manga"),

    @RequiresLock(locks = ["model:migan"])
    MIGAN("migan", "MIGAN")
}

/**
 * Vision models supported by Vision plugin with lock constraints.
 */
enum class VisionModel(val modelId: String, val displayName: String) {
    @RequiresLock(locks = ["model:yolo-det-x-best-v3"])
    YOLO_DET_X("yolo-det-x-best-v3", "YOLO Det X Best v3"),

    @RequiresLock(locks = ["model:rfdetr-seg-2xlarge-ema-v3"])
    RFDETR_SEG_2XLARGE("rfdetr-seg-2xlarge-ema-v3", "RF-DETR Seg 2XLarge EMA v3")
}
