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
            fontName = "ArialMT",
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
        // Create a PSD with both stroke and drop shadow
        val originalPsd = nativePlugin.buildPsdObject(
            imagePath = File(outDir.parentFile, "base.png").absolutePath,
            texts = listOf("Effect Test"),
            bb = listOf(listOf(0.1, 0.1, 0.9, 0.9)),
            fontSize = 50,
            fontName = "ArialMT",
            borderSize = 5,
            context = context
        )

        // Manually add a shadow
        val textLayer = originalPsd.children[1]
        textLayer.effects?.let { effects ->
            effects.dropShadow = listOf(
                com.wip.kpsd.LayerEffectShadow(
                    size = com.wip.kpsd.UnitsValue("Pixels", 10f),
                    distance = com.wip.kpsd.UnitsValue("Pixels", 5f),
                    color = com.wip.kpsd.Rgb(0, 0, 0),
                    opacity = 0.5f
                )
            )
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
    fun testFolderWithImageAndText() = runBlocking {
        val tempDir = File("build/tmp/size_comparison_test").apply { mkdirs() }
        val context = mockk<PluginContext>(relaxed = true)

        val imagePath = File(tempDir, "base_folder.png").absolutePath
        val baseImage = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
        val g = baseImage.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 200, 200)
        g.dispose()
        ImageIO.write(baseImage, "png", File(imagePath))

        // Create a custom image layer: 50x50 pixels, filled with green
        val imgBytes = ByteArray(50 * 50 * 4) { i ->
            when (i % 4) {
                0 -> 0.toByte()      // R
                1 -> 255.toByte()    // G
                2 -> 0.toByte()      // B
                else -> 255.toByte()  // A
            }
        }
        val imgLayer = com.wip.kpsd.Layer(
            name = "Image Layer",
            top = 10,
            left = 10,
            bottom = 60,
            right = 60,
            imageData = com.wip.kpsd.PixelData(50, 50, imgBytes)
        )

        // Create a text layer
        val textLayer = com.wip.kpsd.Layer(
            name = "My Text Layer",
            top = 80,
            left = 10,
            bottom = 120,
            right = 190,
            text = com.wip.kpsd.LayerTextData(
                text = "Hello Inside Folder",
                shapeType = "box",
                boxBounds = floatArrayOf(0f, 0f, 180f, 40f),
                transform = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 10.0, 80.0),
                left = 0f,
                top = 0f,
                right = 180f,
                bottom = 40f,
                style = com.wip.kpsd.TextStyle(
                    font = com.wip.kpsd.Font(name = "AnimeAce2.0BB"),
                    fontSize = 20f,
                    fillColor = com.wip.kpsd.Rgb(255, 255, 255)
                )
            )
        )

        // Create an open folder group containing both layers
        val folderLayer = com.wip.kpsd.Layer(
            name = "My Group Folder",
            opened = true,
            children = mutableListOf(imgLayer, textLayer)
        )

        // Create base background layer
        val bgBytes = ByteArray(200 * 200 * 4)
        val bgPixelData = com.wip.kpsd.PixelData(200, 200, bgBytes)
        val bgLayer = com.wip.kpsd.Layer(
            name = "Background",
            top = 0,
            left = 0,
            bottom = 200,
            right = 200,
            imageData = bgPixelData
        )

        // Assemble PSD
        val psd = com.wip.kpsd.Psd(
            width = 200,
            height = 200,
            children = mutableListOf(bgLayer, folderLayer),
            imageData = bgPixelData
        )

        // Write
        val psdBytes = com.wip.kpsd.KPsd.write(psd, compress = false)
        val psdFile = File(tempDir, "folder_img_text.psd")
        psdFile.writeBytes(psdBytes)

        // Read back and assert hierarchy
        val parsed = com.wip.kpsd.KPsd.read(psdBytes)
        assertEquals(2, parsed.children.size, "PSD should have 2 root layers (Background and Folder)")
        
        val parsedBg = parsed.children[0]
        assertEquals("Background", parsedBg.name)

        val parsedFolder = parsed.children[1]
        assertEquals("My Group Folder", parsedFolder.name)
        assertNotNull(parsedFolder.children)
        assertEquals(2, parsedFolder.children!!.size, "Folder should contain 2 child layers")

        val parsedImg = parsedFolder.children!![0]
        assertEquals("Image Layer", parsedImg.name)
        assertNotNull(parsedImg.imageData)
        assertEquals(50, parsedImg.imageData!!.width)
        assertEquals(50, parsedImg.imageData!!.height)

        val parsedText = parsedFolder.children!![1]
        assertEquals("My Text Layer", parsedText.name)
        assertNotNull(parsedText.text)
        assertEquals("Hello Inside Folder", parsedText.text!!.text)
    }

    @Test
    fun testFolderWithEffects() = runBlocking {
        val tempDir = File("build/tmp/size_comparison_test").apply { mkdirs() }

        // Create a text layer with shadow and stroke effects
        val textLayer = com.wip.kpsd.Layer(
            name = "Effect Text Layer",
            top = 20,
            left = 20,
            bottom = 80,
            right = 180,
            text = com.wip.kpsd.LayerTextData(
                text = "Shadow & Stroke",
                shapeType = "box",
                boxBounds = floatArrayOf(0f, 0f, 160f, 60f),
                transform = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 20.0, 20.0),
                left = 0f,
                top = 0f,
                right = 160f,
                bottom = 60f,
                style = com.wip.kpsd.TextStyle(
                    font = com.wip.kpsd.Font(name = "AnimeAce2.0BB"),
                    fontSize = 24f,
                    fillColor = com.wip.kpsd.Rgb(255, 255, 255)
                )
            ),
            effects = com.wip.kpsd.LayerEffectsInfo(
                scale = 1f,
                stroke = listOf(
                    com.wip.kpsd.LayerEffectStroke(
                        size = com.wip.kpsd.UnitsValue("Pixels", 4f),
                        color = com.wip.kpsd.Rgb(0, 0, 0)
                    )
                ),
                dropShadow = listOf(
                    com.wip.kpsd.LayerEffectShadow(
                        size = com.wip.kpsd.UnitsValue("Pixels", 8f),
                        distance = com.wip.kpsd.UnitsValue("Pixels", 6f),
                        color = com.wip.kpsd.Rgb(0, 0, 0),
                        opacity = 0.6f
                    )
                )
            ),
            effectsOpen = true
        )

        // Create folder containing the text layer
        val folderLayer = com.wip.kpsd.Layer(
            name = "Folder with Effects",
            opened = true,
            children = mutableListOf(textLayer)
        )

        // Create base background layer
        val bgBytes = ByteArray(200 * 200 * 4)
        val bgPixelData = com.wip.kpsd.PixelData(200, 200, bgBytes)
        val bgLayer = com.wip.kpsd.Layer(
            name = "Background",
            top = 0,
            left = 0,
            bottom = 200,
            right = 200,
            imageData = bgPixelData
        )

        // Assemble PSD
        val psd = com.wip.kpsd.Psd(
            width = 200,
            height = 200,
            children = mutableListOf(bgLayer, folderLayer),
            imageData = bgPixelData
        )

        // Write
        val psdBytes = com.wip.kpsd.KPsd.write(psd, compress = false)
        val psdFile = File(tempDir, "folder_effects.psd")
        psdFile.writeBytes(psdBytes)

        // Read back and assert
        val parsed = com.wip.kpsd.KPsd.read(psdBytes)
        assertEquals(2, parsed.children.size)

        val parsedFolder = parsed.children[1]
        assertEquals("Folder with Effects", parsedFolder.name)
        assertNotNull(parsedFolder.children)
        assertEquals(1, parsedFolder.children!!.size)

        val parsedTextLayer = parsedFolder.children!![0]
        assertEquals("Effect Text Layer", parsedTextLayer.name)
        
        val parsedEffects = parsedTextLayer.effects
        assertNotNull(parsedEffects)
        assertNotNull(parsedEffects.stroke)
        assertEquals(1, parsedEffects.stroke!!.size)
        assertEquals(4f, parsedEffects.stroke!![0].size.value)

        assertNotNull(parsedEffects.dropShadow)
        assertEquals(1, parsedEffects.dropShadow!!.size)
        assertEquals(8f, parsedEffects.dropShadow!![0].size.value)
        assertEquals(6f, parsedEffects.dropShadow!![0].distance.value)
    }

    @Test
    fun testEffectsOnFolder() = runBlocking {
        val tempDir = File("build/tmp/size_comparison_test").apply { mkdirs() }

        // Create a simple text layer
        val textLayer = com.wip.kpsd.Layer(
            name = "Child Layer",
            top = 20,
            left = 20,
            bottom = 80,
            right = 180,
            text = com.wip.kpsd.LayerTextData(
                text = "Inside Folder",
                shapeType = "box",
                boxBounds = floatArrayOf(0f, 0f, 160f, 60f),
                transform = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 20.0, 20.0),
                left = 0f,
                top = 0f,
                right = 160f,
                bottom = 60f,
                style = com.wip.kpsd.TextStyle(
                    font = com.wip.kpsd.Font(name = "AnimeAce2.0BB"),
                    fontSize = 24f,
                    fillColor = com.wip.kpsd.Rgb(255, 255, 255)
                )
            )
        )

        // Create folder containing the text layer, and apply effects to the FOLDER
        val folderLayer = com.wip.kpsd.Layer(
            name = "Folder with Effects Applied",
            opened = true,
            children = mutableListOf(textLayer),
            effects = com.wip.kpsd.LayerEffectsInfo(
                scale = 1f,
                stroke = listOf(
                    com.wip.kpsd.LayerEffectStroke(
                        size = com.wip.kpsd.UnitsValue("Pixels", 5f),
                        color = com.wip.kpsd.Rgb(0, 0, 0)
                    )
                ),
                dropShadow = listOf(
                    com.wip.kpsd.LayerEffectShadow(
                        size = com.wip.kpsd.UnitsValue("Pixels", 12f),
                        distance = com.wip.kpsd.UnitsValue("Pixels", 4f),
                        color = com.wip.kpsd.Rgb(0, 0, 0),
                        opacity = 0.5f
                    )
                )
            ),
            effectsOpen = true
        )

        // Create base background layer
        val bgBytes = ByteArray(200 * 200 * 4)
        val bgPixelData = com.wip.kpsd.PixelData(200, 200, bgBytes)
        val bgLayer = com.wip.kpsd.Layer(
            name = "Background",
            top = 0,
            left = 0,
            bottom = 200,
            right = 200,
            imageData = bgPixelData
        )

        // Assemble PSD
        val psd = com.wip.kpsd.Psd(
            width = 200,
            height = 200,
            children = mutableListOf(bgLayer, folderLayer),
            imageData = bgPixelData
        )

        // Write
        val psdBytes = com.wip.kpsd.KPsd.write(psd, compress = false)
        val psdFile = File(tempDir, "folder_with_effects_applied.psd")
        psdFile.writeBytes(psdBytes)

        // Read back and assert
        val parsed = com.wip.kpsd.KPsd.read(psdBytes)
        assertEquals(2, parsed.children.size)

        val parsedFolder = parsed.children[1]
        assertEquals("Folder with Effects Applied", parsedFolder.name)
        assertNotNull(parsedFolder.children)
        assertEquals(1, parsedFolder.children!!.size)

        // Verify folder layer itself has the effects
        val parsedEffects = parsedFolder.effects
        assertNotNull(parsedEffects, "Folder layer should have effects parsed back")
        assertNotNull(parsedEffects.stroke)
        assertEquals(1, parsedEffects.stroke!!.size)
        assertEquals(5f, parsedEffects.stroke!![0].size.value)

        assertNotNull(parsedEffects.dropShadow)
        assertEquals(1, parsedEffects.dropShadow!!.size)
        assertEquals(12f, parsedEffects.dropShadow!![0].size.value)
        assertEquals(4f, parsedEffects.dropShadow!![0].distance.value)

        // Verify child layer does not have effects
        val parsedChild = parsedFolder.children!![0]
        assertEquals("Child Layer", parsedChild.name)
        assertTrue(parsedChild.effects == null || (parsedChild.effects!!.stroke == null && parsedChild.effects!!.dropShadow == null))
    }
}
