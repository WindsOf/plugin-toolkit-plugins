package com.wip.psdbuilder

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import org.wip.plugintoolkit.api.PluginFileSystem
import java.io.File
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class PSDBuilderPluginTest {

    @Test
    fun testNativeBuilderOutput() = runBlocking {
        val tempDir = File("build/tmp/test_psdbuilder_native").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val context = mockk<PluginContext>()
        val logger = mockk<PluginLogger>(relaxed = true)
        val progress = mockk<ProgressReporter>(relaxed = true)
        val fileSystem = mockk<PluginFileSystem>()

        every { context.logger } returns logger
        every { context.progress } returns progress
        every { context.fileSystem } returns fileSystem
        every { fileSystem.getBasePath() } returns tempDir.absolutePath

        val plugin = PSDBuilderPlugin()

        val imageFile = File(tempDir, "base_image.png")
        val width = 200
        val height = 200
        val baseImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(baseImage, "png", imageFile)

        val texts = listOf("Speech Bubble Text", "SFX BOOM")
        val bb = listOf(
            listOf(0.1, 0.1, 0.5, 0.4),
            listOf(0.6, 0.7, 0.9, 0.9)
        )
        val fontName = PsdFont.ARIAL
        val fontSize = 24
        val borderSize = 3
        val shapes = listOf("oval", "rectangular")

        val nativeOutputPath = plugin.buildPsdFromInputs(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = borderSize,
            outputDir = tempDir.absolutePath,
            shapes = shapes,
            context = context
        )

        val outputFile = File(nativeOutputPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
        assertTrue(outputFile.length() > 0, "Output PSD should not be empty")
    }

    @Test
    fun testDifferentShapes() = runBlocking {
        val tempDir = File("build/tmp/test_psdbuilder_native_shapes").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val context = mockk<PluginContext>()
        every { context.logger } returns mockk(relaxed = true)
        every { context.progress } returns mockk(relaxed = true)
        every { context.fileSystem } returns mockk {
            every { getBasePath() } returns tempDir.absolutePath
        }

        val plugin = PSDBuilderPlugin()

        val imageFile = File(tempDir, "base_image_shapes.png")
        val baseImage = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(baseImage, "png", imageFile)

        val texts = listOf("A very long text to test oval wrapping", "Another very long text to test rectangular wrapping", "Short text")
        val bb = listOf(
            listOf(0.1, 0.1, 0.4, 0.4),
            listOf(0.5, 0.1, 0.9, 0.4),
            listOf(0.1, 0.6, 0.4, 0.9)
        )
        val shapes = listOf("oval", "rectangular", "unknown_shape")

        val nativeOutputPath = plugin.buildPsdFromInputs(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = 24,
            fontName = PsdFont.ARIAL,
            borderSize = 3,
            outputDir = tempDir.absolutePath,
            shapes = shapes,
            context = context
        )

        val outputFile = File(nativeOutputPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
    }

    @Test
    fun testBigBalloonCurvatureAndRectangle() = runBlocking {
        val tempDir = File("build/tmp/test_psdbuilder_native_big_balloon").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val context = mockk<PluginContext>()
        every { context.logger } returns mockk(relaxed = true)
        every { context.progress } returns mockk(relaxed = true)
        every { context.fileSystem } returns mockk {
            every { getBasePath() } returns tempDir.absolutePath
        }

        val plugin = PSDBuilderPlugin()

        val imageFile = File(tempDir, "base_image_big_balloon.png")
        val width = 1000
        val height = 1000

        val heavyText = "This is a very long paragraph intended to test the curvature of a big balloon. " +
                "When placed inside an oval shape, the text should naturally wrap inwards at the top and bottom edges, " +
                "creating a distinct curved formatting that resembles a comic speech bubble."

        val texts = listOf(heavyText, heavyText, heavyText)
        val bb = listOf(
            listOf(0.1, 0.05, 0.9, 0.30),
            listOf(0.1, 0.35, 0.9, 0.60),
            listOf(0.35, 0.65, 0.65, 0.95)
        )
        val shapes = listOf("oval", "rectangular", "circle")

        val baseImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.RED
        for (i in bb.indices) {
            val box = bb[i]
            val shape = shapes[i]
            val x = (box[0] * width).toInt()
            val y = (box[1] * height).toInt()
            val w = ((box[2] - box[0]) * width).toInt()
            val h = ((box[3] - box[1]) * height).toInt()
            if (shape == "oval" || shape == "circle") {
                g.drawOval(x, y, w, h)
            } else {
                g.drawRect(x, y, w, h)
            }
        }
        g.dispose()
        ImageIO.write(baseImage, "png", imageFile)

        val nativeOutputPath = plugin.buildPsdFromInputs(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = 32,
            fontName = PsdFont.ARIAL,
            borderSize = 3,
            outputDir = tempDir.absolutePath,
            shapes = shapes,
            context = context
        )

        val outputFile = File(nativeOutputPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
    }

    @Test
    fun testVerticalAlignment() = runBlocking {
        val tempDir = File("build/tmp/test_psdbuilder_native_vertical_alignment").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val context = mockk<PluginContext>()
        every { context.logger } returns mockk(relaxed = true)
        every { context.fileSystem } returns mockk {
            every { getBasePath() } returns tempDir.absolutePath
        }

        val plugin = PSDBuilderPlugin()
        val imageFile = File(tempDir, "base_image_vertical.png")
        val baseImage = BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(baseImage, "png", imageFile)

        val texts = listOf("Short text")
        val bb = listOf(listOf(0.1, 0.1, 0.4, 0.4))
        
        val nativeOutputPath = plugin.buildPsdFromInputs(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = 24,
            fontName = PsdFont.ARIAL,
            borderSize = 3,
            outputDir = tempDir.absolutePath,
            shapes = listOf("oval"),
            context = context
        )

        assertTrue(File(nativeOutputPath).exists(), "Output PSD should exist")
    }
}
