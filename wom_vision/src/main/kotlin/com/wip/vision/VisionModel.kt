package com.wip.vision

import org.wip.plugintoolkit.api.annotations.RequiresLock

/**
 * Enumeration of vision models for Vision plugin capabilities with UI lock requirements.
 */
enum class VisionModel(val modelId: String, val displayName: String, val isCore: Boolean = true) {
    @RequiresLock(locks = ["model:yolo-det-x-best-v3"])
    YOLO_DET_X("yolo-det-x-best-v3", "YOLO Det X Best V3", isCore = true),

    @RequiresLock(locks = ["model:rfdetr-seg-2xlarge-ema-v3"])
    RFDETR_SEG_2XLARGE("rfdetr-seg-2xlarge-ema-v3", "RF-DETR Seg 2XLarge EMA V3", isCore = true),

    @RequiresLock(locks = ["model:Qwen3-VL-4B-Instruct-Q4_K_M", "model:qwen3-vl-4b-instruct-q4_k_m"])
    QWEN3_VL_4B_Q4_K_M("Qwen3-VL-4B-Instruct-Q4_K_M", "Qwen3-VL 4B Instruct (Q4_K_M)", isCore = false),

    @RequiresLock(locks = ["model:Qwen3-VL-4B-Instruct-Q8_0", "model:qwen3-vl-4b-instruct-q8_0"])
    QWEN3_VL_4B_Q8_0("Qwen3-VL-4B-Instruct-Q8_0", "Qwen3-VL 4B Instruct (Q8_0)", isCore = false),

    @RequiresLock(locks = ["model:Qwen3-VL-8B-Instruct-Q4_K_M", "model:qwen3-vl-8b-instruct-q4_k_m"])
    QWEN3_VL_8B_Q4_K_M("Qwen3-VL-8B-Instruct-Q4_K_M", "Qwen3-VL 8B Instruct (Q4_K_M)", isCore = false),

    @RequiresLock(locks = ["model:Qwen3-VL-8B-Instruct-Q8_0", "model:qwen3-vl-8b-instruct-q8_0"])
    QWEN3_VL_8B_Q8_0("Qwen3-VL-8B-Instruct-Q8_0", "Qwen3-VL 8B Instruct (Q8_0)", isCore = false);

    companion object {
        fun fromModelId(id: String): VisionModel? {
            return entries.find { it.modelId.equals(id, ignoreCase = true) }
        }
    }
}

/**
 * Enumeration of vision models for download actions without lock requirements.
 */
enum class VisionDownloadModel(val modelId: String, val displayName: String) {
    YOLO_DET_X("yolo-det-x-best-v3", "YOLO Det X Best V3"),
    RFDETR_SEG_2XLARGE("rfdetr-seg-2xlarge-ema-v3", "RF-DETR Seg 2XLarge EMA V3"),
    QWEN3_VL_4B_Q4_K_M("Qwen3-VL-4B-Instruct-Q4_K_M", "Qwen3-VL 4B Instruct (Q4_K_M)"),
    QWEN3_VL_4B_Q8_0("Qwen3-VL-4B-Instruct-Q8_0", "Qwen3-VL 4B Instruct (Q8_0)"),
    QWEN3_VL_8B_Q4_K_M("Qwen3-VL-8B-Instruct-Q4_K_M", "Qwen3-VL 8B Instruct (Q4_K_M)"),
    QWEN3_VL_8B_Q8_0("Qwen3-VL-8B-Instruct-Q8_0", "Qwen3-VL 8B Instruct (Q8_0)");

    companion object {
        fun fromModelId(id: String): VisionDownloadModel? {
            return entries.find { it.modelId.equals(id, ignoreCase = true) }
        }
    }
}

/**
 * Reclassification mode for detected text elements.
 */
enum class VisionReclassificationMode {
    NONE,

    @RequiresLock(locks = ["model:qwen"])
    QWEN
}
