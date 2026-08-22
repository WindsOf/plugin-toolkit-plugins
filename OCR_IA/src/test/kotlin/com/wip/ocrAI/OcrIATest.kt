package com.wip.ocrAI

import com.wip.ocrAI.models.AIModel
import com.wip.ocrAI.models.OcrIASettings
import org.junit.Test
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OcrIATest {

    private class FakeLogger : PluginLogger {
        val messages = mutableListOf<String>()
        override fun verbose(message: String) { messages.add("VERBOSE: $message") }
        override fun debug(message: String) { messages.add("DEBUG: $message") }
        override fun info(message: String) { messages.add("INFO: $message") }
        override fun warn(message: String) { messages.add("WARN: $message") }
        override fun error(message: String, throwable: Throwable?) { messages.add("ERROR: $message") }
    }

    @Test
    fun testOcrIASettingsDefaults() {
        val settings = OcrIASettings(googleApiKey = "test-api-key")
        assertEquals("test-api-key", settings.googleApiKey)
        assertEquals("http://localhost:1234/v1", settings.lmStudioUrl)
        assertEquals("lm-studio", settings.lmStudioApiKey)
    }

    @Test
    fun testAIModelEnumIdentifiers() {
        assertEquals("gemma-4-26b-a4b-it", AIModel.GEMMA_26B.id)
        assertEquals("gemma-4-31b-it", AIModel.GEMMA_31B.id)
        assertEquals("gemini-1.5-pro", AIModel.GEMINI_1_5_PRO.id)
        assertEquals("gemini-2.5-pro", AIModel.GEMINI_2_5_PRO.id)
        assertEquals("claude-3-5-sonnet-20241022", AIModel.CLAUDE_3_5_SONNET.id)
        assertEquals("gpt-4o", AIModel.GPT_4O.id)
        assertEquals("lm-studio", AIModel.LM_STUDIO.id)
    }

    @Test
    fun testLifecycleHooks() {
        val plugin = OCR_IA(OcrIASettings(googleApiKey = "key123"))
        val logger = FakeLogger()

        val loadResult = plugin.onLoad(logger)
        assertTrue(loadResult.isSuccess)

        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        kotlinx.coroutines.runBlocking {
            val setupResult = plugin.setup(context)
            assertTrue(setupResult.isSuccess)

            val updateResult = plugin.update(context)
            assertTrue(updateResult.isSuccess)

            val validateResult = plugin.validate(context)
            assertTrue(validateResult.isSuccess)
        }
    }
}
