package com.wip.common.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OnnxInferenceEngineTest {

    @Test
    fun testEnvironmentInitialization() {
        val env = OnnxInferenceEngine.environment
        assertNotNull(env)
    }

    @Test
    fun testExecutionDeviceEnum() {
        assertEquals(5, ExecutionDevice.entries.size)
        assertEquals(ExecutionDevice.AUTO, ExecutionDevice.valueOf("AUTO"))
        assertEquals(ExecutionDevice.CPU, ExecutionDevice.valueOf("CPU"))
        assertEquals(ExecutionDevice.CUDA, ExecutionDevice.valueOf("CUDA"))
        assertEquals(ExecutionDevice.DIRECT_ML, ExecutionDevice.valueOf("DIRECT_ML"))
        assertEquals(ExecutionDevice.CORE_ML, ExecutionDevice.valueOf("CORE_ML"))
    }
}
