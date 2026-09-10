package com.wip.vision

import org.wip.plugintoolkit.api.annotations.RequiresLock

/**
 * Enumeration of vision models for Vision plugin capabilities with UI lock requirements.
 */
enum class VisionModel(val modelId: String, val displayName: String) {
    @RequiresLock(locks = ["model:yolo-det-x-best-v3"])
    YOLO_DET_X("yolo-det-x-best-v3", "YOLO Det X Best V3"),

    @RequiresLock(locks = ["model:rfdetr-seg-2xlarge-ema-v3"])
    RFDETR_SEG_2XLARGE("rfdetr-seg-2xlarge-ema-v3", "RF-DETR Seg 2XLarge EMA V3");

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
    RFDETR_SEG_2XLARGE("rfdetr-seg-2xlarge-ema-v3", "RF-DETR Seg 2XLarge EMA V3");

    companion object {
        fun fromModelId(id: String): VisionDownloadModel? {
            return entries.find { it.modelId.equals(id, ignoreCase = true) }
        }
    }
}
