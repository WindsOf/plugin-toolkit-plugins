package com.wip.psdbuilder

import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.test.assertTrue

class BoundingBoxTest {
    @Test
    fun testDebugModeAndInterpolation() {
        val plugin = PSDBuilderPlugin(PSDBuilderSettings(debugMode = true))
        val ctx = io.mockk.mockk<org.wip.plugintoolkit.api.PluginContext>(relaxed = true)

        // Create a temporary image
        val tempImage = File.createTempFile("test_image", ".png")
        tempImage.deleteOnExit()
        val img = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(img, "png", tempImage)

        // Pass text and bounding boxes (relative coordinates)
        val texts = listOf("Hello World")
        val balloonBoxes = listOf(listOf(0.2, 0.2, 0.4, 0.4)) // ymin, xmin, ymax, xmax
        val textBoxes = listOf(listOf(0.25, 0.25, 0.35, 0.35))

        kotlinx.coroutines.runBlocking {
            try {
                val psd = plugin.buildPsdObject(
                    imagePath = tempImage.absolutePath,
                    texts = texts,
                    balloonBoxes = balloonBoxes,
                    textBoxes = textBoxes,
                    context = ctx
                )
                // If it successfully builds and doesn't crash, the debug drawing and box calculation work.
                assertTrue(psd.children.isNotEmpty())
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }
}
