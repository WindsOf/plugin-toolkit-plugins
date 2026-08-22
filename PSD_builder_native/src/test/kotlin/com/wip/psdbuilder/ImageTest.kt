package com.wip.psdbuilder

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import io.mockk.mockk
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.wip.plugintoolkit.api.PluginContext

class ImageTest {
    @Test
    fun testWebP() {
        val registry = IIORegistry.getDefaultInstance()
        registry.registerServiceProvider(WebPImageReaderSpi())

        val test2Dir = File("src/main/resources/test2")
        val files = if (test2Dir.exists()) {
            test2Dir.listFiles { _, name -> name.endsWith(".webp") && !name.contains("clean") }?.toList() ?: emptyList()
        } else emptyList()

        val imagesToTest = if (files.isNotEmpty()) {
            files
        } else {
            // Generate temporary image if test2 resources are not present
            val temp = File.createTempFile("test_sample", ".png")
            temp.deleteOnExit()
            val img = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.RED
            g.fillRect(0, 0, 200, 200)
            g.dispose()
            ImageIO.write(img, "png", temp)
            listOf(temp)
        }

        val plugin = PSDBuilderPlugin()
        val ctx = mockk<PluginContext>(relaxed = true)

        for (file in imagesToTest) {
            println("Reading ${file.name}")
            val img = ImageIO.read(file)
            assertTrue(img != null, "ImageIO should successfully read ${file.name}")
            assertTrue(img.width > 0 && img.height > 0)

            runBlocking {
                val psd = plugin.buildPsdObject(
                    imagePath = file.absolutePath,
                    texts = listOf("Sample"),
                    balloonBoxes = listOf(listOf(0.1, 0.1, 0.5, 0.5)),
                    context = ctx
                )
                val psdBytes = com.wip.kpsd.KPsd.write(psd, compress = false)
                assertTrue(psdBytes.isNotEmpty(), "Generated PSD bytes should not be empty")
            }
            println("Successfully built and wrote PSD for ${file.name}")
        }
    }
}

