package com.wip.psdbuilder

import com.wip.ocrAI.models.OCRResult
import com.wip.ocrAI.models.AdvancedOCRResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import java.io.File
import kotlin.test.assertTrue

class OcrDataTest {

    @Test
    fun testBuildPsdFromOcrData() = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)

        val tempDir = File("build/tmp/test_psdbuilder_native_ocrdata").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val imageFile = File(tempDir, "base_image.png")
        val width = 200
        val height = 200
        val baseImage = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = java.awt.Color.BLUE
        g.fillRect(0, 0, width, height)
        g.dispose()
        javax.imageio.ImageIO.write(baseImage, "png", imageFile)

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
            ocrData = ocrResult,
            outputDir = tempDir.absolutePath,
            context = context,
            hostFs = io.mockk.mockk(relaxed = true)
        )

        val outputFile = File(result.psdPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
        assertTrue(outputFile.length() > 0, "Output PSD should not be empty")
        
        println("Test passed: PSD generated using OCRData natively successfully!")
    }

    @Test
    fun testBuildPsdFromAdvancedOcrData() = runBlocking {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = false))
        val context = io.mockk.mockk<PluginContext>(relaxed = true)

        val tempDir = File("build/tmp/test_psdbuilder_native_advancedocrdata").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val imageFile = File(tempDir, "base_image.png")
        val width = 200
        val height = 200
        val baseImage = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = java.awt.Color.GREEN
        g.fillRect(0, 0, width, height)
        g.dispose()
        javax.imageio.ImageIO.write(baseImage, "png", imageFile)

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
            hostFs = io.mockk.mockk(relaxed = true)
        )

        val outputFile = File(result.psdPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
        assertTrue(outputFile.length() > 0, "Output PSD should not be empty")

        println("Test passed: PSD generated using AdvancedOCRData natively successfully!")
    }
}
