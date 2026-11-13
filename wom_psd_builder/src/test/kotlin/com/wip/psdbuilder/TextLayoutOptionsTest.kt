package com.wip.psdbuilder

import com.wip.common.models.DetectionBox
import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
import com.wip.kpsd.Justification
import com.wip.kpsd.KPsd
import com.wip.kpsd.Layer
import com.wip.kpsd.Psd
import com.wip.kpsd.TextShapeType
import com.wip.kpsd.WordBreak
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextLayoutOptionsTest {

    private fun firstTranslationLayer(psd: Psd): Layer =
        psd.children.first { it.name == "translation" }.children!!.first()

    private fun roundTripTranslationLayer(psd: Psd): Layer =
        firstTranslationLayer(KPsd.read(KPsd.write(psd, compress = false)))

    private fun Layer.effectiveAutoHyphenate(): Boolean? =
        text?.paragraphStyle?.autoHyphenate
            ?: text?.paragraphStyleRuns?.firstOrNull()?.style?.autoHyphenate

    private fun String?.hasLineBreak(): Boolean =
        this?.let { it.contains('\r') || it.contains('\n') } == true

    @Test
    fun testHyphenationModesAndAutoHyphenateSync(): Unit = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val ctx = mockk<PluginContext>(relaxed = true)
        val baseImg = BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB)
        val longWord = "Supercalifragilisticexpialidocious"
        val box = listOf(listOf(0.2, 0.2, 0.6, 0.5))

        // 1. NONE -> autoHyphenate = false, no hyphen inserted
        val psdNone = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(longWord),
            balloonBoxes = box,
            fontSizes = listOf(40),
            shapes = listOf("rectangular"),
            wordBreak = WordBreak.NONE,
            context = ctx
        )
        val layerNone = roundTripTranslationLayer(psdNone)
        assertEquals(false, firstTranslationLayer(psdNone).text?.paragraphStyle?.autoHyphenate)
        assertEquals(false, layerNone.effectiveAutoHyphenate())
        assertFalse(layerNone.text?.text?.contains("-") == true)

        // 2. HYPHENATE -> autoHyphenate = true, hyphen inserted when word exceeds width
        val psdHyphen = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(longWord),
            balloonBoxes = box,
            fontSizes = listOf(40),
            shapes = listOf("rectangular"),
            wordBreak = WordBreak.HYPHENATE,
            context = ctx
        )
        val layerHyphen = roundTripTranslationLayer(psdHyphen)
        assertEquals(true, layerHyphen.effectiveAutoHyphenate())
        assertTrue(layerHyphen.text?.text?.contains("-") == true, "Expected hyphenated break in '${layerHyphen.text?.text}'")

        // 3. BREAK_WORD -> autoHyphenate = false, breaks across lines without adding '-'
        val psdBreak = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(longWord),
            balloonBoxes = box,
            fontSizes = listOf(40),
            shapes = listOf("rectangular"),
            wordBreak = WordBreak.BREAK_WORD,
            context = ctx
        )
        val layerBreak = roundTripTranslationLayer(psdBreak)
        assertEquals(false, layerBreak.effectiveAutoHyphenate())
        assertTrue(layerBreak.text?.text.hasLineBreak(), "Expected word break newline in '${layerBreak.text?.text}'")
        assertFalse(layerBreak.text?.text?.contains("-") == true, "BREAK_WORD should not insert hyphens")
    }

    @Test
    fun testDisableHardcodedNewlinesPreservesOriginalTextWithAutoFitAndCentering(): Unit = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val ctx = mockk<PluginContext>(relaxed = true)
        val baseImg = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
        val sampleText = "This sentence is long enough to wrap inside a narrow speech balloon"
        val box = listOf(listOf(0.2, 0.25, 0.6, 0.55)) // 180px wide x 240px tall

        // With hardcoded newlines enabled (default)
        val psdWrapped = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(sampleText),
            balloonBoxes = box,
            fontSizes = listOf(60),
            shapes = listOf("rectangular"),
            wordBreak = WordBreak.HYPHENATE,
            allowLineWrapping = true,
            insertHardcodedNewlines = true,
            context = ctx
        )
        assertTrue(firstTranslationLayer(psdWrapped).text?.text?.contains('\r') == true)
        val layerWrapped = roundTripTranslationLayer(psdWrapped)
        assertTrue(layerWrapped.text?.text.hasLineBreak(), "Default mode should insert line breaks")

        // With hardcoded newlines disabled
        val psdNoHardcoded = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(sampleText),
            balloonBoxes = box,
            fontSizes = listOf(60),
            shapes = listOf("rectangular"),
            wordBreak = WordBreak.HYPHENATE,
            allowLineWrapping = true,
            insertHardcodedNewlines = false,
            context = ctx
        )
        val layerNoHardcoded = roundTripTranslationLayer(psdNoHardcoded)
        val textData = layerNoHardcoded.text!!

        assertEquals(sampleText, textData.text, "Original text must be preserved without hardcoded breaks")
        assertEquals(TextShapeType.BOX, textData.shapeType)
        assertEquals(true, layerNoHardcoded.effectiveAutoHyphenate(), "Photoshop autoHyphenate must still sync with HYPHENATE")
        assertEquals(Justification.CENTER, textData.paragraphStyle?.justification)

        val fittedFontSize = textData.style?.fontSize
        assertNotNull(fittedFontSize)
        assertEquals(layerWrapped.text?.style?.fontSize, fittedFontSize, "AutoFit font size should match wrapped layout")
        assertTrue(fittedFontSize < 60f, "Font size ($fittedFontSize) should auto-fit below 60")

        val bounds = textData.boxBounds
        assertNotNull(bounds)
        assertTrue(bounds[1] > 0f && bounds[3] < 240f, "Box bounds should be vertically centered: ${bounds.toList()}")
    }

    @Test
    fun testSingleLineCenteredModePreventsLineWrapping(): Unit = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val ctx = mockk<PluginContext>(relaxed = true)
        val baseImg = BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB)

        // Vision polygon with a tail to verify visualCenter + symmetric single-line centering still works
        val balloon = SegmentedObject(
            label = "balloon",
            confidence = 0.99,
            box = DetectionBox(ymin = 0.2, xmin = 0.2, ymax = 0.6, xmax = 0.6),
            polygon = listOf(
                PolygonPoint(0.2, 0.2),
                PolygonPoint(0.6, 0.2),
                PolygonPoint(0.6, 0.6),
                PolygonPoint(0.2, 0.6)
            )
        )

        val multiWord = "Hello\nworld from single line"
        val psdMultiLine = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(multiWord),
            balloonBoxes = listOf(listOf(0.2, 0.2, 0.6, 0.6)),
            fontSizes = listOf(60),
            visionResult = VisionResult(objects = listOf(balloon)),
            allowLineWrapping = true,
            insertHardcodedNewlines = true,
            context = ctx
        )
        val multiLayer = KPsd.read(KPsd.write(psdMultiLine, compress = false))
            .children.first { it.name == "translation" }.children!!.first()

        val psdSingleLine = plugin.buildPsdObject(
            baseImageBmp = baseImg,
            texts = listOf(multiWord),
            balloonBoxes = listOf(listOf(0.2, 0.2, 0.6, 0.6)),
            fontSizes = listOf(60),
            visionResult = VisionResult(objects = listOf(balloon)),
            wordBreak = WordBreak.HYPHENATE,
            allowLineWrapping = false,
            insertHardcodedNewlines = true,
            context = ctx
        )
        val singleLayer = KPsd.read(KPsd.write(psdSingleLine, compress = false))
            .children.first { it.name == "translation" }.children!!.first()
        val singleText = singleLayer.text!!

        assertEquals("Hello world from single line", singleText.text, "Newlines must be collapsed and no \\r added")
        assertEquals(Justification.CENTER, singleText.paragraphStyle?.justification)
        assertEquals(false, singleLayer.effectiveAutoHyphenate(), "Single-line mode should disable autoHyphenate")

        val singleFont = singleText.style?.fontSize!!
        val multiFont = multiLayer.text?.style?.fontSize!!
        assertTrue(singleFont < multiFont, "Single-line font ($singleFont) must shrink smaller than multi-line ($multiFont) to fit on 1 line")

        val builtSingleLayer = firstTranslationLayer(psdSingleLine)
        val boxHeight = (builtSingleLayer.bottom - builtSingleLayer.top).toFloat()
        assertEquals(320f, boxHeight)
        val bounds = singleText.boxBounds!!
        val topMargin = bounds[1]
        val bottomMargin = boxHeight - bounds[3]
        assertTrue(topMargin > 0f, "Single-line text must be vertically centered inside box")
        assertEquals(topMargin, bottomMargin, 1.0f, "Vertical centering margins must be symmetric")
    }

    @Test
    fun testPluginSettingsAndCapabilityOverrides(): Unit = runBlocking {
        val tempDir = File("build/tmp/test_psd_layout_overrides").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val imgFile = File(tempDir, "page.png")
        ImageIO.write(BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB), "png", imgFile)

        val pluginWithDefaults = PSDBuilderPlugin(
            PSDBuilderSettings(
                debugMode = false,
                wordBreak = PsdWordBreak.HYPHENATE,
                allowLineWrapping = true,
                insertHardcodedNewlines = false
            )
        )
        val ctx = mockk<PluginContext>(relaxed = true)
        val text = "Testing plugin setting defaults and overrides"
        val boxes = listOf(listOf(0.1, 0.1, 0.5, 0.5))

        // 1. Capability DEFAULT defers to PSDBuilderSettings
        val outDefaultDir = File(tempDir, "out_default").apply { mkdirs() }
        val resDefault = pluginWithDefaults.buildPsdFromInputs(
            imagePath = imgFile.absolutePath,
            texts = listOf(text),
            balloonBoxes = boxes,
            fontSize = 48,
            outputDir = outDefaultDir.absolutePath,
            context = ctx,
            hostFs = mockk(relaxed = true)
        )
        val layerDefault = firstTranslationLayer(KPsd.read(File(resDefault.psdPath).readBytes()))
        assertEquals(text, layerDefault.text?.text, "Setting insertHardcodedNewlines=false must preserve text")
        assertEquals(true, layerDefault.effectiveAutoHyphenate(), "Setting wordBreak=HYPHENATE must sync autoHyphenate=true")

        // 2. Capability explicit overrides win over settings
        val outOverrideDir = File(tempDir, "out_override").apply { mkdirs() }
        val resOverride = pluginWithDefaults.buildPsdFromInputs(
            imagePath = imgFile.absolutePath,
            texts = listOf(text),
            balloonBoxes = boxes,
            fontSize = 48,
            outputDir = outOverrideDir.absolutePath,
            wordBreak = WordBreakMode.NONE,
            allowLineWrapping = FeatureToggleMode.ENABLED,
            insertHardcodedNewlines = FeatureToggleMode.ENABLED,
            context = ctx,
            hostFs = mockk(relaxed = true)
        )
        val layerOverride = firstTranslationLayer(KPsd.read(File(resOverride.psdPath).readBytes()))
        assertTrue(layerOverride.text?.text.hasLineBreak(), "Capability override insertHardcodedNewlines=ENABLED must insert line breaks")
        assertEquals(false, layerOverride.effectiveAutoHyphenate(), "Capability override wordBreak=NONE must set autoHyphenate=false")

        // 3. Chapter build forwards capability overrides
        val chapterInDir = File(tempDir, "chapter_in").apply { mkdirs() }
        ImageIO.write(BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB), "png", File(chapterInDir, "001.png"))
        val chapterOutDir = File(tempDir, "chapter_out").apply { mkdirs() }
        val resChapter = pluginWithDefaults.buildPsdForChapter(
            inputFolder = chapterInDir.absolutePath,
            texts = listOf(text),
            balloonBoxes = boxes,
            pageNames = listOf("001.png"),
            outputDir = chapterOutDir.absolutePath,
            fontSize = 48,
            allowLineWrapping = FeatureToggleMode.DISABLED,
            context = ctx,
            hostFs = mockk(relaxed = true)
        )
        val chapterLayer = firstTranslationLayer(KPsd.read(File(resChapter.psdPaths.first()).readBytes()))
        assertEquals(text, chapterLayer.text?.text)
        assertEquals(false, chapterLayer.effectiveAutoHyphenate())

        tempDir.deleteRecursively()
    }
}
