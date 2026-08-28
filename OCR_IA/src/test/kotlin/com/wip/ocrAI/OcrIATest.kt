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
        }
    }

    @Test
    fun testActions() = kotlinx.coroutines.runBlocking {
        val plugin = OCR_IA(OcrIASettings())
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        plugin.detectLlamaServer(context)
        plugin.checkInstalledModels(context)
        plugin.stopLlamaServer(context)
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

        // Test 4: Standard Unlimited-OCR tagged format with layout_tag [x1, y1, x2, y2]
        val taggedOutput = "text [389, 318, 680, 369]IT'S MY\nMANA CORE.\nimage [0, 0, 999, 999]"
        val taggedRegions = runner.parseOcrOutput(taggedOutput, 940.0, 1918.0)
        assertEquals(1, taggedRegions.size)
        assertEquals("IT'S MY\nMANA CORE.", taggedRegions[0].text)
        // xmin = 389/1000 * 940 = 365.66
        // ymin = 318/1000 * 1918 = 609.924
        // xmax = 680/1000 * 940 = 639.2
        // ymax = 369/1000 * 1918 = 707.742
        assertEquals(609.924, taggedRegions[0].ymin, 0.01)
        assertEquals(365.66, taggedRegions[0].xmin, 0.01)
        assertEquals(707.742, taggedRegions[0].ymax, 0.01)
        assertEquals(639.2, taggedRegions[0].xmax, 0.01)
    }

    @Test
    fun testVisionCutoutHelperCropAndMerge() {
        val obj1 = com.wip.common.models.SegmentedObject(
            label = "balloon",
            confidence = 0.9,
            box = com.wip.common.models.DetectionBox(ymin = 0.10, xmin = 0.10, ymax = 0.15, xmax = 0.20)
        )
        val obj2 = com.wip.common.models.SegmentedObject(
            label = "text",
            confidence = 0.95,
            box = com.wip.common.models.DetectionBox(ymin = 0.12, xmin = 0.12, ymax = 0.18, xmax = 0.22)
        )
        val objDisjoint = com.wip.common.models.SegmentedObject(
            label = "balloon",
            confidence = 0.85,
            box = com.wip.common.models.DetectionBox(ymin = 0.70, xmin = 0.50, ymax = 0.80, xmax = 0.60)
        )

        val imageW = 1000
        val imageH = 10000

        // With padding = 100px:
        // obj1: ymin=1000-100=900, xmin=100-100=0, ymax=1500+100=1600, xmax=200+100=300
        // obj2: ymin=1200-100=1100, xmin=120-100=20, ymax=1800+100=1900, xmax=220+100=320
        // obj1 and obj2 overlap! Union: ymin=900, xmin=0, ymax=1900, xmax=320
        // objDisjoint: ymin=7000-100=6900, xmin=500-100=400, ymax=8000+100=8100, xmax=600+100=700
        val crops = VisionCutoutHelper.computeCropRegions(
            listOf(obj1, obj2, objDisjoint),
            imageWidth = imageW,
            imageHeight = imageH,
            paddingPx = 100
        )

        assertEquals(2, crops.size)
        // First merged crop
        assertEquals(0, crops[0].xmin)
        assertEquals(900, crops[0].ymin)
        assertEquals(320, crops[0].xmax)
        assertEquals(1900, crops[0].ymax)

        // Second disjoint crop
        assertEquals(400, crops[1].xmin)
        assertEquals(6900, crops[1].ymin)
        assertEquals(700, crops[1].xmax)
        assertEquals(8100, crops[1].ymax)

        // Test coordinate remapping
        val localBox = listOf(50.0, 20.0, 150.0, 120.0) // [ymin, xmin, ymax, xmax] relative to crop
        val globalBox = VisionCutoutHelper.remapBoxToGlobal(localBox, crops[0], imageW.toDouble(), imageH.toDouble())
        assertEquals(950.0, globalBox[0])  // 900 + 50
        assertEquals(20.0, globalBox[1])   // 0 + 20
        assertEquals(1050.0, globalBox[2]) // 900 + 150
        assertEquals(120.0, globalBox[3])  // 0 + 120
    }

    @Test
    fun testVisionCutoutHelperMatching() {
        val vResult1 = com.wip.common.models.VisionResult(
            objects = emptyList(),
            imageWidth = 800,
            imageHeight = 1200,
            pageName = "page_001.png"
        )
        val vResult2 = com.wip.common.models.VisionResult(
            objects = emptyList(),
            imageWidth = 800,
            imageHeight = 1200,
            pageName = "page_002"
        )
        val chapterVision = com.wip.common.models.ChapterVisionResult(
            results = listOf(vResult1, vResult2),
            totalObjectsDetected = 0
        )

        val file1 = java.io.File("C:/images/page_001.png")
        val file2 = java.io.File("C:/images/page_002.webp")
        val fileMissing = java.io.File("C:/images/page_003.png")

        val match1 = VisionCutoutHelper.findMatchingVisionResult(file1, chapterVision)
        assertNotNull(match1)
        assertEquals("page_001.png", match1.pageName)

        val match2 = VisionCutoutHelper.findMatchingVisionResult(file2, chapterVision)
        assertNotNull(match2)
        assertEquals("page_002", match2.pageName)

        val matchMissing = VisionCutoutHelper.findMatchingVisionResult(fileMissing, chapterVision)
        assertEquals(null, matchMissing)
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
            chapterVisionResult = null,
            cropPadding = 100,
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
            chapterVisionResult = null,
            cropPadding = 100,
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
                chapterVisionResult = null,
                cropPadding = 100,
                context = context,
                hostFs = hostFs
            )
            assertEquals(0, res.texts.size)
        }
    }

    @Test
    fun testMergeCapabilities() = kotlinx.coroutines.runBlocking {
        val plugin = OCR_IA(OcrIASettings())
        val context = io.mockk.mockk<PluginContext>(relaxed = true)

        val ocr = com.wip.common.models.OCRResult(
            texts = listOf("Line 1", "Line 2"),
            bb = listOf(
                listOf(0.1, 0.1, 0.2, 0.3),
                listOf(0.21, 0.1, 0.3, 0.3)
            ),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("p1.png", "p1.png"),
            failedFiles = emptyList()
        )

        val singleVision = com.wip.common.models.VisionResult(
            objects = listOf(
                com.wip.common.models.SegmentedObject(
                    label = "balloon",
                    confidence = 0.9,
                    box = com.wip.common.models.DetectionBox(
                        label = "balloon",
                        confidence = 0.9,
                        ymin = 0.05,
                        xmin = 0.05,
                        ymax = 0.35,
                        xmax = 0.35
                    ),
                    polygon = emptyList()
                )
            ),
            imageWidth = 1000,
            imageHeight = 1000,
            pageName = "p1.png"
        )

        val chapterVision = com.wip.common.models.ChapterVisionResult(
            results = listOf(singleVision),
            totalObjectsDetected = 1
        )

        val mergedChapter = plugin.mergeOcrWithVision(ocr, chapterVision, context)
        assertEquals(1, mergedChapter.texts.size)
        assertEquals("Line 1 Line 2", mergedChapter.texts[0])

        val mergedSingle = plugin.mergeSingleOcrWithVision(ocr, singleVision, context)
        assertEquals(1, mergedSingle.texts.size)
        assertEquals("Line 1 Line 2", mergedSingle.texts[0])

        val advOcr = com.wip.common.models.AdvancedOCRResult(
            texts = listOf("Adv Line 1", "Adv Line 2"),
            balloonBoxes = listOf(
                listOf(0.1, 0.1, 0.2, 0.3),
                listOf(0.21, 0.1, 0.3, 0.3)
            ),
            textBoxes = listOf(
                listOf(0.12, 0.12, 0.18, 0.28),
                listOf(0.22, 0.12, 0.28, 0.28)
            ),
            shapes = listOf("oval", "oval"),
            fontStyles = listOf("normal", "normal"),
            fontFamilies = listOf("AnimeAce2.0BB", "AnimeAce2.0BB"),
            textAngles = listOf(0.0, 0.0),
            isSparse = listOf(false, false),
            textColors = listOf("#000000", "#000000"),
            hasBorder = listOf(false, false),
            borderColors = listOf("#FFFFFF", "#FFFFFF"),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("p1.png", "p1.png"),
            failedFiles = emptyList()
        )

        val mergedAdvChapter = plugin.mergeAdvancedOcrWithVision(advOcr, chapterVision, context)
        assertEquals(1, mergedAdvChapter.texts.size)
        assertEquals("Adv Line 1 Adv Line 2", mergedAdvChapter.texts[0])

        val mergedAdvSingle = plugin.mergeSingleAdvancedOcrWithVision(advOcr, singleVision, context)
        assertEquals(1, mergedAdvSingle.texts.size)
        assertEquals("Adv Line 1 Adv Line 2", mergedAdvSingle.texts[0])
    }
}

