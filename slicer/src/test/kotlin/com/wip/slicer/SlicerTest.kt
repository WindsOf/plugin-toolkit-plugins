package com.wip.slicer

import org.junit.Test
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlicerTest {

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
        val slicer = Slicer()
        val logger = FakeLogger()
        val loadResult = slicer.onLoad(logger)
        assertTrue(loadResult.isSuccess)

        val context = io.mockk.mockk<PluginContext>(relaxed = true)
        kotlinx.coroutines.runBlocking {
            assertTrue(slicer.setup(context).isSuccess)
            assertTrue(slicer.validate(context).isSuccess)
            assertTrue(slicer.update(context).isSuccess)
        }
    }

    @Test
    fun testSlicingExecution() {
        val slicer = Slicer()
        val tempInputDir = File("build/tmp/test_slicer_input").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val tempOutputDir = File("build/tmp/test_slicer_output").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        // Generate 3 sample images with clear white borders suitable for cutting
        for (i in 1..3) {
            val img = BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 400, 600)
            g.color = Color.BLACK
            g.fillRect(50, 100, 300, 400) // Content in the middle, white at top/bottom
            g.dispose()

            ImageIO.write(img, "png", File(tempInputDir, "strip_$i.png"))
        }

        val logger = FakeLogger()
        val progress = FakeProgress()
        val context = io.mockk.mockk<PluginContext>(relaxed = true) {
            io.mockk.every { this@mockk.logger } returns logger
            io.mockk.every { this@mockk.progress } returns progress
        }
        val hostFs = io.mockk.mockk<HostFileSystem>(relaxed = true)

        slicer.slicer(
            folderPath = tempInputDir.absolutePath,
            outputFolderPath = tempOutputDir.absolutePath,
            minHeight = 500,
            desiredHeight = 600,
            maxHeight = 1200,
            prioritizeSmallerImages = true,
            cutTolerance = 5,
            context = context,
            hostFs = hostFs
        )

        val outputFiles = tempOutputDir.listFiles { _, name -> name.endsWith(".png") }
        assertTrue(outputFiles != null && outputFiles.isNotEmpty(), "Slicer should generate output slice images")
    }
}
