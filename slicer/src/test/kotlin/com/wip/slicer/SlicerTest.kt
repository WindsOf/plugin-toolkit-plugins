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
            val locks = slicer.checkLocks(context)
            assertTrue(locks.containsKey("model:yolo-det-x-best-v3"))
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

    @Test
    fun testMaskForbiddenDetectionRows() {
        val slicer = Slicer()
        val rowVariances = MutableList(1000) { true }

        val detections = listOf(
            com.wip.common.models.DetectionBox(
                label = "balloon",
                confidence = 0.95,
                ymin = 0.40,
                xmin = 0.10,
                ymax = 0.60,
                xmax = 0.90
            )
        )

        slicer.maskForbiddenDetectionRows(
            usefulRowVarianceList = rowVariances,
            detections = detections,
            imageHeight = 1000,
            yOffset = 0,
            detectionMargin = 20
        )

        assertTrue(rowVariances[0])
        assertTrue(rowVariances[379])

        for (y in 380..620) {
            kotlin.test.assertFalse(rowVariances[y], "Row $y should be forbidden for cutting")
        }

        assertTrue(rowVariances[621])
        assertTrue(rowVariances[999])
    }

    @Test
    fun testFindOptimalCutsPerformanceOnLargeChapter() {
        val slicer = Slicer()
        val totalHeight = 150_000 // 150k pixels (~50 webtoon pages)
        val rowVariances = ArrayList<Boolean>(totalHeight)
        for (i in 0 until totalHeight) {
            // Create white-gap cut opportunities every ~500 pixels
            rowVariances.add(i % 500 in 0..20)
        }

        val progress = FakeProgress()
        val startTime = System.currentTimeMillis()
        val (cuts, error) = slicer.findOptimalCuts(
            totalHeight = totalHeight,
            usefulRowVarianceList = rowVariances,
            minHeight = 1000,
            desiredHeight = 10000,
            maxHeight = 10000,
            prioritizeSmallerImages = true,
            progressReporter = progress
        )
        val durationMs = System.currentTimeMillis() - startTime

        assertTrue(cuts.isNotEmpty(), "Cuts should not be empty")
        assertEquals(totalHeight, cuts.last(), "Last cut must reach totalHeight")
        assertTrue(durationMs < 500, "150k-pixel DP cut calculation should finish in under 500ms, took ${durationMs}ms")

        var prev = 0
        for (cut in cuts) {
            val sliceH = cut - prev
            assertTrue(sliceH in 1000..10000, "Slice height $sliceH must be within [1000, 10000]")
            prev = cut
        }
    }

    @Test
    fun testSlicerModelEnums() {
        assertEquals("yolo-det-x-best-v3", SlicerModel.YOLO_DET_X.modelId)
        assertEquals(SlicerModel.YOLO_DET_X, SlicerModel.fromModelId("yolo-det-x-best-v3"))
        assertEquals(SlicerModel.YOLO_DET_X, SlicerModel.fromModelId("YOLO-DET-X-BEST-V3"))
        assertEquals(null, SlicerModel.fromModelId("unknown-model"))

        assertEquals("yolo-det-x-best-v3", SlicerDownloadModel.YOLO_DET_X.modelId)
        assertEquals(SlicerDownloadModel.YOLO_DET_X, SlicerDownloadModel.fromModelId("yolo-det-x-best-v3"))
        assertEquals(1, SlicerDownloadModel.entries.size)
    }
}
