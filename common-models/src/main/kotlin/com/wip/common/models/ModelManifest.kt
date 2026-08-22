package com.wip.common.models

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.annotations.CapabilityResult
import org.wip.plugintoolkit.api.annotations.ComplexObject

/**
 * Supported model architecture families.
 */
enum class ModelType {
    YOLO_V10,
    RFDETR_SEG,
    UNKNOWN;

    companion object {
        fun fromString(type: String): ModelType {
            return when (type.trim().lowercase()) {
                "yolov10", "yolo_v10", "yolo" -> YOLO_V10
                "rfdetr_seg", "rfdetr-seg", "rfdetr" -> RFDETR_SEG
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Model specification loaded from .yaml descriptor files.
 */
@Serializable
data class ModelSpec(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    val name: String,
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("model_path")
    val modelPath: String,
    @SerialName("input_width")
    val inputWidth: Int = 640,
    @SerialName("input_height")
    val inputHeight: Int = 640,
    @SerialName("score_threshold")
    val scoreThreshold: Double = 0.25,
    @SerialName("conf_threshold")
    val confThreshold: Double = 0.25,
    @SerialName("iou_threshold")
    val iouThreshold: Double = 0.45,
    @SerialName("classes")
    val classes: List<String> = emptyList()
) {
    val modelType: ModelType
        get() = ModelType.fromString(type)

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
    val onnxUrl: String,
    val lockKey: String,
    val description: String,
    val type: ModelType
)

/**
 * Built-in catalog of known remote ONNX models.
 */
object ModelCatalog {
    const val YOLO_DET_X_ID = "yolo-det-x-best-v3"
    const val RFDETR_SEG_2XLARGE_ID = "rfdetr-seg-2xlarge-ema-v3"

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

    val ALL_MODELS: List<ModelCatalogEntry> = listOf(
        YOLO_DET_X,
        RFDETR_SEG_2XLARGE
    )

    fun findById(id: String): ModelCatalogEntry? {
        return ALL_MODELS.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }

    fun getLockKey(modelId: String): String = "model:${modelId.trim().lowercase()}"
}

/**
 * Detection result for a single detected bounding box.
 */
@ComplexObject(
    id = "com.wip.common.models.DetectionBox",
    description = "A single detected box with label and confidence score",
    version = 1
)
@Serializable
data class DetectionBox(
    @CapabilityResult(name = "label", description = "Class label of the detection")
    val label: String,
    @CapabilityResult(name = "confidence", description = "Detection confidence score between 0.0 and 1.0")
    val confidence: Double,
    @CapabilityResult(name = "ymin", description = "Normalized top coordinate")
    val ymin: Double,
    @CapabilityResult(name = "xmin", description = "Normalized left coordinate")
    val xmin: Double,
    @CapabilityResult(name = "ymax", description = "Normalized bottom coordinate")
    val ymax: Double,
    @CapabilityResult(name = "xmax", description = "Normalized right coordinate")
    val xmax: Double
)

/**
 * Result collection of object detections for an image.
 */
@ComplexObject(
    id = "com.wip.common.models.DetectionResult",
    description = "List of detections with coordinates and classes",
    version = 1
)
@Serializable
data class DetectionResult(
    val boxes: List<DetectionBox>,
    val imageWidth: Int,
    val imageHeight: Int
)
