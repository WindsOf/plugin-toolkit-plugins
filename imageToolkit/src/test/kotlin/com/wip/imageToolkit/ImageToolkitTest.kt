package com.wip.imageToolkit

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.ProgressReporter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class ImageToolkitTest {

    @Test
    fun testAddTextToImage() = runBlocking {
        // 1. Create a dummy image
        val tempDir = java.nio.file.Files.createTempDirectory("test_image_toolkit").toFile().apply { deleteOnExit() }
        val imageFile = File(tempDir, "test_image.png")
        val bufferedImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = bufferedImage.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 100, 100)
        g.dispose()
        ImageIO.write(bufferedImage, "png", imageFile)

        // 2. Mock PluginContext
        val context = mockk<PluginContext>()
        val logger = mockk<PluginLogger>(relaxed = true)
        val progress = mockk<ProgressReporter>(relaxed = true)
        every { context.logger } returns logger
        every { context.progress } returns progress

        // 3. Call the capability
        val toolkit = ImageToolkit()
        val texts = listOf("Hello World")
        val bb = listOf(listOf(0.1, 0.1, 0.9, 0.9)) // centered box
        
        val resultPath = toolkit.addTextToImage(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = 12,
            fontName = "Helvetica",
            pageNumber = 1,
            pageName = "test_image.png",
            context = context,
            hostFs = io.mockk.mockk(relaxed = true)
        )

        // 4. Verify result
        val resultFile = File(resultPath)
        assertTrue(resultFile.exists(), "Output PDF should exist")
        assertTrue(resultFile.name.endsWith("_layered.pdf"), "Output file should be a PDF")
        
        println("Generated PDF at: ${resultFile.absolutePath}")
    }

    @Test
    fun testAddTextToChapter() = runBlocking {
        // 1. Create a dummy image in a folder
        val tempDir = java.nio.file.Files.createTempDirectory("test_chapter").toFile().apply { deleteOnExit() }
        val imageFile = File(tempDir, "page1.png")
        val bufferedImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = bufferedImage.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 100, 100)
        g.dispose()
        ImageIO.write(bufferedImage, "png", imageFile)

        // 2. Mock PluginContext
        val context = mockk<PluginContext>()
        val logger = mockk<PluginLogger>(relaxed = true)
        val progress = mockk<ProgressReporter>(relaxed = true)
        every { context.logger } returns logger
        every { context.progress } returns progress

        // 3. Call the capability
        val toolkit = ImageToolkit()
        val texts = listOf("Page 1 Text")
        val bb = listOf(listOf(0.1, 0.1, 0.9, 0.9))
        val pageNames = listOf("page1.png")
        
        val resultDirPath = toolkit.addTextToChapter(
            inputFolder = tempDir.absolutePath,
            texts = texts,
            bb = bb,
            pageNames = pageNames,
            outputDir = File(tempDir, "output").absolutePath,
            fontSize = 12,
            fontName = "Helvetica",
            context = context,
            hostFs = io.mockk.mockk(relaxed = true)
        )

        // 4. Verify result
        val resultDir = File(resultDirPath)
        assertTrue(resultDir.exists(), "Output directory should exist")
        val pdfFile = File(resultDir, "page1.pdf")
        assertTrue(pdfFile.exists(), "Output PDF for page1 should exist")
        
        println("Generated Chapter PDFs at: ${resultDir.absolutePath}")
    }
}
