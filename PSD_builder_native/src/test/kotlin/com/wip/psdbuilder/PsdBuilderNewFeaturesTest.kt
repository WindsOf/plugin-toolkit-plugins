package com.wip.psdbuilder

import com.wip.common.models.DetectionBox
import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
import com.wip.kpsd.KPsd
import com.wip.kpsd.Layer
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PsdBuilderNewFeaturesTest {

    @Test
    fun testDebugModeAndCleanImageMemoryOptimization() {
        runBlocking {
            val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = true))
            val ctx = mockk<PluginContext>(relaxed = true)

            val w = 200
            val h = 400
            val baseImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val gBase = baseImg.createGraphics()
            gBase.color = Color.WHITE
            gBase.fillRect(0, 0, w, h)
            gBase.dispose()

            val cleanImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val gClean = cleanImg.createGraphics()
            gClean.color = Color.LIGHT_GRAY
            gClean.fillRect(0, 0, w, h)
            gClean.dispose()

            val visionResult = VisionResult(
                objects = listOf(
                    SegmentedObject(
                        label = "balloon",
                        confidence = 0.95,
                        box = DetectionBox(ymin = 0.1, xmin = 0.1, ymax = 0.4, xmax = 0.8),
                        polygon = listOf(
                            PolygonPoint(0.1, 0.1),
                            PolygonPoint(0.8, 0.1),
                            PolygonPoint(0.8, 0.4),
                            PolygonPoint(0.1, 0.4)
                        )
                    )
                )
            )

            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                cleanImageBmp = cleanImg,
                texts = listOf("Test dialogue"),
                balloonBoxes = listOf(listOf(0.1, 0.1, 0.4, 0.8)),
                visionResult = visionResult,
                context = ctx
            )

            assertNotNull(psd)
            assertEquals(w, psd.width)
            assertEquals(h, psd.height)

            val debugBoxesGroup = psd.children.firstOrNull { it.name == "debug_boxes" }
            assertNotNull(debugBoxesGroup, "debug_boxes group should exist when debugMode is enabled")

            val psdBytes = KPsd.write(psd, compress = false)
            assertTrue(psdBytes.isNotEmpty())
        }
    }

    @Test
    fun testCleanerBoxPriorityWithoutMixAndMatch() {
        runBlocking {
            val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
            val ctx = mockk<PluginContext>(relaxed = true)

            val w = 1000
            val h = 1000
            val baseImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

            // Cleaner balloon covers (100, 100) to (700, 700)
            val cleanerBalloon = SegmentedObject(
                label = "speech_balloon",
                confidence = 0.95,
                box = DetectionBox(ymin = 0.1, xmin = 0.1, ymax = 0.7, xmax = 0.7),
                polygon = listOf(
                    PolygonPoint(0.1, 0.1),
                    PolygonPoint(0.7, 0.1),
                    PolygonPoint(0.7, 0.7),
                    PolygonPoint(0.1, 0.7)
                )
            )

            // OCR text box is small in the middle (300, 300) to (450, 550)
            val textBox = listOf(0.3, 0.3, 0.45, 0.55)
            // Inaccurate/different OCR balloon box
            val ocrBalloonBox = listOf(0.25, 0.25, 0.5, 0.6)

            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                texts = listOf("Mi restano solo pochi giorni"),
                balloonBoxes = listOf(ocrBalloonBox),
                textBoxes = listOf(textBox),
                visionResult = VisionResult(objects = listOf(cleanerBalloon)),
                context = ctx
            )

            val translationGroup = psd.children.firstOrNull { it.name == "translation" }
            assertNotNull(translationGroup)
            val textLayer = translationGroup.children?.firstOrNull()
            assertNotNull(textLayer)

            // Should match cleaner balloon bounds (100, 100, 700, 700) directly without clamping to text box
            assertEquals(100, textLayer.left)
            assertEquals(100, textLayer.top)
            assertEquals(700, textLayer.right)
            assertEquals(700, textLayer.bottom)
        }
    }

    @Test
    fun testDropToOcrBalloonBoxWhenCleanerBoxNotAvailable() {
        runBlocking {
            val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
            val ctx = mockk<PluginContext>(relaxed = true)

            val w = 1000
            val h = 1000
            val baseImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

            // OCR balloon box is large (100, 100) to (800, 800)
            val ocrBalloonBox = listOf(0.1, 0.1, 0.8, 0.8)
            // OCR text box is small inside (300, 300) to (400, 500)
            val textBox = listOf(0.3, 0.3, 0.4, 0.5)

            // No vision result provided
            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                texts = listOf("Dialogue without cleaner"),
                balloonBoxes = listOf(ocrBalloonBox),
                textBoxes = listOf(textBox),
                visionResult = null,
                context = ctx
            )

            val translationGroup = psd.children.firstOrNull { it.name == "translation" }
            assertNotNull(translationGroup)
            val textLayer = translationGroup.children?.firstOrNull()
            assertNotNull(textLayer)

            // Should use OCR balloon box (100, 100, 800, 800) directly without clamping
            assertEquals(100, textLayer.left)
            assertEquals(100, textLayer.top)
            assertEquals(800, textLayer.right)
            assertEquals(800, textLayer.bottom)
        }
    }

    @Test
    fun testChapterPSDBuildMemoryOptimized() {
        runBlocking {
            val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
            val ctx = mockk<PluginContext>(relaxed = true)

            val tempInputDir = File.createTempFile("chapter_in", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }
            val tempOutputDir = File.createTempFile("chapter_out", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

            val pageNames = mutableListOf<String>()
            for (i in 1..4) {
                val f = File(tempInputDir, "page_$i.png")
                val img = BufferedImage(150, 300, BufferedImage.TYPE_INT_RGB)
                ImageIO.write(img, "png", f)
                pageNames.add(f.name)
            }

            val result = plugin.buildPsdForChapter(
                inputFolder = tempInputDir.absolutePath,
                texts = listOf("Text 1", "Text 2", "Text 3", "Text 4"),
                balloonBoxes = listOf(
                    listOf(0.1, 0.1, 0.3, 0.8),
                    listOf(0.2, 0.2, 0.4, 0.7),
                    listOf(0.15, 0.15, 0.35, 0.85),
                    listOf(0.1, 0.1, 0.3, 0.8)
                ),
                pageNames = pageNames,
                outputDir = tempOutputDir.absolutePath,
                desiredHeight = 600, // Merges 2 pages per PSD
                context = ctx,
                hostFs = mockk(relaxed = true)
            )

            assertNotNull(result)
            assertTrue(result.psdPaths.isNotEmpty())
            for (path in result.psdPaths) {
                val f = File(path)
                assertTrue(f.exists() && f.length() > 0)
            }

            tempInputDir.deleteRecursively()
            tempOutputDir.deleteRecursively()
        }
    }
}
