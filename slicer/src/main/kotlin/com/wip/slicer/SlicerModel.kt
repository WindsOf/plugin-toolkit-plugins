package com.wip.slicer

import com.wip.common.models.ModelCatalog
import org.wip.plugintoolkit.api.annotations.RequiresLock

/**
 * Enumeration of vision/detection models used by the Slicer plugin with UI lock requirements.
 */
enum class SlicerModel(val modelId: String, val displayName: String) {
    @RequiresLock(locks = ["model:yolo-det-x-best-v3"])
    YOLO_DET_X(ModelCatalog.YOLO_DET_X_ID, "YOLO Det X Best V3");

    companion object {
        fun fromModelId(id: String): SlicerModel? {
            return entries.find { it.modelId.equals(id, ignoreCase = true) }
        }
    }
}

/**
 * Enumeration of detection models for Slicer download actions (renders as dropdown).
 */
enum class SlicerDownloadModel(val modelId: String, val displayName: String) {
    YOLO_DET_X(ModelCatalog.YOLO_DET_X_ID, "YOLO Det X Best V3");

    companion object {
        fun fromModelId(id: String): SlicerDownloadModel? {
            return entries.find { it.modelId.equals(id, ignoreCase = true) }
        }
    }
}
