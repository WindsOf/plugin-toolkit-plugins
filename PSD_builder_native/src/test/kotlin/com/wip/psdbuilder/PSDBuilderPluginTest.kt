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
        // 1. Create a temporary test directory
        val tempDir = File("build/tmp/test_psdbuilder_native").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        // 2. Mock PluginContext and its components
        val context = mockk<PluginContext>()
        val logger = mockk<PluginLogger>(relaxed = true)
        val progress = mockk<ProgressReporter>(relaxed = true)
        val fileSystem = mockk<PluginFileSystem>()

        every { context.logger } returns logger
        every { context.progress } returns progress
        every { context.fileSystem } returns fileSystem

        // Mock getBasePath() to return our temp test directory
        every { fileSystem.getBasePath() } returns tempDir.absolutePath

        // 3. Initialize plugin
        val plugin = PSDBuilderPlugin()

        // 4. Create a dummy base image
        val imageFile = File(tempDir, "base_image.png")
        val width = 200
        val height = 200
        val baseImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(baseImage, "png", imageFile)

        // 5. Setup parameters
        val texts = listOf("Speech Bubble Text", "SFX BOOM")
        val bb = listOf(
            listOf(0.1, 0.1, 0.5, 0.4),
            listOf(0.6, 0.7, 0.9, 0.9)
        )
        val fontName = "ArialMT"
        val fontSize = 24
        val borderSize = 3

        // 6. Run native (Kotlin-based) builder
        val nativeOutputPath = plugin.buildPsdFromInputs(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = fontSize,
            fontName = fontName,
            borderSize = borderSize,
            outputDir = tempDir.absolutePath,
            context = context
        )

        val outputFile = File(nativeOutputPath)
        assertTrue(outputFile.exists(), "Output PSD should exist")
        assertTrue(outputFile.length() > 0, "Output PSD should not be empty")

        println("Test passed: PSD generated natively successfully!")
    }
}
