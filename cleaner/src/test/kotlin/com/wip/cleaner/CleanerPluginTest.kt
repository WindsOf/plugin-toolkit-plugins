package com.wip.cleaner

import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.DetectionBox
import com.wip.common.models.InpaintingModel
import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanerPluginTest {

    private class FakeLogger : PluginLogger {
        val messages = mutableListOf<String>()
        override fun verbose(message: String) { messages.add("VERBOSE: $message") }
        override fun debug(message: String) { messages.add("DEBUG: $message") }
        override fun info(message: String) { messages.add("INFO: $message") }
        override fun warn(message: String) { messages.add("WARN: $message") }
        override fun error(message: String, throwable: Throwable?) { messages.add("ERROR: $message") }
    }

    private class FakeProgress : ProgressReporter {
        var lastProgress: Float = 0f
        override fun report(progress: Float) { lastProgress = progress }
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
            assertTrue(locks.containsKey("model:Places_512_FullData_G"))
            assertTrue(locks.containsKey("model:mat"))
            assertTrue(locks.containsKey("model:anime-manga-big-lama"))
            assertTrue(locks.containsKey("model:manga"))
            assertTrue(locks.containsKey("model:diffusion"))
            assertTrue(locks.containsKey("model:ldm"))
            assertTrue(locks.containsKey("model:zits-inpaint-0717"))
            assertTrue(locks.containsKey("model:zits"))
            assertTrue(locks.containsKey("model:places_512_G"))
            assertTrue(locks.containsKey("model:fcf"))
            assertTrue(locks.containsKey("model:migan_traced"))
            assertTrue(locks.containsKey("model:migan"))
        }

        // Test validation failure when no models exist
        val missingFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns false
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
                    polygon = listOf(PolygonPoint(0.2, 0.2), PolygonPoint(0.4, 0.2), PolygonPoint(0.4, 0.4), PolygonPoint(0.2, 0.4))
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
                    polygon = listOf(PolygonPoint(0.2, 0.2), PolygonPoint(0.4, 0.2), PolygonPoint(0.4, 0.4), PolygonPoint(0.2, 0.4))
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
}
