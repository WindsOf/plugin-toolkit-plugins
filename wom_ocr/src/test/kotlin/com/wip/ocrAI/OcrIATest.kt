package com.wip.ocrAI

import com.wip.common.inference.llama.LlamaBackend
import com.wip.common.inference.llama.LlamaServerMode
import com.wip.ocrAI.models.AIModel
import com.wip.ocrAI.models.AdvancedAIModel
import com.wip.ocrAI.models.OcrDownloadModel
import com.wip.ocrAI.models.OcrIASettings
import com.wip.ocrAI.models.OcrQuantization
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
        override fun verbose(message: String) {
            messages.add("VERBOSE: $message")
        }

        override fun debug(message: String) {
            messages.add("DEBUG: $message")
        }

        override fun info(message: String) {
            messages.add("INFO: $message")
        }

        override fun warn(message: String) {
            messages.add("WARN: $message")
        }

        override fun error(message: String, throwable: Throwable?) {
            messages.add("ERROR: $message")
        }
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
        assertEquals("Qwen3-VL-4B-Instruct-Q4_K_M", OcrDownloadModel.QWEN3_VL_4B_Q4_K_M.modelId)
        assertEquals("Qwen3-VL-4B-Instruct-Q8_0", OcrDownloadModel.QWEN3_VL_4B_Q8_0.modelId)
        assertEquals("Qwen3-VL-8B-Instruct-Q4_K_M", OcrDownloadModel.QWEN3_VL_8B_Q4_K_M.modelId)
        assertEquals("Qwen3-VL-8B-Instruct-Q8_0", OcrDownloadModel.QWEN3_VL_8B_Q8_0.modelId)
    }

    @Test
    fun testOcrQuantizationEnum() {
        assertEquals("Q4_K_M", OcrQuantization.Q4_K_M.id)
        assertEquals("Q8_0", OcrQuantization.Q8_0.id)
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
        assertEquals("Qwen3-VL-4B-Instruct", AIModel.QWEN3_VL_4B.id)
        assertEquals("Qwen3-VL-8B-Instruct", AIModel.QWEN3_VL_8B.id)
    }

    @Test
    fun testAdvancedAIModelEnumIdentifiers() {
        assertEquals("gemma-4-26b-a4b-it", AdvancedAIModel.GEMMA_26B.id)
        assertEquals("gemma-4-31b-it", AdvancedAIModel.GEMMA_31B.id)
        assertEquals("gemini-1.5-pro", AdvancedAIModel.GEMINI_1_5_PRO.id)
        assertEquals("gemini-2.5-pro", AdvancedAIModel.GEMINI_2_5_PRO.id)
        assertEquals("gemini-3.1-flash-lite", AdvancedAIModel.GEMINI_3_1_FLASH_LITE.id)
        assertEquals("claude-3-5-sonnet-20241022", AdvancedAIModel.CLAUDE_3_5_SONNET.id)
        assertEquals("gpt-4o", AdvancedAIModel.GPT_4O.id)
        assertEquals("lm-studio", AdvancedAIModel.LM_STUDIO.id)
        assertEquals("Qwen3-VL-4B-Instruct", AdvancedAIModel.QWEN3_VL_4B.id)
        assertEquals("Qwen3-VL-8B-Instruct", AdvancedAIModel.QWEN3_VL_8B.id)
        val names = AdvancedAIModel.entries.map { it.name }
        assertTrue(names.none { it.startsWith("UNLIMITED_OCR") })
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
        val plugin = OCR_IA(OcrIASettings(lmStudioUrl = "http://127.0.0.1:59999/v1"))
        val toasts = mutableListOf<String>()
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        io.mockk.every { context.showToast(any()) } answers {
            toasts.add(firstArg())
        }
        plugin.detectLlamaServer(context)
        plugin.checkInstalledModels(context)
        plugin.stopLlamaServer(context)
        plugin.testLmStudioConnection(context)
        assertTrue(toasts.size >= 4, "Expected at least 4 toast messages from actions")
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
        assertEquals(609.924, taggedRegions[0].ymin, 0.01)
        assertEquals(365.66, taggedRegions[0].xmin, 0.01)
        assertEquals(707.742, taggedRegions[0].ymax, 0.01)
        assertEquals(639.2, taggedRegions[0].xmax, 0.01)

        // Test 5: Hallucination explanation wall of text (from todebug/1.json)
        val hallucinationOutput =
            "text [0, 0, 999, 999]The image contains no text. The OCR result \"1\" is a hallucination and does not correspond to any content in the source image. Therefore, the correct OCR output must reflect the absence of any visible text.\n\n(no text)"
        val hallucinationRegions = runner.parseOcrOutput(hallucinationOutput, 940.0, 1918.0)
        assertEquals(0, hallucinationRegions.size, "Explanation hallucination must produce 0 regions")

        // Test 6: Direct (no text) and (nessun testo)
        val noTextOutput = "(no text)"
        val noTextRegions = runner.parseOcrOutput(noTextOutput, 1000.0, 1000.0)
        assertEquals(0, noTextRegions.size)

        val nessunTestoOutput = "(nessun testo)"
        val nessunTestoRegions = runner.parseOcrOutput(nessunTestoOutput, 1000.0, 1000.0)
        assertEquals(0, nessunTestoRegions.size)
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

        // Test coordinate remapping (normalized to [0.0, 1.0])
        val localBox = listOf(50.0, 20.0, 150.0, 120.0) // [ymin, xmin, ymax, xmax] relative to crop
        val globalBox = VisionCutoutHelper.remapBoxToGlobal(localBox, crops[0], imageW.toDouble(), imageH.toDouble())
        assertEquals(0.095, globalBox[0], 0.0001)  // (900 + 50) / 10000
        assertEquals(0.02, globalBox[1], 0.0001)   // (0 + 20) / 1000
        assertEquals(0.105, globalBox[2], 0.0001)  // (900 + 150) / 1000
        assertEquals(0.12, globalBox[3], 0.0001)   // (0 + 120) / 1000

        // Test normalizeBoxToGlobal
        val unnormalized = listOf(950.0, 20.0, 1050.0, 120.0)
        val normalized = VisionCutoutHelper.normalizeBoxToGlobal(unnormalized, imageW.toDouble(), imageH.toDouble())
        assertEquals(0.095, normalized[0], 0.0001)
        assertEquals(0.02, normalized[1], 0.0001)
        assertEquals(0.105, normalized[2], 0.0001)
        assertEquals(0.12, normalized[3], 0.0001)
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
            model = AdvancedAIModel.QWEN3_VL_4B,
            chapterVisionResult = null,
            cropPadding = 100,
            context = context,
            hostFs = hostFs
        )

        assertEquals(0, advancedResult.texts.size)
        assertEquals(0, advancedResult.balloonBoxes.size)

        for (m in listOf(
            AIModel.UNLIMITED_OCR_BF16,
            AIModel.UNLIMITED_OCR_Q8_0,
            AIModel.UNLIMITED_OCR_Q4_K_M,
            AIModel.UNLIMITED_OCR_IQ2_M,
            AIModel.QWEN3_VL_4B,
            AIModel.QWEN3_VL_8B
        )) {
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

        val mergedChapter = com.wip.common.models.OcrVisionMerger.mergeChapterOcrResult(ocr, chapterVision)
        assertEquals(1, mergedChapter.texts.size)
        assertEquals("Line 1 Line 2", mergedChapter.texts[0])

        val mergedSingle = com.wip.common.models.OcrVisionMerger.mergeOcrResult(ocr, singleVision)
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

        val mergedAdvChapter = com.wip.common.models.OcrVisionMerger.mergeChapterAdvancedOcrResult(advOcr, chapterVision)
        assertEquals(1, mergedAdvChapter.texts.size)
        assertEquals("Adv Line 1 Adv Line 2", mergedAdvChapter.texts[0])

        val mergedAdvSingle = com.wip.common.models.OcrVisionMerger.mergeAdvancedOcrResult(advOcr, singleVision)
        assertEquals(1, mergedAdvSingle.texts.size)
        assertEquals("Adv Line 1 Adv Line 2", mergedAdvSingle.texts[0])
    }

    @Test
    fun testMergeOcrWithVisionFiltersHallucinationsAndDegenerateLoops() = kotlinx.coroutines.runBlocking {
        val plugin = OCR_IA(OcrIASettings(googleApiKey = "key123"))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)

        val ocr = com.wip.common.models.OCRResult(
            texts = listOf(
                "[Non-Text]",
                "1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1",
                "Actual speech text"
            ),
            bb = listOf(
                listOf(0.0, 0.0, 0.2, 0.2),
                listOf(0.2, 0.2, 0.4, 0.4),
                listOf(0.1, 0.1, 0.3, 0.3)
            ),
            pageNumbers = listOf(1, 1, 1),
            pageNames = listOf("p1.png", "p1.png", "p1.png"),
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
                    )
                )
            ),
            imageWidth = 1000,
            imageHeight = 1000,
            pageName = "p1.png"
        )

        val merged = com.wip.common.models.OcrVisionMerger.mergeOcrResult(ocr, singleVision)
        assertEquals(1, merged.texts.size)
        assertEquals("Actual speech text", merged.texts[0])
    }

    @Test
    fun testParseQwenOcrOutput() {
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)
        val runner = UnlimitedOcrRunner(context, hostFs)

        val rawOutput = """
            {speech} [100, 200, 300, 400] Where are you going?!
            {sfx} [500, 600, 700, 800] *BOOM*
        """.trimIndent()

        val regions = runner.parseOcrOutput(rawOutput, 1000.0, 1000.0)
        assertEquals(2, regions.size)
        assertEquals("Where are you going?!", regions[0].text)
        assertEquals("speech", regions[0].category)
        assertEquals(200.0, regions[0].ymin, 0.01)
        assertEquals(100.0, regions[0].xmin, 0.01)
        assertEquals(400.0, regions[0].ymax, 0.01)
        assertEquals(300.0, regions[0].xmax, 0.01)
        assertEquals("oval", regions[0].shape)

        assertEquals("*BOOM*", regions[1].text)
        assertEquals("sfx", regions[1].category)
        assertEquals("rectangular", regions[1].shape)
        assertEquals("screaming", regions[1].fontFamily)
    }

    @Test
    fun testCategoryFallbackForNonSupportingModels() {
        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)
        val runner = UnlimitedOcrRunner(context, hostFs)

        // Unlimited-OCR layout format (no category support)
        val unlimitedOutput = """
            text [100, 200, 300, 400] Normal dialogue line
            balloon [500, 600, 700, 800] Another speech balloon
        """.trimIndent()
        val unlimitedRegions = runner.parseOcrOutput(unlimitedOutput, 1000.0, 1000.0)
        assertEquals(2, unlimitedRegions.size)
        assertEquals("speech", unlimitedRegions[0].category)
        assertEquals("speech", unlimitedRegions[1].category)

        // Coordinate prefix format (no category support)
        val coordOutput = "[100, 200, 300, 400] Plain text line"
        val coordRegions = runner.parseOcrOutput(coordOutput, 1000.0, 1000.0)
        assertEquals(1, coordRegions.size)
        assertEquals("speech", coordRegions[0].category)

        // Custom/unknown tag should strictly fall back to speech
        val unknownTagOutput = "{unknown_category} [100, 200, 300, 400] Fallback to speech text"
        val unknownRegions = runner.parseOcrOutput(unknownTagOutput, 1000.0, 1000.0)
        assertEquals(1, unknownRegions.size)
        assertEquals("speech", unknownRegions[0].category)
    }

    @Test
    fun testUpdateHookCallsOrganizeModelsDirectory() = kotlinx.coroutines.runBlocking {
        val plugin = OCR_IA(OcrIASettings())
        val context = io.mockk.mockk<PluginContext>(relaxed = true)

        val updateResult = plugin.update(context)
        assertTrue(updateResult.isSuccess)
    }

    @Test
    fun testQwenOcrPunctuationAndTaggedOutputs() {
        val runner = UnlimitedOcrRunner(
            io.mockk.mockk(relaxed = true),
            io.mockk.mockk(relaxed = true),
            OcrIASettings()
        )

        // 1. User sample: {sfx} [200, 400, 700, 800] ?!
        val sfxPunctuation = "{sfx} [200, 400, 700, 800] ?!"
        val sfxRegions = runner.parseOcrOutput(sfxPunctuation, 1000.0, 1000.0)
        assertEquals(1, sfxRegions.size)
        assertEquals("?!", sfxRegions[0].text)
        assertEquals("sfx", sfxRegions[0].category)
        assertEquals("rectangular", sfxRegions[0].shape)
        assertEquals("screaming", sfxRegions[0].fontFamily)
        assertEquals(400.0, sfxRegions[0].ymin)
        assertEquals(200.0, sfxRegions[0].xmin)
        assertEquals(800.0, sfxRegions[0].ymax)
        assertEquals(700.0, sfxRegions[0].xmax)

        // 2. User sample: {speech} [400, 300, 600, 600] ?!
        val speechPunctuation = "{speech} [400, 300, 600, 600] ?!"
        val speechRegions = runner.parseOcrOutput(speechPunctuation, 1000.0, 1000.0)
        assertEquals(1, speechRegions.size)
        assertEquals("?!", speechRegions[0].text)
        assertEquals("speech", speechRegions[0].category)
        assertEquals("oval", speechRegions[0].shape)
        assertEquals(300.0, speechRegions[0].ymin)
        assertEquals(400.0, speechRegions[0].xmin)
        assertEquals(600.0, speechRegions[0].ymax)
        assertEquals(600.0, speechRegions[0].xmax)

        // 3. Coordinate-first format: [200, 400, 700, 800] {sfx} ...
        val coordFirst = "[200, 400, 700, 800] {sfx} ..."
        val coordFirstRegions = runner.parseOcrOutput(coordFirst, 1000.0, 1000.0)
        assertEquals(1, coordFirstRegions.size)
        assertEquals("...", coordFirstRegions[0].text)
        assertEquals("sfx", coordFirstRegions[0].category)

        // 4. Bracketed/colon variant: [speech] [100, 200, 300, 400]: dialogue text
        val bracketedColon = "[speech] [100, 200, 300, 400]: dialogue text"
        val bracketedRegions = runner.parseOcrOutput(bracketedColon, 1000.0, 1000.0)
        assertEquals(1, bracketedRegions.size)
        assertEquals("dialogue text", bracketedRegions[0].text)
        assertEquals("speech", bracketedRegions[0].category)
    }
}

