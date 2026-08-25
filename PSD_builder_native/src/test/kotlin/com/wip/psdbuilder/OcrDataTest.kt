package com.wip.psdbuilder

import com.wip.common.models.AdvancedOCRResult
import com.wip.common.models.OCRResult
import com.wip.kpsd.KPsd
import io.mockk.mockk
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext

class OcrDataTest {

    @Test
    fun testBuildPsdFromOcrData() = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val context = mockk<PluginContext>(relaxed = true)

        val tempDir = File("build/tmp/test_psdbuilder_native_ocrdata").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val imageFile = File(tempDir, "base_image.png")
        val width = 200
        val height = 200
        val baseImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(baseImage, "png", imageFile)

        val cleanImageFile = File(tempDir, "clean_image.png")
        val cleanImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val cg = cleanImage.createGraphics()
        cg.color = Color.WHITE
        cg.fillRect(0, 0, width, height)
        cg.dispose()
        ImageIO.write(cleanImage, "png", cleanImageFile)

        val ocrResult = OCRResult(
            texts = listOf("Hello", "World"),
            bb = listOf(
                listOf(0.1, 0.1, 0.5, 0.4),
                listOf(0.6, 0.7, 0.9, 0.9)
            ),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("base_image.png", "base_image.png"),
            failedFiles = emptyList()
        )

        val result = plugin.buildPsdFromOcrData(
            imagePath = imageFile.absolutePath,
            cleanImagePath = cleanImageFile.absolutePath,
            ocrData = ocrResult,
            outputDir = tempDir.absolutePath,
            context = context,
            hostFs = mockk(relaxed = true)
        )

        val outputFile = File(result.psdPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
        assertTrue(outputFile.length() > 0, "Output PSD should not be empty")

        val psd = KPsd.read(outputFile.readBytes())
        assertNotNull(psd.children.find { it.name == "raw" }, "Should contain 'raw' base layer")
        val cleanGroup = psd.children.find { it.name == "clean" }
        assertNotNull(cleanGroup, "Should contain 'clean' group folder")
        val translationGroup = psd.children.find { it.name == "translation" }
        assertNotNull(translationGroup, "Should contain 'translation' group folder")
        assertTrue(translationGroup.children?.any { it.text != null } == true, "Translation group should contain text layers")
    }

    @Test
    fun testBuildPsdFromAdvancedOcrData() = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val context = mockk<PluginContext>(relaxed = true)

        val tempDir = File("build/tmp/test_psdbuilder_native_advancedocrdata").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val imageFile = File(tempDir, "base_image.png")
        val width = 200
        val height = 200
        val baseImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.GREEN
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(baseImage, "png", imageFile)

        val advancedOcrResult = AdvancedOCRResult(
            texts = listOf("Advanced", "Test"),
            balloonBoxes = listOf(
                listOf(0.1, 0.1, 0.5, 0.4),
                listOf(0.6, 0.7, 0.9, 0.9)
            ),
            textBoxes = listOf(
                listOf(0.15, 0.15, 0.45, 0.35),
                listOf(0.65, 0.75, 0.85, 0.85)
            ),
            shapes = listOf("oval", "rectangular"),
            fontStyles = listOf("normal", "bold"),
            fontFamilies = listOf("sans-serif", "serif"),
            textAngles = listOf(0.0, 15.0),
            isSparse = listOf(false, false),
            textColors = listOf("#000000", "#FF0000"),
            hasBorder = listOf(true, false),
            borderColors = listOf("#FFFFFF", ""),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("base_image.png", "base_image.png"),
            failedFiles = emptyList()
        )

        val result = plugin.buildPsdFromAdvancedOcrData(
            imagePath = imageFile.absolutePath,
            ocrData = advancedOcrResult,
            outputDir = tempDir.absolutePath,
            context = context,
            hostFs = mockk(relaxed = true)
        )

        val outputFile = File(result.psdPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
        assertTrue(outputFile.length() > 0, "Output PSD should not be empty")

        val psd = KPsd.read(outputFile.readBytes())
        assertNotNull(psd.children.find { it.name == "raw" }, "Should contain 'raw' base layer")
        assertNotNull(psd.children.find { it.name == "clean" }, "Should contain 'clean' group folder")
        val translationGroup = psd.children.find { it.name == "translation" }
        assertNotNull(translationGroup, "Should contain 'translation' group folder")
        val textChildren = translationGroup.children?.filter { it.text != null } ?: emptyList()
        assertEquals(2, textChildren.size, "Translation group should contain 2 text layers")
    }

    @Test
    fun testBuildPsdForChapterWithChapterCleanerResult() = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val context = mockk<PluginContext>(relaxed = true)

        val tempDir = File("build/tmp/test_psdbuilder_clean_result").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val inputDir = File(tempDir, "input_images").apply { mkdirs() }
        val page1 = File(inputDir, "001.png")
        val img1 = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(img1, "png", page1)

        val cleanDir = File(tempDir, "clean_images").apply { mkdirs() }
        val cleanPage1 = File(cleanDir, "001_clean.png")
        val cleanImg1 = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val cg = cleanImg1.createGraphics()
        cg.color = Color.MAGENTA
        cg.fillRect(0, 0, 100, 100)
        cg.dispose()
        ImageIO.write(cleanImg1, "png", cleanPage1)

        val ocrResult = OCRResult(
            texts = listOf("Chapter Text"),
            bb = listOf(listOf(0.2, 0.2, 0.4, 0.6)),
            pageNumbers = listOf(1),
            pageNames = listOf("001.png"),
            failedFiles = emptyList()
        )

        val cleanChapterResult = com.wip.common.models.ChapterCleanerResult(
            cleanedImagePaths = listOf(cleanPage1.absolutePath),
            totalCleanedPages = 1
        )

        val outDir = File(tempDir, "out_psd").apply { mkdirs() }
        val result = plugin.buildPsdForChapterFromOcrData(
            inputFolder = inputDir.absolutePath,
            ocrData = ocrResult,
            outputDir = outDir.absolutePath,
            cleanChapterResult = cleanChapterResult,
            context = context,
            hostFs = mockk(relaxed = true)
        )

        assertEquals(1, result.psdPaths.size)
        val psdFile = File(result.psdPaths.first())
        assertTrue(psdFile.exists())

        val psd = KPsd.read(psdFile.readBytes())
        val cleanGroup = psd.children.find { it.name == "clean" }
        assertNotNull(cleanGroup, "Clean group should exist and be populated via ChapterCleanerResult")
        assertTrue(cleanGroup.children?.isNotEmpty() == true, "Clean layer should be present inside clean group")
    }
}
