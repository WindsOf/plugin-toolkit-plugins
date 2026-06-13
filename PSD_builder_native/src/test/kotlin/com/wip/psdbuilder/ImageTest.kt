package com.wip.psdbuilder

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry

class ImageTest {
    @Test
    fun testWebP() {
        val registry = IIORegistry.getDefaultInstance()
        registry.registerServiceProvider(WebPImageReaderSpi())

        val files = listOf("1_upscaled.webp", "2_upscaled.webp", "3_upscaled.webp")
        for (f in files) {
            val file = File("C:/Users/sgroo/Desktop/testc/denoise/$f")
            println("Reading $f")
            val img = ImageIO.read(file)
            println("Width: ${img.width}, Height: ${img.height}")
            val w = img.width
            val h = img.height
            val rgb = IntArray(w * h)
            img.getRGB(0, 0, w, h, rgb, 0, w)
            println("Got RGB successfully for $f")
            
            // Build PSD Object to see if it crashes
            val plugin = PSDBuilderPlugin()
            val ctx = io.mockk.mockk<org.wip.plugintoolkit.api.PluginContext>(relaxed = true)
            
            kotlinx.coroutines.runBlocking {
                try {
                    val psd = plugin.buildPsdObject(
                        imagePath = file.absolutePath,
                        texts = emptyList(),
                        balloonBoxes = emptyList(),
                        context = ctx
                    )
                    com.wip.kpsd.KPsd.write(psd, compress = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }
            }
            println("Successfully built and wrote PSD for $f")
        }
    }
}
