package com.wip.psdbuilder

import org.junit.Test
import java.io.File
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlin.math.pow

class Test2Outputs {
    @Test
    fun testAllImagesInTest2() {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = true))
        val ctx = io.mockk.mockk<org.wip.plugintoolkit.api.PluginContext>(relaxed = true)

        val test2Dir = File("src/main/resources/test2")
        val outputDir = File("build/tmp/psd_builder_test_outputs")
        if (!outputDir.exists()) outputDir.mkdirs()

        val jsonFiles = test2Dir.listFiles { _, name -> name.endsWith("_OCR.json") } ?: emptyArray()

        for (jsonFile in jsonFiles) {
            val baseName = jsonFile.name.substringBefore("_OCR.json")
            val imageFile = File(test2Dir, "$baseName.webp")
            if (!imageFile.exists()) {
                println("Image $imageFile not found for $jsonFile")
                continue
            }

            println("Processing $baseName")

            val baseImage = javax.imageio.ImageIO.read(imageFile)
            val imgWidth = baseImage.width
            val imgHeight = baseImage.height

            // Use clean image if available
            val cleanImageFile = File(test2Dir, "${baseName}_clean.webp")
            val imageToUse = if (cleanImageFile.exists()) cleanImageFile else imageFile

            // Parse OCR JSON
            val rawContent = jsonFile.readText()
            val jsonContent = rawContent.substringAfter("```json\n").substringBefore("\n```").trim()
            val jsonObj = Json.parseToJsonElement(jsonContent).jsonObject
            val balloons = jsonObj["balloons"]?.jsonArray ?: emptyList()

            val texts = mutableListOf<String>()
            val balloonBoxes = mutableListOf<List<Double>>()
            val textBoxes = mutableListOf<List<Double>>()
            val shapes = mutableListOf<String>()

            for (b in balloons) {
                val bObj = b.jsonObject
                texts.add(bObj["text"]?.jsonPrimitive?.content ?: "")
                
                val tBoxArrVal = bObj["text_box_2d"]?.jsonArray
                if (tBoxArrVal != null) {
                    var ymin = tBoxArrVal[0].jsonPrimitive.double / 1000.0 * imgHeight
                    var xmin = tBoxArrVal[1].jsonPrimitive.double / 1000.0 * imgWidth
                    var ymax = tBoxArrVal[2].jsonPrimitive.double / 1000.0 * imgHeight
                    var xmax = tBoxArrVal[3].jsonPrimitive.double / 1000.0 * imgWidth
                    
                    // Hack to prevent PSDBuilderPlugin.toAbs from misinterpreting absolute pixels near 0 as normalized coordinates
                    if (ymin in 0.0..1.0) ymin = 2.0
                    if (xmin in 0.0..1.0) xmin = 2.0
                    if (ymax in 0.0..1.0) ymax = 2.0
                    if (xmax in 0.0..1.0) xmax = 2.0

                    textBoxes.add(listOf(ymin, xmin, ymax, xmax))
                } else {
                    textBoxes.add(emptyList())
                }
                
                val bBoxArrVal = bObj["balloon_box_2d"]?.jsonArray
                if (bBoxArrVal != null) {
                    var ymin = bBoxArrVal[0].jsonPrimitive.double / 1000.0 * imgHeight
                    var xmin = bBoxArrVal[1].jsonPrimitive.double / 1000.0 * imgWidth
                    var ymax = bBoxArrVal[2].jsonPrimitive.double / 1000.0 * imgHeight
                    var xmax = bBoxArrVal[3].jsonPrimitive.double / 1000.0 * imgWidth
                    
                    // Hack to prevent PSDBuilderPlugin.toAbs from misinterpreting absolute pixels near 0 as normalized coordinates
                    if (ymin in 0.0..1.0) ymin = 2.0
                    if (xmin in 0.0..1.0) xmin = 2.0
                    if (ymax in 0.0..1.0) ymax = 2.0
                    if (xmax in 0.0..1.0) xmax = 2.0

                    balloonBoxes.add(listOf(ymin, xmin, ymax, xmax))
                } else {
                    balloonBoxes.add(emptyList())
                }
                val shape = bObj["shape"]?.jsonPrimitive?.content ?: "oval"
                shapes.add(shape)
            }

            runBlocking {
                try {
                    val psd = plugin.buildPsdObject(
                        imagePath = imageToUse.absolutePath,
                        texts = texts,
                        balloonBoxes = balloonBoxes,
                        textBoxes = textBoxes,
                        fontSizes = List(texts.size) { 60 },
                        borderSizes = List(texts.size) { 0 },
                        shapes = shapes,
                        context = ctx
                    )
                    val outPsd = File(outputDir, "$baseName.psd")
                    val psdBytes = com.wip.kpsd.KPsd.write(psd, compress = false)
                    outPsd.writeBytes(psdBytes)
                    println("Successfully wrote ${outPsd.absolutePath}")
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }
            }
        }
    }
}
