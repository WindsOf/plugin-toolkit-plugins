package com.wip.vision

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

class VisionPluginTest {

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
        }

        // Test validation failure when models missing
        val missingFs = mockk<PluginFileSystem>(relaxed = true) {
            coEvery { exists(any()) } returns false
        }
        val missingContext = mockk<PluginContext>(relaxed = true) {
            every { this@mockk.fileSystem } returns missingFs
        }
        runBlocking {
            kotlin.test.assertFalse(vision.validate(missingContext).isSuccess)
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
}
