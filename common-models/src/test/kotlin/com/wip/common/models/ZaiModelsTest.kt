package com.wip.common.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZaiModelsTest {

    @Test
    fun testConstants() {
        assertEquals("https://api.z.ai/api/paas/v4", ZaiModels.DEFAULT_BASE_URL)
        assertEquals("glm-5.3-flash", ZaiModels.MODEL_GLM_5_3_FLASH)
        assertEquals("glm-5.3-flashx", ZaiModels.MODEL_GLM_5_3_FLASHX)
        assertEquals("glm-4.7-flash", ZaiModels.MODEL_GLM_4_7_FLASH)
        assertEquals(3, ZaiModels.ALL_MODELS.size)
    }

    @Test
    fun testIsZaiModel() {
        assertTrue(ZaiModels.isZaiModel("glm-5.3-flash"))
        assertTrue(ZaiModels.isZaiModel("glm-5.3-flashx"))
        assertTrue(ZaiModels.isZaiModel("glm-4.7-flash"))
        assertTrue(ZaiModels.isZaiModel("GLM-5.3-FLASH"))
        assertTrue(ZaiModels.isZaiModel("glm-4-plus"))
        assertFalse(ZaiModels.isZaiModel("gemini-3.8-flash"))
        assertFalse(ZaiModels.isZaiModel("deepseek-flash"))
    }

    @Test
    fun testEnumValues() {
        assertEquals("glm-5.3-flash", ZaiModel.GLM_5_3_FLASH.modelId)
        assertEquals("glm-5.3-flashx", ZaiModel.GLM_5_3_FLASHX.modelId)
        assertEquals("glm-4.7-flash", ZaiModel.GLM_4_7_FLASH.modelId)

        assertTrue(ZaiModel.GLM_5_3_FLASH.supportsThinking)
        assertTrue(ZaiModel.GLM_5_3_FLASH.supportsVision)
        assertTrue(ZaiModel.GLM_5_3_FLASHX.supportsThinking)
        assertTrue(ZaiModel.GLM_5_3_FLASHX.supportsVision)
        assertTrue(ZaiModel.GLM_4_7_FLASH.supportsThinking)
        assertTrue(ZaiModel.GLM_4_7_FLASH.supportsVision)

        assertEquals(ZaiModel.GLM_5_3_FLASH, ZaiModel.fromModelId("glm-5.3-flash"))
        assertEquals(ZaiModel.GLM_5_3_FLASHX, ZaiModel.fromModelId("GLM-5.3-FLASHX"))
        assertEquals(ZaiModel.GLM_4_7_FLASH, ZaiModel.fromModelId("glm-4.7-flash"))
        assertNull(ZaiModel.fromModelId("unknown-model"))
    }
}
