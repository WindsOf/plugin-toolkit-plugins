package com.wip.psdbuilder

import io.mockk.*
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
import com.wip.kpsd.KPsd
import kotlinx.serialization.json.*

/**
 * This test ensures that the Native implementation in PSD_builder_native 
 * matches the Executable implementation in PSD_builder.
 */
class PSDBuilderCrossModuleTest {

    @Test
    fun testNativeMatchesExecutableAcrossScenarios() = runBlocking {
        val tempDir = File("build/tmp/cross_module_test").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val context = mockk<PluginContext>(relaxed = true)
        val fileSystem = mockk<PluginFileSystem>()
        every { context.fileSystem } returns fileSystem
        every { fileSystem.getBasePath() } returns tempDir.absolutePath

        // Copy EXE from PSD_builder module to tempDir for comparison
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

        // 2. Define test scenarios: various positions, amounts of text, and rotations
        data class Scenario(
            val texts: List<String>,
            val bb: List<List<Double>>,
            val rotations: List<Double>? = null
        )

        val scenarios = listOf(
            // Scenario 0: Single centered bubble
            Scenario(listOf("Center Text"), listOf(listOf(0.2, 0.4, 0.8, 0.6))),
            // Scenario 1: Multiple bubbles in corners
            Scenario(
                listOf("Top Left", "Bottom Right"),
                listOf(
                    listOf(0.05, 0.05, 0.3, 0.2),
                    listOf(0.7, 0.8, 0.95, 0.95)
                )
            ),
            // Scenario 2: Long text that should wrap
            Scenario(
                listOf("This is a very long piece of text that should definitely trigger the wrapping logic in both implementations to see if they behave the same way."),
                listOf(listOf(0.1, 0.1, 0.9, 0.3))
            ),
            // Scenario 3: Rotated text layers
            Scenario(
                listOf("Rotated Text 30", "Rotated Text -45"),
                listOf(
                    listOf(0.15, 0.15, 0.45, 0.35),
                    listOf(0.55, 0.55, 0.85, 0.75)
                ),
                listOf(30.0, -45.0)
            )
        )

        scenarios.forEachIndexed { index, scenario ->
            println("Running scenario ${index + 1}...")
            val texts = scenario.texts
            val bb = scenario.bb
            val rotations = scenario.rotations
            
            val outDir = File(tempDir, "scenario_$index").apply { mkdirs() }
            val exePsdPath = File(outDir, "exe_output.psd").absolutePath
            val nativePsdPath = File(outDir, "native_output.psd").absolutePath

            // --- "EXE" Implementation (Manual call to PSD_builder.exe) ---
            val psdTexts = texts.indices.map { idx ->
                val text = texts[idx]
                val box = bb[idx]
                val left = (box[0] * 500).toInt()
                val top = (box[1] * 500).toInt()
                val right = (box[2] * 500).toInt()
                val bottom = (box[3] * 500).toInt()
                val rot = rotations?.getOrNull(idx)
                
                buildJsonObject {
                    put("text", text)
                    put("left", left)
                    put("top", top)
                    put("right", right)
                    put("bottom", bottom)
                    put("fontName", "ArialMT")
                    put("fontSize", 24)
                    put("strokeSize", 3)
                    if (rot != null) {
                        put("rotation", rot)
                    }
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

            val process = ProcessBuilder(exeDest.absolutePath, jsonFile.absolutePath, exePsdPath)
                .directory(tempDir)
                .start()
            assertEquals(0, process.waitFor(), "EXE execution failed for scenario $index")

            // --- Native Implementation ---
            nativePlugin.buildPsdFromInputs(
                imagePath = imageFile.absolutePath,
                texts = texts,
                bb = bb,
                fontSize = 24,
                fontName = "ArialMT",
                borderSize = 3,
                outputDir = outDir.absolutePath,
                rotations = rotations,
                context = context
            )
            // The plugin names it based on image name
            File(outDir, "base.psd").renameTo(File(nativePsdPath))

            // --- Comparison ---
            val exePsd = KPsd.read(File(exePsdPath).readBytes())
            val nativePsd = KPsd.read(File(nativePsdPath).readBytes())

            println("Comparing Scenario $index: EXE width=${exePsd.width}, Native width=${nativePsd.width}")
            assertEquals(exePsd.width, nativePsd.width, "Width mismatch in scenario $index")
            assertEquals(exePsd.height, nativePsd.height, "Height mismatch in scenario $index")
            assertEquals(exePsd.children.size, nativePsd.children.size, "Layer count mismatch in scenario $index")
            
            for (i in exePsd.children.indices) {
                val eL = exePsd.children[i]
                val nL = nativePsd.children[i]
                
                println("  Layer $i: EXE name=${eL.name}, Native name=${nL.name}")
                assertEquals(eL.name, nL.name, "Layer $i name mismatch in scenario $index")
                if (i > 0) {
                    assertEquals(texts[i - 1], eL.name, "EXE Layer $i name should be equal to its text content")
                    assertEquals(texts[i - 1], nL.name, "Native Layer $i name should be equal to its text content")
                }
                assertEquals(eL.top, nL.top, "Layer $i top mismatch in scenario $index")
                assertEquals(eL.left, nL.left, "Layer $i left mismatch in scenario $index")
                
                if (eL.text != null) {
                    assertNotNull(nL.text, "Layer $i should be text in both (scenario $index)")
                    // Normalize text comparison (line endings and extra spaces from wrapping)
                    val eText = eL.text!!.text.trim().replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ")
                    val nText = nL.text!!.text.trim().replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ")
                    println("    EXE text: [$eText], Native text: [$nText]")
                    assertEquals(eText, nText, "Layer $i text content mismatch in scenario $index")
                    
                    assertNotNull(eL.text!!.boxBounds, "Layer $i EXE boxBounds should not be null")
                    assertNotNull(nL.text!!.boxBounds, "Layer $i Native boxBounds should not be null")
                    assertEquals(eL.text!!.boxBounds!!.size, nL.text!!.boxBounds!!.size, "Layer $i boxBounds size mismatch")
                    for (idx in eL.text!!.boxBounds!!.indices) {
                        assertEquals(eL.text!!.boxBounds!![idx], nL.text!!.boxBounds!![idx], 1e-4f, "Layer $i boxBounds[$idx] mismatch")
                    }

                    assertNotNull(eL.text!!.transform, "Layer $i EXE transform should not be null")
                    assertNotNull(nL.text!!.transform, "Layer $i Native transform should not be null")
                    assertEquals(eL.text!!.transform!!.size, nL.text!!.transform!!.size, "Layer $i transform size mismatch")
                    for (idx in eL.text!!.transform!!.indices) {
                        assertEquals(eL.text!!.transform!![idx], nL.text!!.transform!![idx], 1e-4, "Layer $i transform[$idx] mismatch")
                    }
                }
            }
        }
        
        println("Cross-module validation passed for all scenarios!")
    }
}
