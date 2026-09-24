package com.wip.common.inference.deepseek

import com.wip.common.inference.llm.ReasoningEffortLevel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepSeekManagerTest {

    @Test
    fun testDeepSeekModelConstants() {
        assertEquals("deepseek-flash", DeepSeekManager.MODEL_FLASH)
        assertEquals("deepseek-v4-pro", DeepSeekManager.MODEL_PRO)
        assertEquals("https://api.deepseek.com", DeepSeekManager.DEFAULT_BASE_URL)
    }

    @Test
    fun testCheckStatusUnreachable() = runBlocking {
        val manager = DeepSeekManager()
        // Port 59998 is typically unused, should fail gracefully
        val status = manager.checkStatus(baseUrl = "http://127.0.0.1:59998/v1", apiKey = "test-key")
        assertFalse(status.connected)
        assertNotNull(status.errorMessage)
        assertEquals("http://127.0.0.1:59998/v1", status.baseUrl)
    }

    @Test
    fun testCreateKoogClient() {
        val manager = DeepSeekManager()
        val client = manager.createKoogClient(apiKey = "sk-test", baseUrl = "https://api.deepseek.com")
        assertNotNull(client)
    }

    @Test
    fun testReasoningEffortLevelEnum() {
        val levels = ReasoningEffortLevel.entries.map { it.id }
        assertTrue(levels.contains("default"))
        assertTrue(levels.contains("low"))
        assertTrue(levels.contains("medium"))
        assertTrue(levels.contains("high"))
    }
}
