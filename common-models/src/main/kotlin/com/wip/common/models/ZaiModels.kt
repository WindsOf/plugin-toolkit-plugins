package com.wip.common.models

/**
 * Common model identifiers and configurations for Z.AI (Zhipu AI) GLM models.
 */
object ZaiModels {
    const val DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"
    const val MODEL_GLM_5_3_FLASH: String = "glm-5.3-flash"
    const val MODEL_GLM_5_3_FLASHX: String = "glm-5.3-flashx"
    const val MODEL_GLM_4_7_FLASH: String = "glm-4.7-flash"

    val ALL_MODELS: List<String> = listOf(
        MODEL_GLM_5_3_FLASH,
        MODEL_GLM_5_3_FLASHX,
        MODEL_GLM_4_7_FLASH
    )

    /**
     * Checks whether the provided model ID belongs to the Z.AI GLM family.
     */
    fun isZaiModel(modelId: String): Boolean {
        val clean = modelId.trim().lowercase()
        return clean == MODEL_GLM_5_3_FLASH ||
            clean == MODEL_GLM_5_3_FLASHX ||
            clean == MODEL_GLM_4_7_FLASH ||
            clean.startsWith("glm-")
    }
}

/**
 * Enumeration of supported Z.AI GLM models.
 */
enum class ZaiModel(
    val modelId: String,
    val displayName: String,
    val supportsThinking: Boolean = true,
    val supportsVision: Boolean = true
) {
    GLM_5_3_FLASH(
        modelId = ZaiModels.MODEL_GLM_5_3_FLASH,
        displayName = "GLM-5.3-Flash",
        supportsThinking = true,
        supportsVision = true
    ),
    GLM_5_3_FLASHX(
        modelId = ZaiModels.MODEL_GLM_5_3_FLASHX,
        displayName = "GLM-5.3-FlashX",
        supportsThinking = true,
        supportsVision = true
    ),
    GLM_4_7_FLASH(
        modelId = ZaiModels.MODEL_GLM_4_7_FLASH,
        displayName = "GLM-4.7-Flash",
        supportsThinking = true,
        supportsVision = true
    );

    companion object {
        fun fromModelId(id: String): ZaiModel? {
            val clean = id.trim().lowercase()
            return entries.firstOrNull { it.modelId.equals(clean, ignoreCase = true) }
        }
    }
}
