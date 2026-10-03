package com.wip.vision

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisionPluginTest {

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

    private class FakeProgress : ProgressReporter {
        var lastProgress: Float = 0f
        override fun report(progress: Float) {
            lastProgress = progress
        }
    }

    @Test
    fun testLifecycleHooks() {
        val vision = VisionPlugin()
        val logger = FakeLogger()
        val loadResult = vision.onLoad(logger)
        assertTrue(loadResult.isSuccess)

        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns true
        }
        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.fileSystem } returns pluginFs
        }
        runBlocking {
            assertTrue(vision.setup(context).isSuccess)
            assertTrue(vision.validate(context).isSuccess)
            assertTrue(vision.update(context).isSuccess)
            val locks = vision.checkLocks(context)
            assertTrue(locks.containsKey("model:yolo-det-x-best-v3"))
            assertTrue(locks.containsKey("model:rfdetr-seg-2xlarge-ema-v3"))
            assertTrue(locks.containsKey("model:qwen"))
            assertTrue(locks.containsKey("qwen"))
            assertTrue(locks.containsKey("model:qwen3-vl"))
            assertEquals(true, locks["model:qwen"])
        }

        val missingFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns false
            coEvery { readTextFile(any()) } returns null
        }
        val missingContext = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.fileSystem } returns missingFs
        }
        runBlocking {
            kotlin.test.assertFalse(vision.validate(missingContext).isSuccess)
            val locks = vision.checkLocks(missingContext)
            assertEquals(false, locks["model:qwen"])
            assertEquals(false, locks["model:yolo-det-x-best-v3"])
        }
    }

    @Test
    fun testDetectAndSegmentExecution() {
        val vision = VisionPlugin()
        val tempDir = File("build/tmp/test_vision").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        // Generate synthetic image
        val testImage = File(tempDir, "test_page.png")
        val img = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 800, 1200)
        g.color = Color.BLACK
        g.fillRect(100, 200, 200, 80) // Mock text box
        g.dispose()
        ImageIO.write(img, "png", testImage)

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { readTextFile(any()) } returns null
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = vision.detectAndSegment(
                imagePath = testImage.absolutePath,
                detectionScoreThreshold = 0.25,
                segmentationScoreThreshold = 0.25,
                iouThreshold = 0.45,
                saveMask = true,
                outputDir = tempDir.absolutePath,
                context = context,
                hostFs = hostFs
            )

            assertEquals(800, result.imageWidth)
            assertEquals(1200, result.imageHeight)
            assertEquals("test_page.png", result.pageName)
            assertTrue(result.maskPath != null && File(result.maskPath!!).exists())
        }
    }

    @Test
    fun testDetectAndSegmentWithMultiScaleAndDebugImage() {
        val vision = VisionPlugin()
        val tempDir = File("build/tmp/test_vision_debug").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val testImage = File(tempDir, "sample_manhwa_page.png")
        val img = BufferedImage(1000, 1500, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 1000, 1500)
        g.color = Color.DARK_GRAY
        g.fillOval(150, 200, 500, 400) // Large speech bubble
        g.color = Color.BLACK
        g.fillRect(200, 300, 400, 150) // Speech text
        g.dispose()
        ImageIO.write(img, "png", testImage)

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { readTextFile(any()) } returns null
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = vision.detectAndSegment(
                imagePath = testImage.absolutePath,
                detectionScoreThreshold = 0.25,
                segmentationScoreThreshold = 0.25,
                iouThreshold = 0.45,
                detectScale = 2.0,
                detectOverlap = 0.35,
                segmentScale = 2.0,
                segmentOverlap = 0.35,
                saveMask = true,
                saveDebugImage = true,
                outputDir = tempDir.absolutePath,
                context = context,
                hostFs = hostFs
            )

            assertEquals(1000, result.imageWidth)
            assertEquals(1500, result.imageHeight)
            assertEquals("sample_manhwa_page.png", result.pageName)
            assertTrue(result.maskPath != null && File(result.maskPath!!).exists())
            assertTrue(result.debugImagePath != null && File(result.debugImagePath!!).exists())
            assertTrue(File(result.debugImagePath!!).length() > 0)
        }
    }

    @Test
    fun testDetectAndSegmentWithTileGridAndSegmentationRois() {
        val vision = VisionPlugin()
        val tempDir = File("build/tmp/test_vision_rois").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val testImage = File(tempDir, "manhwa_rois_page.png")
        val img = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 800, 1200)
        g.dispose()
        ImageIO.write(img, "png", testImage)

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { readTextFile(any()) } returns null
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = vision.detectAndSegment(
                imagePath = testImage.absolutePath,
                detectionScoreThreshold = 0.25,
                segmentationScoreThreshold = 0.25,
                iouThreshold = 0.45,
                iosThreshold = 0.65,
                detectScale = 1.0,
                detectOverlap = 0.25,
                segmentScale = 1.0,
                segmentOverlap = 0.25,
                saveMask = false,
                saveDebugImage = true,
                drawTileGrid = true,
                drawSegmentationRois = true,
                outputDir = tempDir.absolutePath,
                context = context,
                hostFs = hostFs
            )

            assertTrue(result.debugImagePath != null && File(result.debugImagePath!!).exists())
            assertTrue(File(result.debugImagePath!!).length() > 0)
        }
    }

    @Test
    fun testVisionSettingsAndShiftedTilingExecution() {
        val customSettings = VisionSettings(
            enableShiftedTiling = true,
            detectionTilingPasses = 2,
            enableDynamicRoiExpansion = true,
            maxRoiExpansionRetries = 1,
            roiExpansionRatio = 0.25,
            borderTouchThresholdPx = 3
        )
        val vision = VisionPlugin(settings = customSettings)
        assertEquals(2, vision.settings.detectionTilingPasses)
        assertTrue(vision.settings.enableDynamicRoiExpansion)

        val tempDir = File("build/tmp/test_vision_settings").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val testImage = File(tempDir, "manhwa_settings_page.png")
        val img = BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 800, 1200)
        g.dispose()
        ImageIO.write(img, "png", testImage)

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { readTextFile(any()) } returns null
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = vision.detectAndSegment(
                imagePath = testImage.absolutePath,
                detectionScoreThreshold = 0.25,
                segmentationScoreThreshold = 0.25,
                iouThreshold = 0.45,
                iosThreshold = 0.65,
                detectScale = 1.0,
                detectOverlap = 0.25,
                saveDebugImage = false,
                outputDir = tempDir.absolutePath,
                context = context,
                hostFs = hostFs
            )

            assertEquals(800, result.imageWidth)
            assertEquals(1200, result.imageHeight)

            kotlin.test.assertFailsWith<IllegalStateException> {
                vision.detect(
                    imagePath = testImage.absolutePath,
                    scoreThreshold = 0.25,
                    iouThreshold = 0.45,
                    iosThreshold = 0.65,
                    detectScale = 1.0,
                    detectOverlap = 0.25,
                    saveDebugImage = false,
                    outputDir = tempDir.absolutePath,
                    context = context,
                    hostFs = hostFs
                )
            }
        }
    }

    @Test
    fun testDownloadModelActions() {
        val vision = VisionPlugin()
        val logger = FakeLogger()
        val toastMessages = mutableListOf<String>()
        val dummyYaml = """
            name: TestModel
            type: OBJECT_DETECTION
            version: 1
            files:
              weights: test_model.onnx
            inputs: []
            outputs: []
            executionProviders: []
        """.trimIndent()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns true
            coEvery { readTextFile(any()) } returns dummyYaml
        }
        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.fileSystem } returns pluginFs
            every { showToast(any()) } answers {
                toastMessages.add(firstArg())
            }
        }

        runBlocking {
            vision.downloadModel(VisionDownloadModel.YOLO_DET_X, context)
            assertTrue(toastMessages.any { it.contains("Downloaded model: YOLO_DET_X") })

            vision.downloadModel(VisionDownloadModel.QWEN3_VL_4B_Q4_K_M, context)
            assertTrue(toastMessages.any { it.contains("Downloaded model: QWEN3_VL_4B_Q4_K_M") })

            vision.downloadAllModels(context)
            assertTrue(toastMessages.any { it.contains("All vision models downloaded successfully") })
        }

        // Test model filtering & entries
        val coreModels = VisionModel.entries.filter { it.isCore }
        assertEquals(listOf(VisionModel.YOLO_DET_X, VisionModel.RFDETR_SEG_2XLARGE), coreModels)

        assertEquals(VisionDownloadModel.QWEN3_VL_4B_Q4_K_M, VisionDownloadModel.fromModelId("Qwen3-VL-4B-Instruct-Q4_K_M"))
        assertEquals(VisionDownloadModel.QWEN3_VL_4B_Q8_0, VisionDownloadModel.fromModelId("Qwen3-VL-4B-Instruct-Q8_0"))
        assertEquals(VisionDownloadModel.QWEN3_VL_8B_Q4_K_M, VisionDownloadModel.fromModelId("Qwen3-VL-8B-Instruct-Q4_K_M"))
        assertEquals(VisionDownloadModel.QWEN3_VL_8B_Q8_0, VisionDownloadModel.fromModelId("Qwen3-VL-8B-Instruct-Q8_0"))
        assertEquals(VisionModel.QWEN3_VL_4B_Q4_K_M, VisionModel.fromModelId("Qwen3-VL-4B-Instruct-Q4_K_M"))
    }

    @Test
    fun testVisionReclassificationModeLockAnnotation() {
        val manifestStream = javaClass.classLoader.getResourceAsStream("META-INF/manifest.json")
            ?: File("build/generated/ksp/main/resources/META-INF/manifest.json").inputStream()
        val manifest = manifestStream.bufferedReader().use { it.readText() }
        assertTrue(manifest.contains("\"com.wip.vision.VisionReclassificationMode\""), "Manifest must describe VisionReclassificationMode")
        assertTrue(manifest.contains("\"model:qwen\""), "Manifest must require lock model:qwen for QWEN option")
        assertTrue(manifest.contains("\"optionLockRequirements\""), "Manifest must specify optionLockRequirements")
    }

    @Test
    fun testVisionTextReclassifierParsing() {
        val speechJson = """{"category": "speech"}"""
        assertEquals("speech", VisionTextReclassifier.parseCategoryFromResponse(speechJson))

        val sfxJson = """```json
{"category": "sfx"}
```"""
        assertEquals("sfx", VisionTextReclassifier.parseCategoryFromResponse(sfxJson))

        val nonTextJson = """{"category": "non_text"}"""
        assertEquals("non_text", VisionTextReclassifier.parseCategoryFromResponse(nonTextJson))

        val rawText = "The classification is sfx."
        assertEquals("sfx", VisionTextReclassifier.parseCategoryFromResponse(rawText))

        val invalid = "random output without category"
        assertEquals(null, VisionTextReclassifier.parseCategoryFromResponse(invalid))
    }

    @Test
    fun testVisionTextReclassifierNoneMode() = runBlocking {
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val objects = listOf(
            com.wip.common.models.SegmentedObject(
                label = "text",
                confidence = 0.9,
                box = com.wip.common.models.DetectionBox(ymin = 0.1, xmin = 0.1, ymax = 0.5, xmax = 0.5)
            )
        )
        val context = mockk<PluginContext>(relaxed = true)
        val result = VisionTextReclassifier.reclassifyTextElements(img, objects, VisionReclassificationMode.NONE, context)
        assertEquals(objects, result)
    }
}
