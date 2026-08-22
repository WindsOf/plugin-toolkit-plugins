package com.wip.manhwaTranslatorAI

import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslatorAITest {

    private class FakeLogger : PluginLogger {
        val messages = mutableListOf<String>()
        override fun verbose(message: String) { messages.add("VERBOSE: $message") }
        override fun debug(message: String) { messages.add("DEBUG: $message") }
        override fun info(message: String) { messages.add("INFO: $message") }
        override fun warn(message: String) { messages.add("WARN: $message") }
        override fun error(message: String, throwable: Throwable?) { messages.add("ERROR: $message") }
    }

    @Test
    fun testTranslatorAISettingsDefaults() {
        val settings = TranslatorAISettings(googleApiKey = "test-key-456")
        assertEquals("test-key-456", settings.googleApiKey)
        assertEquals(true, settings.useStructuredOutput)
        assertEquals("http://localhost:1234/v1", settings.lmStudioUrl)
        assertEquals("lm-studio", settings.lmStudioApiKey)
    }

    @Test
    fun testAIModelIdentifiers() {
        assertEquals("gemma-4-26b-a4b-it", AIModel.GEMMA_26B.id)
        assertEquals("gemma-4-31b-it", AIModel.GEMMA_31B.id)
        assertEquals("gemini-3.5-flash", AIModel.GEMINI_3_5_FLASH.id)
        assertEquals("gemini-3.1-flash-lite", AIModel.GEMINI_3_1_FLASH_LITE.id)
        assertEquals("lm-studio", AIModel.LM_STUDIO.id)
    }

    @Test
    fun testLifecycleHooks() {
        val plugin = TranslatorAI(TranslatorAISettings(googleApiKey = "key123"))
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

    @Test
    fun testOCRResultCopyPreservation() {
        val initialOcr = OCRResult(
            texts = listOf("Korean text 1", "Korean text 2"),
            bb = listOf(listOf(0.1, 0.1, 0.3, 0.3), listOf(0.4, 0.4, 0.6, 0.6)),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("01.png", "01.png"),
            failedFiles = emptyList()
        )

        val translatedTexts = listOf("Testo italiano 1", "Testo italiano 2")
        val translatedOcr = initialOcr.copy(texts = translatedTexts)

        assertEquals(translatedTexts, translatedOcr.texts)
        assertEquals(initialOcr.bb, translatedOcr.bb)
        assertEquals(initialOcr.pageNames, translatedOcr.pageNames)
    }

    @Test
    fun testAdvancedOCRResultCopyPreservation() {
        val initialOcr = AdvancedOCRResult(
            texts = listOf("Korean text"),
            balloonBoxes = listOf(listOf(0.1, 0.1, 0.5, 0.5)),
            textBoxes = listOf(listOf(0.15, 0.15, 0.45, 0.45)),
            shapes = listOf("oval"),
            fontStyles = listOf("bold"),
            fontFamilies = listOf("sans-serif"),
            textAngles = listOf(0.0),
            isSparse = listOf(false),
            textColors = listOf("#000000"),
            hasBorder = listOf(true),
            borderColors = listOf("#FFFFFF"),
            pageNumbers = listOf(1),
            pageNames = listOf("01.png"),
            failedFiles = emptyList()
        )

        val translatedTexts = listOf("Testo tradotto")
        val translatedOcr = initialOcr.copy(texts = translatedTexts)

        assertEquals(translatedTexts, translatedOcr.texts)
        assertEquals(initialOcr.balloonBoxes, translatedOcr.balloonBoxes)
        assertEquals(initialOcr.shapes, translatedOcr.shapes)
        assertEquals(initialOcr.borderColors, translatedOcr.borderColors)
    }
}
