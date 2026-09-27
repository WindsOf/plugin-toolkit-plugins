package com.wip.common.inference.zai

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZaiManagerTest {

    @Test
    fun testZaiModelConstants() {
        assertEquals("glm-5.3-flash", ZaiManager.MODEL_GLM_5_3_FLASH)
        assertEquals("glm-5.3-flashx", ZaiManager.MODEL_GLM_5_3_FLASHX)
        assertEquals("glm-4.7-flash", ZaiManager.MODEL_GLM_4_7_FLASH)
        assertEquals("https://api.z.ai/api/paas/v4", ZaiManager.DEFAULT_BASE_URL)
    }

    @Test
    fun testCheckStatusUnreachable() = runBlocking {
        val manager = ZaiManager()
        // Port 59997 is typically unused, should fail gracefully
        val status = manager.checkStatus(baseUrl = "http://127.0.0.1:59997/v4", apiKey = "test-key")
        assertFalse(status.connected)
        assertNotNull(status.errorMessage)
        assertEquals("http://127.0.0.1:59997/v4", status.baseUrl)
    }

    @Test
    fun testCreateKoogClient() {
        val manager = ZaiManager()
        val client = manager.createKoogClient(apiKey = "zai-test-key", baseUrl = "https://api.z.ai/api/paas/v4")
        assertNotNull(client)
    }

    @Test
    fun testLiveZaiStatusIfKeyPresent() = runBlocking {
        var apiKey = (System.getenv("ZAI_API_KEY") ?: "").trim()
        if (apiKey.isBlank()) {
            val envFile = listOf(File("../.env"), File(".env")).firstOrNull { it.exists() }
            if (envFile != null) {
                val props = java.util.Properties()
                envFile.inputStream().use { props.load(it) }
                apiKey = (props.getProperty("ZAI_API_KEY") ?: "").trim()
            }
        }

        if (apiKey.isBlank()) {
            println("[LiveTest] Skipping testLiveZaiStatusIfKeyPresent: ZAI_API_KEY not configured.")
            return@runBlocking
        }

        println("[LiveTest] Probing Z.AI live endpoint with configured key...")
        val manager = ZaiManager()
        val status = manager.checkStatus(baseUrl = ZaiManager.DEFAULT_BASE_URL, apiKey = apiKey)
        println("[LiveTest] Z.AI status: connected=${status.connected}, models=${status.models}, error=${status.errorMessage}")
        assertTrue(status.connected, "Expected connection to Z.AI to succeed: ${status.errorMessage}")
    }
}
