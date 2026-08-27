package com.wip.ocrAI

import com.wip.common.inference.llama.LlamaBackend
import com.wip.common.inference.llama.LlamaServerMode
import com.wip.ocrAI.models.AIModel
import com.wip.ocrAI.models.OcrDownloadModel
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
        assertEquals(LlamaServerMode.AUTO, settings.llamaServerMode)
        assertEquals(LlamaBackend.AUTO, settings.llamaServerBackend)
        assertEquals(99, settings.llamaServerGpuLayers)
        assertEquals(8080, settings.llamaServerPort)
    }

    @Test
    fun testOcrDownloadModelEnumIdentifiers() {
        assertEquals("Unlimited-OCR-BF16", OcrDownloadModel.UNLIMITED_OCR_BF16.modelId)
        assertEquals("Unlimited-OCR-Q8_0", OcrDownloadModel.UNLIMITED_OCR_Q8_0.modelId)
        assertEquals("Unlimited-OCR-Q4_K_M", OcrDownloadModel.UNLIMITED_OCR_Q4_K_M.modelId)
        assertEquals("Unlimited-OCR-IQ2_M", OcrDownloadModel.UNLIMITED_OCR_IQ2_M.modelId)
    }

    @Test
    fun testAIModelEnumIdentifiers() {
        assertEquals("gemma-4-26b-a4b-it", AIModel.GEMMA_26B.id)
        assertEquals("gemma-4-31b-it", AIModel.GEMMA_31B.id)
        assertEquals("gemini-1.5-pro", AIModel.GEMINI_1_5_PRO.id)
        assertEquals("gemini-2.5-pro", AIModel.GEMINI_2_5_PRO.id)
        assertEquals("gemini-3.1-flash-lite", AIModel.GEMINI_3_1_FLASH_LITE.id)
        assertEquals("claude-3-5-sonnet-20241022", AIModel.CLAUDE_3_5_SONNET.id)
        assertEquals("gpt-4o", AIModel.GPT_4O.id)
        assertEquals("lm-studio", AIModel.LM_STUDIO.id)
        assertEquals("Unlimited-OCR-BF16", AIModel.UNLIMITED_OCR_BF16.id)
        assertEquals("Unlimited-OCR-Q8_0", AIModel.UNLIMITED_OCR_Q8_0.id)
        assertEquals("Unlimited-OCR-Q4_K_M", AIModel.UNLIMITED_OCR_Q4_K_M.id)
        assertEquals("Unlimited-OCR-IQ2_M", AIModel.UNLIMITED_OCR_IQ2_M.id)
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

            val locks = plugin.checkLocks(context)
            assertTrue(locks.containsKey("model:Unlimited-OCR-Q4_K_M"))
            assertTrue(locks.containsKey("model:unlimited-ocr-q4_k_m"))
            assertTrue(locks.containsKey("Unlimited-OCR-Q4_K_M"))
        }
    }

    @Test
    fun testActions() = kotlinx.coroutines.runBlocking {
        val plugin = OCR_IA(OcrIASettings())
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        plugin.detectLlamaServer(context)
        plugin.checkInstalledModels(context)
    }
    
    @Test
    fun testUnlimitedOcrRunnerParsing() {
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)
        val runner = UnlimitedOcrRunner(context, hostFs)

        // Test 1: JSON output
        val jsonOutput = """
            ```json
            {
                "balloons": [
                    {"text": "Hello world", "ymin": 0.1, "xmin": 0.2, "ymax": 0.3, "xmax": 0.4}
                ]
            }
            ```
        """.trimIndent()
        val jsonRegions = runner.parseOcrOutput(jsonOutput, 1000.0, 1000.0)
        assertEquals(1, jsonRegions.size)
        assertEquals("Hello world", jsonRegions[0].text)
        assertEquals(100.0, jsonRegions[0].ymin)
        assertEquals(200.0, jsonRegions[0].xmin)
        assertEquals(300.0, jsonRegions[0].ymax)
        assertEquals(400.0, jsonRegions[0].xmax)

        // Test 2: DeepSeek / Baidu <|ref|>...<|box|>... tags with 1000-scale
        val refBoxOutput = "<|ref|>Speech balloon text<|/ref|><|box|>[150, 250, 450, 650]<|/box|>"
        val refRegions = runner.parseOcrOutput(refBoxOutput, 800.0, 1200.0)
        assertEquals(1, refRegions.size)
        assertEquals("Speech balloon text", refRegions[0].text)
        assertEquals(180.0, refRegions[0].ymin) // 150/1000 * 1200 = 180
        assertEquals(200.0, refRegions[0].xmin) // 250/1000 * 800 = 200
        assertEquals(540.0, refRegions[0].ymax) // 450/1000 * 1200 = 540
        assertEquals(520.0, refRegions[0].xmax) // 650/1000 * 800 = 520

        // Test 3: <|det|>... tags
        val detOutput = "<|det|>text [100, 200, 300, 400]<|/det|>Sample detected text"
        val detRegions = runner.parseOcrOutput(detOutput, 1000.0, 1000.0)
        assertEquals(1, detRegions.size)
        assertEquals("Sample detected text", detRegions[0].text)
        assertEquals(100.0, detRegions[0].ymin)
        assertEquals(200.0, detRegions[0].xmin)
        assertEquals(300.0, detRegions[0].ymax)
        assertEquals(400.0, detRegions[0].xmax)
    }

    @Test
    fun testOcrIAWithGgufModelReturnsEmptyForNonExistentFiles() = kotlinx.coroutines.runBlocking {
        val plugin = OCR_IA(OcrIASettings())
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        val ocrResult = plugin.ocr(
            input = "non_existent_folder",
            save = false,
            outputDir = "",
            useStructuredOutput = false,
            saveThinking = false,
            model = AIModel.UNLIMITED_OCR_Q4_K_M,
            context = context,
            hostFs = hostFs
        )

        assertEquals(0, ocrResult.texts.size)
        assertEquals(0, ocrResult.bb.size)

        val advancedResult = plugin.advancedOcr(
            input = "non_existent_folder",
            save = false,
            outputDir = "",
            useStructuredOutput = false,
            saveThinking = false,
            model = AIModel.UNLIMITED_OCR_Q4_K_M,
            context = context,
            hostFs = hostFs
        )

        assertEquals(0, advancedResult.texts.size)
        assertEquals(0, advancedResult.balloonBoxes.size)

        for (m in listOf(AIModel.UNLIMITED_OCR_BF16, AIModel.UNLIMITED_OCR_Q8_0, AIModel.UNLIMITED_OCR_Q4_K_M, AIModel.UNLIMITED_OCR_IQ2_M)) {
            val res = plugin.ocr(
                input = "non_existent_folder",
                save = false,
                outputDir = "",
                useStructuredOutput = false,
                saveThinking = false,
                model = m,
                context = context,
                hostFs = hostFs
            )
            assertEquals(0, res.texts.size)
        }
    }
}

