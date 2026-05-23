package com.wip.psdbuilder

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginFileSystem
import java.io.File
import java.io.FileNotFoundException
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class PSDBuilderSizeComparisonTest {

    @Test
    fun testPsdSizeDifference() = runBlocking {
        val tempDir = File("build/tmp/size_comparison_test").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val context = mockk<PluginContext>(relaxed = true)
        val fileSystem = mockk<PluginFileSystem>()
        every { context.fileSystem } returns fileSystem
        every { fileSystem.getBasePath() } returns tempDir.absolutePath

        val exeSource = File("../PSD_builder/src/main/resources/tools/PSD_builder.exe")
        val exeDest = File(tempDir, "PSD_builder.exe")
        if (!exeSource.exists()) {
            throw FileNotFoundException("Could not find PSD_builder.exe at ${exeSource.absolutePath}")
        }
        exeSource.copyTo(exeDest, overwrite = true)

        val nativePlugin = PSDBuilderPlugin()

        // 1. Create a base image
        val imageFile = File(tempDir, "base.png")
        val baseImage = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 500, 500)
        g.dispose()
        ImageIO.write(baseImage, "png", imageFile)

        // Scenario: A few text bubbles
        val texts = listOf("Header", "Main Content", "Footer")
        val bb = listOf(
            listOf(0.1, 0.1, 0.9, 0.2),
            listOf(0.1, 0.3, 0.9, 0.7),
            listOf(0.1, 0.8, 0.9, 0.9)
        )

        val outDir = File(tempDir, "output").apply { mkdirs() }
        val exePsdPath = File(outDir, "exe_output.psd")
        val nativePsdPath = File(outDir, "native_output.psd")

        // --- EXE Implementation ---
        val psdTexts = texts.zip(bb).map { (text, box) ->
            buildJsonObject {
                put("text", text)
                put("left", (box[0] * 500).toInt())
                put("top", (box[1] * 500).toInt())
                put("right", (box[2] * 500).toInt())
                put("bottom", (box[3] * 500).toInt())
                put("fontName", "ArialMT")
                put("fontSize", 24)
                put("strokeSize", 3)
            }
        }
        val payload = buildJsonObject {
            put("backgroundImage", imageFile.absolutePath)
            put("texts", JsonArray(psdTexts))
        }
        val jsonFile = File(outDir, "payload.json")
        jsonFile.writeText(payload.toString())

        val process = ProcessBuilder(exeDest.absolutePath, jsonFile.absolutePath, exePsdPath.absolutePath)
            .directory(tempDir)
            .start()
        process.waitFor()

        // --- Native Implementation ---
        nativePlugin.buildPsdFromInputs(
            imagePath = imageFile.absolutePath,
            texts = texts,
            bb = bb,
            fontSize = 24,
            fontName = "ArialMT",
            borderSize = 3,
            outputDir = outDir.absolutePath,
            context = context
        )
        File(outDir, "base.psd").renameTo(nativePsdPath)

        val exeSize = exePsdPath.length()
        val nativeSize = nativePsdPath.length()
        val difference = Math.abs(exeSize - nativeSize).toDouble() / exeSize

        println("EXE Size: $exeSize bytes")
        println("Native Size: $nativeSize bytes")
        println("Difference: ${String.format("%.2f", difference * 100)}%")

        assertTrue(difference < 0.1, "PSD file size difference is too large: ${String.format("%.2f", difference * 100)}% (EXE: $exeSize, Native: $nativeSize)")
    }
}
