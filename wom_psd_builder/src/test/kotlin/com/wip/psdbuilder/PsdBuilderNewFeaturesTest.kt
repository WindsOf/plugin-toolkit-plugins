package com.wip.psdbuilder

import com.wip.common.models.DetectionBox
import com.wip.common.models.OcrCategory
import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
import com.wip.kpsd.KPsd
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

    @Test
    fun testGhostLayerAndHallucinationFilteringInPsdBuilder() {
        runBlocking {
            val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
            val ctx = mockk<PluginContext>(relaxed = true)

            val w = 500
            val h = 800
            val baseImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                texts = listOf(
                    "(no text)",
                    "Valid Text Layer",
                    "(nessun testo)",
                    "The image contains no text. The OCR result \"1\" is a hallucination"
                ),
                balloonBoxes = listOf(
                    listOf(0.0, 0.0, 1.0, 1.0),
                    listOf(0.1, 0.1, 0.3, 0.5),
                    listOf(0.0, 0.0, 1.0, 1.0),
                    listOf(0.0, 0.0, 1.0, 1.0)
                ),
                context = ctx
            )

            assertNotNull(psd)
            val translationGroup = psd.children.firstOrNull { it.name == "translation" }
            assertNotNull(translationGroup)
            val layers = translationGroup.children
            assertNotNull(layers)
            assertEquals(1, layers.size, "Only 1 valid text layer must exist, ghost/hallucination layers skipped")
            assertEquals("Valid Text Layer", layers[0].name)
        }
    }

    @Test
    fun testNaturalOrderSequentialPsdNaming() {
        runBlocking {
            val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
            val ctx = mockk<PluginContext>(relaxed = true)

            val tempInputDir = File.createTempFile("chapter_nat_in", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }
            val tempOutputDir = File.createTempFile("chapter_nat_out", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

            // Create images named 1.png, 2.png, 10.png, 11.png (like Slicer output)
            val names = listOf("1.png", "2.png", "10.png", "11.png")
            for (name in names) {
                val f = File(tempInputDir, name)
                val img = BufferedImage(100, 300, BufferedImage.TYPE_INT_RGB)
                ImageIO.write(img, "png", f)
            }

            val result = plugin.buildPsdForChapter(
                inputFolder = tempInputDir.absolutePath,
                texts = listOf("Text 1", "Text 2", "Text 10", "Text 11"),
                balloonBoxes = listOf(
                    listOf(0.1, 0.1, 0.3, 0.8),
                    listOf(0.2, 0.2, 0.4, 0.7),
                    listOf(0.15, 0.15, 0.35, 0.85),
                    listOf(0.1, 0.1, 0.3, 0.8)
                ),
                pageNames = names,
                outputDir = tempOutputDir.absolutePath,
                desiredHeight = 0,
                context = ctx,
                hostFs = mockk(relaxed = true)
            )

            assertNotNull(result)
            assertEquals(4, result.psdPaths.size)
            assertEquals("001.psd", File(result.psdPaths[0]).name)
            assertEquals("002.psd", File(result.psdPaths[1]).name)
            assertEquals("003.psd", File(result.psdPaths[2]).name)
            assertEquals("004.psd", File(result.psdPaths[3]).name)

            // Also test merging respects natural sort order (1.png + 2.png -> 001.psd, 10.png + 11.png -> 002.psd)
            val tempMergeDir = File.createTempFile("chapter_merge_out", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

            val mergeResult = plugin.buildPsdForChapter(
                inputFolder = tempInputDir.absolutePath,
                texts = listOf("Text 1", "Text 2", "Text 10", "Text 11"),
                balloonBoxes = listOf(
                    listOf(0.1, 0.1, 0.3, 0.8),
                    listOf(0.2, 0.2, 0.4, 0.7),
                    listOf(0.15, 0.15, 0.35, 0.85),
                    listOf(0.1, 0.1, 0.3, 0.8)
                ),
                pageNames = names,
                outputDir = tempMergeDir.absolutePath,
                desiredHeight = 600, // Merges 2 pages of height 300 each
                context = ctx,
                hostFs = mockk(relaxed = true)
            )

            assertEquals(2, mergeResult.psdPaths.size)
            assertEquals("001.psd", File(mergeResult.psdPaths[0]).name)
            assertEquals("002.psd", File(mergeResult.psdPaths[1]).name)

            tempInputDir.deleteRecursively()
            tempOutputDir.deleteRecursively()
            tempMergeDir.deleteRecursively()
        }
    }

    @Test
    fun testDefaultVisibleClassesHidesNonTargetTextLayers() {
        runBlocking {
            val plugin = PSDBuilderPlugin()
            val ctx = mockk<PluginContext>(relaxed = true)

            val baseImg = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                texts = listOf("Speech text", "SFX boom"),
                balloonBoxes = listOf(listOf(0.1, 0.1, 0.4, 0.4), listOf(0.5, 0.5, 0.8, 0.8)),
                categories = listOf("speech", "sfx"),
                defaultVisibleClasses = listOf(OcrCategory.speech),
                context = ctx
            )

            val translationGroup = psd.children.firstOrNull { it.name == "translation" }
            assertNotNull(translationGroup)
            val layers = translationGroup.children ?: emptyList()
            assertEquals(2, layers.size)
            assertEquals(false, layers[0].hidden, "Speech layer should be visible")
            assertEquals(true, layers[1].hidden, "SFX layer should be hidden when defaultVisibleClasses only contains speech")
        }
    }

    @Test
    fun testCleanPatchesImportedIntoCleanGroupOnly() {
        runBlocking {
            val plugin = PSDBuilderPlugin()
            val ctx = mockk<PluginContext>(relaxed = true)

            val tempDir = File("build/tmp/test_psd_patches").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val patchImg = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)
            val patchFile = File(tempDir, "patch_0.png")
            ImageIO.write(patchImg, "png", patchFile)

            val baseImg = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                texts = listOf("Dialogue"),
                balloonBoxes = listOf(listOf(0.1, 0.1, 0.4, 0.4)),
                cleanPatches = listOf(patchFile.absolutePath),
                context = ctx
            )

            val cleanGroup = psd.children.firstOrNull { it.name == "clean" }
            assertNotNull(cleanGroup, "Clean group must exist")
            val cleanPatchLayer = cleanGroup.children?.firstOrNull { it.name == "clean_patch_1" }
            assertNotNull(cleanPatchLayer, "clean_patch_1 must be inside clean group")

            val translationGroup = psd.children.firstOrNull { it.name == "translation" }
            assertNotNull(translationGroup, "Translation group must exist")
            val translationPatches = translationGroup.children?.filter { it.name?.startsWith("clean_patch") == true } ?: emptyList()
            assertTrue(translationPatches.isEmpty(), "Clean patches must NEVER be imported into translation group")

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCleanPatchesFilteredByVisibilityClasses() {
        runBlocking {
            val plugin = PSDBuilderPlugin()
            val ctx = mockk<PluginContext>(relaxed = true)

            val tempDir = File("build/tmp/test_psd_patch_vis").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val patchImg1 = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)
            val patchFile1 = File(tempDir, "patch_0.png") // Speech patch
            ImageIO.write(patchImg1, "png", patchFile1)

            val patchImg2 = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
            val patchFile2 = File(tempDir, "patch_1.png") // SFX patch
            ImageIO.write(patchImg2, "png", patchFile2)

            val baseImg = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
            val visionResult = VisionResult(
                objects = listOf(
                    SegmentedObject(
                        label = "speech",
                        confidence = 0.95,
                        box = DetectionBox(label = "speech", confidence = 0.95, ymin = 0.1, xmin = 0.1, ymax = 0.35, xmax = 0.35)
                    ),
                    SegmentedObject(
                        label = "sfx",
                        confidence = 0.90,
                        box = DetectionBox(label = "sfx", confidence = 0.90, ymin = 0.5, xmin = 0.5, ymax = 0.7, xmax = 0.7)
                    )
                ),
                imageWidth = 200,
                imageHeight = 200,
                pageName = "page_01.png"
            )

            val psd = plugin.buildPsdObject(
                baseImageBmp = baseImg,
                texts = listOf("Dialogue", "BOOM"),
                balloonBoxes = listOf(listOf(0.1, 0.1, 0.35, 0.35), listOf(0.5, 0.5, 0.7, 0.7)),
                categories = listOf("speech", "sfx"),
                cleanPatches = listOf(patchFile1.absolutePath, patchFile2.absolutePath),
                visionResult = visionResult,
                defaultVisibleClasses = listOf(OcrCategory.speech), // SFX is excluded
                context = ctx
            )

            val cleanGroup = psd.children.firstOrNull { it.name == "clean" }
            assertNotNull(cleanGroup, "Clean group must exist")

            val speechPatch = cleanGroup.children?.firstOrNull { it.name == "clean_patch_1" }
            assertNotNull(speechPatch, "clean_patch_1 must exist")
            assertEquals(false, speechPatch.hidden, "Speech patch must be visible when speech is included")

            val sfxPatch = cleanGroup.children?.firstOrNull { it.name == "clean_patch_2" }
            assertNotNull(sfxPatch, "clean_patch_2 must exist")
            assertEquals(true, sfxPatch.hidden, "SFX patch must be hidden when sfx is excluded from defaultVisibleClasses")

            // Redundant clean_image must be omitted when working solely with crop patches
            val cleanImageLayer = cleanGroup.children?.firstOrNull { it.name == "clean_image" }
            kotlin.test.assertNull(cleanImageLayer, "clean_image must be omitted when only crop patches are supplied")

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testChapterPsdPatchesNotLeakedAcrossPages() {
        runBlocking {
            val plugin = PSDBuilderPlugin()
            val ctx = mockk<PluginContext>(relaxed = true)

            val tempDir = File("build/tmp/test_psd_chapter_patches").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            val inputDir = File(tempDir, "input").apply { mkdirs() }
            val outDir = File(tempDir, "output").apply { mkdirs() }

            val p1 = File(inputDir, "page_01.png")
            val p2 = File(inputDir, "page_02.png")
            ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", p1)
            ImageIO.write(BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", p2)

            val p1PatchesDir = File(tempDir, "page_01_patches").apply { mkdirs() }
            val p1Patch = File(p1PatchesDir, "patch_0.png")
            ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), "png", p1Patch)

            val p2PatchesDir = File(tempDir, "page_02_patches").apply { mkdirs() }
            val p2Patch = File(p2PatchesDir, "patch_0.png")
            ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), "png", p2Patch)

            val ocrData = com.wip.common.models.OCRResult(
                texts = listOf("Text 1", "Text 2"),
                bb = listOf(listOf(0.1, 0.1, 0.3, 0.3), listOf(0.1, 0.1, 0.3, 0.3)),
                pageNumbers = listOf(1, 2),
                pageNames = listOf("page_01.png", "page_02.png"),
                failedFiles = emptyList(),
                categories = listOf("speech", "speech")
            )

            val result = plugin.buildPsdForChapter(
                inputFolder = inputDir.absolutePath,
                texts = ocrData.texts,
                balloonBoxes = ocrData.bb,
                pageNames = ocrData.pageNames,
                outputDir = outDir.absolutePath,
                cleanPatches = listOf(p1Patch.absolutePath, p2Patch.absolutePath),
                categories = ocrData.categories,
                context = ctx,
                hostFs = mockk(relaxed = true)
            )

            assertEquals(2, result.psdPaths.size)

            val psd1Bytes = File(result.psdPaths[0]).readBytes()
            val psd1 = KPsd.read(psd1Bytes)
            val cleanGroup1 = psd1.children.firstOrNull { it.name == "clean" }
            assertNotNull(cleanGroup1)
            val patchesIn1 = cleanGroup1.children?.filter { it.name?.startsWith("clean_patch") == true } ?: emptyList()
            assertEquals(1, patchesIn1.size, "page_01 must contain only its own patch, never page_02's patch")

            val psd2Bytes = File(result.psdPaths[1]).readBytes()
            val psd2 = KPsd.read(psd2Bytes)
            val cleanGroup2 = psd2.children.firstOrNull { it.name == "clean" }
            assertNotNull(cleanGroup2)
            val patchesIn2 = cleanGroup2.children?.filter { it.name?.startsWith("clean_patch") == true } ?: emptyList()
            assertEquals(1, patchesIn2.size, "page_02 must contain only its own patch, never page_01's patch")

            tempDir.deleteRecursively()
        }
    }
}
