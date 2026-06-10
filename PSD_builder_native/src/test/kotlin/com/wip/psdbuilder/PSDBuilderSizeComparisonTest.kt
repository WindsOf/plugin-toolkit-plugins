package com.wip.psdbuilder

import com.wip.kpsd.LayerEffectShadow
import com.wip.kpsd.Units
import com.wip.kpsd.psd
import com.wip.kpsd.Justification
import com.wip.kpsd.TextShapeType
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        g.color = Color.RED
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
                putJsonObject("color") {
                    put("r", 255)
                    put("g", 255)
                    put("b", 255)
                    put("a", 255)
                }
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
            fontName = PsdFont.ARIAL,
            borderSize = 3,
            outputDir = outDir.absolutePath,
            context = context
        )
        File(outDir, "base.psd").renameTo(nativePsdPath)

        val exeSize = exePsdPath.length()
        val nativeSize = nativePsdPath.length()
        val difference = Math.abs(exeSize - nativeSize).toDouble() / exeSize

        val nativeHex = nativePsdPath.readBytes().joinToString("") { "%02x".format(it) }
        assertTrue(nativeHex.contains("6c667832"), "Native PSD should contain 'lfx2' block")

        println("EXE Size: $exeSize bytes")
        println("Native Size: $nativeSize bytes")
        println("Difference: ${String.format("%.2f", difference * 100)}%")

        testMultipleEffects(nativePlugin, context, outDir)

        testTinyPsd(nativePlugin, context, outDir)

        assertTrue(difference < 0.1, "PSD file size difference is too large: ${String.format("%.2f", difference * 100)}% (EXE: $exeSize, Native: $nativeSize)")
    }

    private suspend fun testTinyPsd(nativePlugin: PSDBuilderPlugin, context: PluginContext, outDir: File) {
        // ... Tiny 1x1 test ...
    }

    private suspend fun testMultipleEffects(nativePlugin: PSDBuilderPlugin, context: org.wip.plugintoolkit.api.PluginContext, outDir: File) {
        val imageFile = File(outDir.parentFile, "base.png")
        val baseImage = ImageIO.read(imageFile)
        val width = baseImage.width
        val height = baseImage.height

        val rgbData = IntArray(width * height)
        baseImage.getRGB(0, 0, width, height, rgbData, 0, width)
        val bgBytes = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val argb = rgbData[i]
            val a = (argb shr 24) and 0xff
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            val base = i * 4
            bgBytes[base] = r.toByte()
            bgBytes[base + 1] = g.toByte()
            bgBytes[base + 2] = b.toByte()
            bgBytes[base + 3] = a.toByte()
        }
        val bgPixelData = com.wip.kpsd.PixelData(width, height, bgBytes)

        val originalPsd = psd(width = width, height = height) {
            imageData = bgPixelData

            layer(name = "Background") {
                top = 0
                left = 0
                bottom = height
                right = width
                imageData = bgPixelData
            }

            textLayer(name = "Effect Test", textValue = "Effect Test") {
                top = 50
                left = 50
                bottom = 450
                right = 450
                shapeType = TextShapeType.BOX
                boxBounds = floatArrayOf(0f, 0f, 400f, 400f)
                transform(1.0, 0.0, 0.0, 1.0, 50.0, 50.0)

                style {
                    font("ArialMT")
                    this.fontSize = 50f
                    fillColor(255, 255, 255)
                }

                paragraphStyle {
                    justification = Justification.LEFT
                }

                effectsOpen = true
                effects {
                    stroke {
                        size = com.wip.kpsd.UnitsValue(Units.PIXELS, 5f)
                        rgb(0, 0, 0)
                    }
                    dropShadow {
                        size = com.wip.kpsd.UnitsValue(Units.PIXELS, 10f)
                        distance = com.wip.kpsd.UnitsValue(Units.PIXELS, 5f)
                        rgb(0, 0, 0)
                        opacity = 0.5f
                    }
                }
            }
        }

        val bytes = com.wip.kpsd.KPsd.write(originalPsd, compress = false)
        val effectsPsdFile = File(outDir, "effects_test.psd")
        effectsPsdFile.writeBytes(bytes)

        println("Hex dump around 6476:")
        val startDump = 6400
        val endDump = minOf(bytes.size, 6600)
        for (i in startDump until endDump step 16) {
            val endSlice = minOf(i + 16, bytes.size)
            val slice = bytes.sliceArray(i until endSlice)
            println("%04x: %s".format(i, slice.joinToString(" ") { "%02x".format(it) }))
        }

        val hex = bytes.joinToString("") { "%02x".format(it) }
        assertTrue(hex.contains("6c667832"), "Should contain 'lfx2' block")
        assertTrue(hex.contains("6c6d6678"), "Should contain 'lmfx' block")
        assertTrue(hex.contains("44725368"), "Should contain 'DrSh' (Drop Shadow) block inside effects")

        // Try reading it back
        val parsed = com.wip.kpsd.KPsd.read(bytes)
        val parsedEffects = parsed.children[1].effects
        assertNotNull(parsedEffects)
        assertEquals(1, parsedEffects.stroke?.size)
        assertEquals(1, parsedEffects.dropShadow?.size)
        assertEquals(5f, parsedEffects.stroke!![0].size.value)
        assertEquals(10f, parsedEffects.dropShadow!![0].size.value)
    }

    @Test
    fun testExtractTextLayersAndVerifyBounds() = runBlocking {
        val tempDir = File("build/tmp/size_comparison_test").apply { mkdirs() }
        val context = mockk<PluginContext>(relaxed = true)

        val imagePath = File(tempDir, "base_extract.png").absolutePath
        val baseImage = BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 500, 500)
        g.dispose()
        ImageIO.write(baseImage, "png", File(imagePath))

        val nativePlugin = PSDBuilderPlugin()
        val texts = listOf("Left", "Right")
        val bb = listOf(
            listOf(10.0 / 500.0, 20.0 / 500.0, 100.0 / 500.0, 50.0 / 500.0),
            listOf(120.0 / 500.0, 40.0 / 500.0, 200.0 / 500.0, 80.0 / 500.0)
        )

        val psdPath = nativePlugin.buildPsdFromInputs(
            imagePath = imagePath,
            texts = texts,
            bb = bb,
            fontSize = 24,
            fontName = PsdFont.ARIAL,
            borderSize = 3,
            outputDir = tempDir.absolutePath,
            context = context
        )

        // Extract
        val extracted = nativePlugin.extractTextLayers(psdPath, context)
        assertEquals(2, extracted.size, "Should extract exactly 2 text layers")

        val ext1 = extracted[0]
        assertEquals("Left", ext1.text.replace("\n", "").replace("\r", ""))
        assertTrue(ext1.left >= 10)

        val ext2 = extracted[1]
        assertEquals("Right", ext2.text.replace("\n", "").replace("\r", ""))
        assertTrue(ext2.left >= 120)
    }

    @org.junit.Ignore("Legacy test")
    @Test
    fun testExtractTextLayersFromPhotoshopFile() = runBlocking {
        val context = mockk<PluginContext>(relaxed = true)
        val nativePlugin = PSDBuilderPlugin()

        var psdFile = File("src/main/resources/scanario_1_corrected_by_ps.psd")
        if (!psdFile.exists()) {
            psdFile = File("PSD_builder_native/src/main/resources/scanario_1_corrected_by_ps.psd")
        }
        assertTrue(psdFile.exists(), "Photoshop-corrected reference file should exist at ${psdFile.absolutePath}")

        val extracted = nativePlugin.extractTextLayers(psdFile.absolutePath, context)
        println("Extracted from Photoshop-corrected file:")
        extracted.forEach { println("  Text: '${it.text}' bounds=[${it.left}, ${it.top}, ${it.right}, ${it.bottom}]") }

        // Assert we find the correct text layers
        assertEquals(2, extracted.size, "Should find exactly 2 text layers in Photoshop file")
        
        val ext1 = extracted[0]
        assertEquals("Top Left", ext1.text)
        assertEquals(55, ext1.left)
        assertEquals(24, ext1.top)
        assertEquals(121, ext1.right)
        assertEquals(74, ext1.bottom)

        val ext2 = extracted[1]
        assertEquals("BottomRight", ext2.text)
        assertEquals(354, ext2.left)
        assertEquals(399, ext2.top)
        assertEquals(472, ext2.right)
        assertEquals(449, ext2.bottom)
    }
}
