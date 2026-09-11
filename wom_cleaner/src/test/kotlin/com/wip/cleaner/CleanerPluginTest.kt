package com.wip.cleaner

import com.wip.common.models.BlendingMode
import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.DetectionBox
import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
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

class CleanerPluginTest {

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
        val cleaner = CleanerPlugin()
        val logger = FakeLogger()
        val loadResult = cleaner.onLoad(logger)
        assertTrue(loadResult.isSuccess)

        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns true
        }
        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.fileSystem } returns pluginFs
        }
        runBlocking {
            assertTrue(cleaner.setup(context).isSuccess)
            assertTrue(cleaner.validate(context).isSuccess)
            assertTrue(cleaner.update(context).isSuccess)
            val locks = cleaner.checkLocks(context)
            assertTrue(locks.containsKey("model:big-lama"))
            assertTrue(locks.containsKey("model:lama"))
            assertTrue(locks.containsKey("model:anime-manga-big-lama"))
            assertTrue(locks.containsKey("model:manga"))
            assertTrue(locks.containsKey("model:migan_traced"))
            assertTrue(locks.containsKey("model:migan"))
            assertTrue(locks.containsKey("model:zits"))
            assertTrue(locks.containsKey("model:zits-inpaint-0717"))
            assertTrue(locks.containsKey("model:zitspp"))
            assertTrue(locks.containsKey("model:zits++"))
        }

        val missingFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns false
            coEvery { readTextFile(any()) } returns null
        }
        val missingContext = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.fileSystem } returns missingFs
        }
        runBlocking {
            kotlin.test.assertFalse(cleaner.validate(missingContext).isSuccess)
        }
    }

    @Test
    fun testCleanImageOnlyTargetsSpecifiedClass() {
        val cleaner = CleanerPlugin()
        val tempDir = File("build/tmp/test_cleaner").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val outDir = File(tempDir, "cleaned").apply { mkdirs() }

        // Create 200x200 image with a white background, a red text block, and a blue drawing box
        val testImage = File(tempDir, "page_001.png")
        val img = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 200, 200)

        g.color = Color.RED
        g.fillRect(40, 40, 40, 40) // Target "text"

        g.color = Color.BLUE
        g.fillRect(120, 120, 40, 40) // Untouched "character"
        g.dispose()
        ImageIO.write(img, "png", testImage)

        // VisionResult with "text" object and "character" object
        val textObj = SegmentedObject(
            label = "text",
            confidence = 0.98,
            box = DetectionBox("text", 0.98, 0.2, 0.2, 0.4, 0.4),
            polygon = listOf(
                PolygonPoint(0.2, 0.2),
                PolygonPoint(0.4, 0.2),
                PolygonPoint(0.4, 0.4),
                PolygonPoint(0.2, 0.4)
            )
        )
        val charObj = SegmentedObject(
            label = "character",
            confidence = 0.95,
            box = DetectionBox("character", 0.95, 0.6, 0.6, 0.8, 0.8),
            polygon = listOf(
                PolygonPoint(0.6, 0.6),
                PolygonPoint(0.8, 0.6),
                PolygonPoint(0.8, 0.8),
                PolygonPoint(0.6, 0.8)
            )
        )

        val vResult = VisionResult(
            objects = listOf(textObj, charObj),
            imageWidth = 200,
            imageHeight = 200,
            pageName = "page_001.png"
        )

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = cleaner.cleanImage(
                imagePath = testImage.absolutePath,
                segmentationData = vResult,
                outputDir = outDir.absolutePath,
                targetClasses = listOf("text"),
                dilationRadius = 2,
                saveMask = true,
                context = context,
                hostFs = hostFs
            )

            assertEquals(1, result.cleanedObjectsCount)
            assertTrue(File(result.cleanedImagePath).exists())
            assertTrue(result.maskPath != null && File(result.maskPath!!).exists())

            // Verify in output image: text area (60, 60) was erased/inpainted white, blue box (140, 140) stayed blue
            val cleanedImg = ImageIO.read(File(result.cleanedImagePath))
            val textPixel = cleanedImg.getRGB(60, 60)
            val charPixel = cleanedImg.getRGB(140, 140)

            val textR = (textPixel shr 16) and 0xFF
            val charB = charPixel and 0xFF

            assertTrue(textR > 200, "Text area should be inpainted white background")
            assertTrue(charB > 200, "Character blue box should be preserved untouched")
        }
    }

    @Test
    fun testCleanChapterExecution() {
        val cleaner = CleanerPlugin()
        val tempDir = File("build/tmp/test_clean_chapter").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val inFolder = File(tempDir, "input").apply { mkdirs() }
        val outFolder = File(tempDir, "output").apply { mkdirs() }

        // Create 2 test pages
        for (i in 1..2) {
            val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 100, 100)
            g.color = Color.RED
            g.fillRect(20, 20, 20, 20)
            g.dispose()
            ImageIO.write(img, "png", File(inFolder, "page_$i.png"))
        }

        val v1 = VisionResult(
            objects = listOf(
                SegmentedObject(
                    label = "text",
                    confidence = 0.95,
                    box = DetectionBox("text", 0.95, 0.2, 0.2, 0.4, 0.4),
                    polygon = listOf(
                        PolygonPoint(0.2, 0.2),
                        PolygonPoint(0.4, 0.2),
                        PolygonPoint(0.4, 0.4),
                        PolygonPoint(0.2, 0.4)
                    )
                )
            ),
            imageWidth = 100,
            imageHeight = 100,
            pageName = "page_1.png"
        )
        val v2 = VisionResult(
            objects = listOf(
                SegmentedObject(
                    label = "text",
                    confidence = 0.95,
                    box = DetectionBox("text", 0.95, 0.2, 0.2, 0.4, 0.4),
                    polygon = listOf(
                        PolygonPoint(0.2, 0.2),
                        PolygonPoint(0.4, 0.2),
                        PolygonPoint(0.4, 0.4),
                        PolygonPoint(0.2, 0.4)
                    )
                )
            ),
            imageWidth = 100,
            imageHeight = 100,
            pageName = "page_2.png"
        )
        val chapterVision = ChapterVisionResult(results = listOf(v1, v2), totalObjectsDetected = 2)

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = cleaner.cleanChapter(
                inputFolder = inFolder.absolutePath,
                chapterVisionResult = chapterVision,
                outputDir = outFolder.absolutePath,
                targetClasses = listOf("text"),
                dilationRadius = 2,
                saveMasks = true,
                context = context,
                hostFs = hostFs
            )

            assertEquals(2, result.totalCleanedPages)
            assertEquals(2, result.cleanedImagePaths.size)
            assertTrue(File(result.cleanedImagePaths[0]).exists())
            assertTrue(File(result.cleanedImagePaths[1]).exists())
        }
    }

    @Test
    fun testCleanImagePatchesOnly() {
        val cleaner = CleanerPlugin()
        val tempDir = File("build/tmp/test_clean_patches").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val outDir = File(tempDir, "patches").apply { mkdirs() }

        val testImage = File(tempDir, "page_001.png")
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 100, 100)
        g.color = Color.RED
        g.fillRect(20, 20, 30, 30) // Text region
        g.dispose()
        ImageIO.write(img, "png", testImage)

        val textObj = SegmentedObject(
            label = "text",
            confidence = 0.98,
            box = DetectionBox("text", 0.98, 0.2, 0.2, 0.5, 0.5),
            polygon = listOf(
                PolygonPoint(0.2, 0.2),
                PolygonPoint(0.5, 0.2),
                PolygonPoint(0.5, 0.5),
                PolygonPoint(0.2, 0.5)
            )
        )

        val vResult = VisionResult(
            objects = listOf(textObj),
            imageWidth = 100,
            imageHeight = 100,
            pageName = "page_001.png"
        )

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = cleaner.cleanImagePatchesOnly(
                imagePath = testImage.absolutePath,
                segmentationData = vResult,
                outputDir = outDir.absolutePath,
                targetClasses = listOf("text"),
                dilationRadius = 2,
                context = context,
                hostFs = hostFs
            )

            assertTrue(File(result.cleanedImagePath).exists())
            val patchImg = ImageIO.read(File(result.cleanedImagePath))
            assertEquals(100, patchImg.width)
            assertEquals(100, patchImg.height)

            // Outside the text region (e.g. at 5, 5), pixel must be fully transparent (alpha == 0)
            val outsidePixel = patchImg.getRGB(5, 5)
            val outsideAlpha = (outsidePixel ushr 24) and 0xFF
            assertEquals(0, outsideAlpha, "Outside pixel should be fully transparent")

            // Inside the text region (e.g. at 30, 30), pixel must be non-transparent (alpha > 0)
            val insidePixel = patchImg.getRGB(30, 30)
            val insideAlpha = (insidePixel ushr 24) and 0xFF
            assertTrue(insideAlpha > 0, "Inpainted patch pixel should be visible (alpha > 0)")
        }
    }

    @Test
    fun testCleanImageHybridDeterministic() {
        val cleaner = CleanerPlugin()
        val tempDir = File("build/tmp/test_clean_hybrid").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val outDir = File(tempDir, "cleaned").apply { mkdirs() }

        val testImage = File(tempDir, "balloon_page.png")
        val img = BufferedImage(150, 150, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 150, 150)
        g.color = Color.BLACK
        g.fillRect(30, 30, 40, 20) // Black text inside white balloon
        g.dispose()
        ImageIO.write(img, "png", testImage)

        val textObj = SegmentedObject(
            label = "text",
            confidence = 0.99,
            box = DetectionBox("text", 0.99, 0.2, 0.2, 0.5, 0.4),
            polygon = listOf(
                PolygonPoint(0.2, 0.2),
                PolygonPoint(0.5, 0.2),
                PolygonPoint(0.5, 0.4),
                PolygonPoint(0.2, 0.4)
            )
        )
        val vResult = VisionResult(
            objects = listOf(textObj),
            imageWidth = 150,
            imageHeight = 150,
            pageName = "balloon_page.png"
        )

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = cleaner.cleanImageHybrid(
                imagePath = testImage.absolutePath,
                segmentationData = vResult,
                outputDir = outDir.absolutePath,
                model = InpaintingModel.MANGA,
                strategy = CleaningStrategy.AUTO_HYBRID,
                targetClasses = listOf("text"),
                dilationRadius = 2,
                adaptivePadding = true,
                context = context,
                hostFs = hostFs
            )

            assertTrue(File(result.cleanedImagePath).exists())
            val cleanedImg = ImageIO.read(File(result.cleanedImagePath))

            // Center of text area (45, 40) should be filled pure white (RGB 255, 255, 255)
            val centerPixel = cleanedImg.getRGB(45, 40)
            val r = (centerPixel shr 16) and 0xFF
            val gChan = (centerPixel shr 8) and 0xFF
            val b = centerPixel and 0xFF

            assertEquals(255, r, "Hybrid solid balloon fill should be pure white R")
            assertEquals(255, gChan, "Hybrid solid balloon fill should be pure white G")
            assertEquals(255, b, "Hybrid solid balloon fill should be pure white B")
        }
    }

    @Test
    fun testCleanChapterHybridExecution() {
        val cleaner = CleanerPlugin()
        val tempDir = File("build/tmp/test_clean_chapter_hybrid").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val inFolder = File(tempDir, "input").apply { mkdirs() }
        val outFolder = File(tempDir, "output").apply { mkdirs() }

        // Create 2 test pages with solid balloons
        for (i in 1..2) {
            val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 100, 100)
            g.color = Color.BLACK
            g.fillRect(20, 20, 20, 20)
            g.dispose()
            ImageIO.write(img, "png", File(inFolder, "page_$i.png"))
        }

        val v1 = VisionResult(
            objects = listOf(
                SegmentedObject(
                    label = "text",
                    confidence = 0.95,
                    box = DetectionBox("text", 0.95, 0.2, 0.2, 0.4, 0.4),
                    polygon = listOf(
                        PolygonPoint(0.2, 0.2),
                        PolygonPoint(0.4, 0.2),
                        PolygonPoint(0.4, 0.4),
                        PolygonPoint(0.2, 0.4)
                    )
                )
            ),
            imageWidth = 100,
            imageHeight = 100,
            pageName = "page_1.png"
        )
        val v2 = VisionResult(
            objects = listOf(
                SegmentedObject(
                    label = "text",
                    confidence = 0.95,
                    box = DetectionBox("text", 0.95, 0.2, 0.2, 0.4, 0.4),
                    polygon = listOf(
                        PolygonPoint(0.2, 0.2),
                        PolygonPoint(0.4, 0.2),
                        PolygonPoint(0.4, 0.4),
                        PolygonPoint(0.2, 0.4)
                    )
                )
            ),
            imageWidth = 100,
            imageHeight = 100,
            pageName = "page_2.png"
        )
        val chapterVision = ChapterVisionResult(results = listOf(v1, v2), totalObjectsDetected = 2)

        val logger = FakeLogger()
        val progress = FakeProgress()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)

        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.progress } returns progress
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = cleaner.cleanChapterHybrid(
                inputFolder = inFolder.absolutePath,
                chapterVisionResult = chapterVision,
                outputDir = outFolder.absolutePath,
                model = InpaintingModel.MANGA,
                strategy = CleaningStrategy.AUTO_HYBRID,
                targetClasses = listOf("text"),
                dilationRadius = 2,
                adaptivePadding = true,
                saveMasks = false,
                context = context,
                hostFs = hostFs
            )

            assertEquals(2, result.totalCleanedPages)
            assertEquals(2, result.cleanedImagePaths.size)
            assertTrue(File(result.cleanedImagePaths[0]).exists())
            assertTrue(File(result.cleanedImagePaths[1]).exists())
        }
    }

    @Test
    fun testDownloadModelActions() {
        val cleaner = CleanerPlugin()
        val logger = FakeLogger()
        val toastMessages = mutableListOf<String>()
        val dummyYaml = """
            name: TestModel
            type: INPAINTING
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
            cleaner.downloadModel(InpaintingDownloadModel.LAMA, context)
            assertTrue(toastMessages.any { it.contains("Downloaded model: LAMA") })

            cleaner.downloadAllModels(context)
            assertTrue(toastMessages.any { it.contains("All inpainting models downloaded successfully") })
        }
    }

    @Test
    fun testInpaintingModelResolutionAndFallbacks() {
        // Maintained models
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("lama"))
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("big-lama"))
        assertEquals(InpaintingModel.MANGA, InpaintingModel.fromModelId("manga"))
        assertEquals(InpaintingModel.MANGA, InpaintingModel.fromModelId("anime-manga-big-lama"))
        assertEquals(InpaintingModel.MIGAN, InpaintingModel.fromModelId("migan"))
        assertEquals(InpaintingModel.MIGAN, InpaintingModel.fromModelId("migan_traced"))
        assertEquals(InpaintingModel.ZITS, InpaintingModel.fromModelId("zits"))
        assertEquals(InpaintingModel.ZITS, InpaintingModel.fromModelId("zits-inpaint-0717"))
        assertEquals(InpaintingModel.ZITSPP, InpaintingModel.fromModelId("zitspp"))
        assertEquals(InpaintingModel.ZITSPP, InpaintingModel.fromModelId("zits++"))
        assertEquals(InpaintingModel.ZITSPP, InpaintingModel.fromModelId("zits_plusplus"))

        // Deprecated models fallback gracefully to LAMA without error
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("mat"))
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("places_512_fulldata_g"))
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("diffusion"))
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("ldm"))
        assertEquals(InpaintingModel.LAMA, InpaintingModel.fromModelId("diffusion_overkill"))

        // Download models
        assertEquals(InpaintingDownloadModel.ZITS, InpaintingDownloadModel.fromModelId("zits"))
        assertEquals(InpaintingDownloadModel.ZITS, InpaintingDownloadModel.fromModelId("zits-inpaint-0717"))
        assertEquals(InpaintingDownloadModel.ZITSPP, InpaintingDownloadModel.fromModelId("zitspp"))
        assertEquals(InpaintingDownloadModel.LAMA, InpaintingDownloadModel.fromModelId("mat"))
        assertEquals(InpaintingDownloadModel.LAMA, InpaintingDownloadModel.fromModelId("diffusion"))
    }

    @Test
    fun testCleanImageWithAdvancedParameters() {
        val cleaner = CleanerPlugin()
        val tempDir = File("build/tmp/test_cleaner_advanced").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val imgFile = File(tempDir, "page_adv.png")
        val bi = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = bi.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 100, 100)
        g.color = Color.BLACK
        g.fillRect(20, 20, 30, 30) // Text block
        g.dispose()
        ImageIO.write(bi, "png", imgFile)

        val outDir = File(tempDir, "out")
        val visResult = VisionResult(
            objects = listOf(
                SegmentedObject(
                    label = "text",
                    confidence = 0.99,
                    box = DetectionBox("text", 0.99, 0.2, 0.2, 0.5, 0.5),
                    polygon = listOf(
                        PolygonPoint(0.2, 0.2),
                        PolygonPoint(0.5, 0.2),
                        PolygonPoint(0.5, 0.5),
                        PolygonPoint(0.2, 0.5)
                    )
                )
            ),
            imageWidth = 100,
            imageHeight = 100,
            pageName = "page_adv.png"
        )

        val logger = FakeLogger()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)
        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            val result = cleaner.cleanImage(
                imagePath = imgFile.absolutePath,
                segmentationData = visResult,
                outputDir = outDir.absolutePath,
                model = InpaintingModel.ZITSPP,
                targetClasses = listOf("text"),
                dilationRadius = 2,
                saveMask = true,
                isolatedRegionsOnly = false,
                featherRadius = 3,
                blendingMode = BlendingMode.FEATHER,
                cropMargin = 16,
                iterations = 5,
                addV = 0.05,
                mulV = 1.02,
                sigma256 = 1.8,
                maskTh = 0.88,
                objRemoval = true,
                binaryThreshold = 45,
                context = context,
                hostFs = hostFs
            )

            assertEquals(1, result.cleanedObjectsCount)
            assertTrue(File(result.cleanedImagePath).exists())
            assertTrue(result.maskPath != null && File(result.maskPath!!).exists())
        }
    }

    @Test
    fun testCleanImageWithDifferentBlendingModes() {
        val cleaner = CleanerPlugin()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cleaner_blending_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val imgFile = File(tempDir, "test_blend_in.png")
        val outDir = File(tempDir, "out")
        outDir.mkdirs()

        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(80, 80, 80)
        g.fillRect(0, 0, 100, 100)
        g.color = Color.BLACK
        g.fillRect(30, 30, 40, 40)
        g.dispose()
        ImageIO.write(img, "png", imgFile)

        val visResult = VisionResult(
            imageWidth = 100,
            imageHeight = 100,
            objects = listOf(
                SegmentedObject(
                    label = "text",
                    confidence = 0.95,
                    box = DetectionBox("text", 0.95, 0.3, 0.3, 0.7, 0.7),
                    polygon = listOf(
                        PolygonPoint(0.3, 0.3),
                        PolygonPoint(0.7, 0.3),
                        PolygonPoint(0.7, 0.7),
                        PolygonPoint(0.3, 0.7)
                    )
                )
            )
        )

        val logger = FakeLogger()
        val pluginFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { readFile(any()) } returns null
            coEvery { exists(any()) } returns false
        }
        val hostFs = mockk<HostFileSystem>(relaxed = true)
        val context = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.logger } returns logger
            every { this@mockk.fileSystem } returns pluginFs
        }

        runBlocking {
            // Test 1: POISSON blending mode
            val poissonResult = cleaner.cleanImage(
                imagePath = imgFile.absolutePath,
                segmentationData = visResult,
                outputDir = File(outDir, "poisson").absolutePath,
                model = InpaintingModel.LAMA,
                targetClasses = listOf("text"),
                blendingMode = BlendingMode.POISSON,
                context = context,
                hostFs = hostFs
            )
            assertTrue(File(poissonResult.cleanedImagePath).exists())

            // Test 2: MODIFIED_POISSON blending mode
            val modPoissonResult = cleaner.cleanImage(
                imagePath = imgFile.absolutePath,
                segmentationData = visResult,
                outputDir = File(outDir, "mod_poisson").absolutePath,
                model = InpaintingModel.LAMA,
                targetClasses = listOf("text"),
                blendingMode = BlendingMode.MODIFIED_POISSON,
                context = context,
                hostFs = hostFs
            )
            assertTrue(File(modPoissonResult.cleanedImagePath).exists())

            // Test 3: LAPLACIAN_PYRAMID blending mode
            val laplacianResult = cleaner.cleanImage(
                imagePath = imgFile.absolutePath,
                segmentationData = visResult,
                outputDir = File(outDir, "laplacian").absolutePath,
                model = InpaintingModel.LAMA,
                targetClasses = listOf("text"),
                blendingMode = BlendingMode.LAPLACIAN_PYRAMID,
                context = context,
                hostFs = hostFs
            )
            assertTrue(File(laplacianResult.cleanedImagePath).exists())
        }
    }
}

