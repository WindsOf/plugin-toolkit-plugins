package com.wip.common.models

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InpaintingUtilsTest {

    @Test
    fun testRenderMaskFromObjectsFiltersClass() {
        val textObj = SegmentedObject(
            label = "text",
            confidence = 0.95,
            box = DetectionBox("text", 0.95, 0.2, 0.2, 0.4, 0.4),
            polygon = RfDetrPostprocessor.generateBoxPolygon(0.2, 0.2, 0.4, 0.4)
        )
        val balloonObj = SegmentedObject(
            label = "balloon",
            confidence = 0.99,
            box = DetectionBox("balloon", 0.99, 0.1, 0.1, 0.8, 0.8),
            polygon = RfDetrPostprocessor.generateBoxPolygon(0.1, 0.1, 0.8, 0.8)
        )

        // Render mask targeting ONLY "text"
        val mask = InpaintingUtils.renderMaskFromObjects(
            objects = listOf(textObj, balloonObj),
            imageWidth = 100,
            imageHeight = 100,
            targetClasses = setOf("text"),
            dilationPx = 0
        )

        val raster = mask.raster
        val pixels = IntArray(100 * 100)
        raster.getSamples(0, 0, 100, 100, 0, pixels)

        // Center of text box (x=30, y=30) should be white (255)
        assertEquals(255, pixels[30 * 100 + 30])

        // Outside text box but inside balloon box (x=15, y=15) should be black (0) because balloon was filtered out!
        assertEquals(0, pixels[15 * 100 + 15])
    }

    @Test
    fun testApplyDilationExpandsMask() {
        val baseMask = BufferedImage(50, 50, BufferedImage.TYPE_BYTE_GRAY)
        val g = baseMask.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, 50, 50)
        g.color = Color.WHITE
        g.fillRect(20, 20, 10, 10) // 10x10 square at (20, 20)
        g.dispose()

        val dilated = InpaintingUtils.applyDilation(baseMask, radius = 3)
        val dRaster = dilated.raster
        val dPixels = IntArray(50 * 50)
        dRaster.getSamples(0, 0, 50, 50, 0, dPixels)

        // Pixel at (18, 20) which was black in baseMask should now be white (255) after dilation of radius 3
        assertEquals(255, dPixels[20 * 50 + 18])
    }

    @Test
    fun testInpaintImageRestoresBackground() {
        // Create a 100x100 white image with a red text block in the center
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val gImg = img.createGraphics()
        gImg.color = Color.WHITE
        gImg.fillRect(0, 0, 100, 100)
        gImg.color = Color.RED
        gImg.fillRect(40, 40, 20, 20) // Red box
        gImg.dispose()

        // Create a mask over the red box
        val mask = BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY)
        val gMask = mask.createGraphics()
        gMask.color = Color.BLACK
        gMask.fillRect(0, 0, 100, 100)
        gMask.color = Color.WHITE
        gMask.fillRect(40, 40, 20, 20)
        gMask.dispose()

        val inpainted = InpaintingUtils.inpaintImage(img, mask, roiPaddingPx = 10)

        // Center pixel (50, 50) should be inpainted to near white instead of red
        val rgb = inpainted.getRGB(50, 50)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF

        assertTrue(r > 200, "Expected high R channel for reconstructed white background")
        assertTrue(g > 200, "Expected high G channel for reconstructed white background")
        assertTrue(b > 200, "Expected high B channel for reconstructed white background")
    }

    @Test
    fun testFindMaskBoundingBoxes() {
        val mask = BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY)
        val g = mask.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, 200, 200)
        g.color = Color.WHITE
        g.fillRect(20, 20, 30, 30) // Box 1
        g.fillRect(120, 120, 40, 40) // Box 2
        g.dispose()

        val boxes = InpaintingUtils.findMaskBoundingBoxes(mask, padding = 5)
        assertTrue(boxes.size in 1..2)
    }
}
