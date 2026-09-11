package com.wip.common.models

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZitsInpaintingPipelineTest {

    @Test
    fun testComputeMpeWavefrontDilation() {
        // Create 256x256 mask with a 20x20 square in the center
        val mask = BufferedImage(256, 256, BufferedImage.TYPE_BYTE_GRAY)
        val g = mask.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, 256, 256)
        g.color = Color.WHITE
        g.fillRect(118, 118, 20, 20)
        g.dispose()

        val (relPos, direct) = ZitsInpaintingPipeline.computeMpe(mask, strSize = 256, posNum = 128)

        assertEquals(256 * 256, relPos.size)
        assertEquals(256 * 256 * 4, direct.size)

        // Outside mask, relPos should be 0
        assertEquals(0L, relPos[0])
        assertEquals(0L, relPos[10 * 256 + 10])

        // Inside mask, relPos should be >= 1
        val centerIdx = 128 * 256 + 128
        assertTrue(relPos[centerIdx] >= 1L, "Center of hole should have positive iteration distance in MPE")

        // Direct tensor elements should all be 0 or 1
        for (i in 0 until direct.size step 100) {
            val d = direct[i]
            assertTrue(d == 0L || d == 1L, "Direct tensor elements must be 0 or 1")
        }
    }

    @Test
    fun testApplyEdgeNmsBinarization() {
        val edges = FloatArray(10) { i -> (i * 0.1f) } // 0.0, 0.1, 0.2 ... 0.9
        // threshold 50 out of 255 is ~0.196
        val filtered = ZitsInpaintingPipeline.applyEdgeNms(edges, binaryThreshold = 50)

        assertEquals(edges.size, filtered.size)
        // Values < 0.196 should be 0.0f
        assertEquals(0.0f, filtered[0]) // 0.0
        assertEquals(0.0f, filtered[1]) // 0.1
        // Values >= 0.196 should retain edge confidence
        assertEquals(0.2f, filtered[2], 1e-4f)
        assertEquals(0.9f, filtered[9], 1e-4f)
    }

    @Test
    fun testExtractCannyEdges() {
        val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, 64, 64)
        g.color = Color.WHITE
        g.fillRect(20, 20, 24, 24)
        g.dispose()

        val edges = ZitsInpaintingPipeline.extractCannyEdges(img, sigma = 1.5)
        assertEquals(64 * 64, edges.size)

        // Flat background should have 0 edges
        assertEquals(0.0f, edges[5 * 64 + 5])

        // Edge transition at (20, 20) should have detected edge
        var hasEdge = false
        for (y in 19..21) {
            for (x in 19..21) {
                if (edges[y * 64 + x] == 1.0f) hasEdge = true
            }
        }
        assertTrue(hasEdge, "Should detect high gradient edge at boundary of white square")
    }

    @Test
    fun testAlphaFeatheringSmoothsBoundaries() {
        val orig = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val gO = orig.createGraphics()
        gO.color = Color(200, 0, 0)
        gO.fillRect(0, 0, 64, 64)
        gO.dispose()

        val cleaned = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val gC = cleaned.createGraphics()
        gC.color = Color(0, 200, 0)
        gC.fillRect(0, 0, 64, 64)
        gC.dispose()

        val mask = BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, 64, 64)
        gM.color = Color.WHITE
        gM.fillRect(20, 20, 24, 24)
        gM.dispose()

        val feathered = InpaintingUtils.applyAlphaFeather(cleaned, orig, mask, featherRadiusPx = 3)
        assertNotNull(feathered)

        // Outside mask, still intact
        // Inside mask near boundary (e.g. x=20, y=20), color should be a blend of green (cleaned) and red (orig)
        val boundaryRgb = feathered.getRGB(20, 20)
        val r = (boundaryRgb shr 16) and 0xFF
        val g = (boundaryRgb shr 8) and 0xFF

        assertTrue(r > 0, "Red channel from orig should blend in at boundary")
        assertTrue(g > 0, "Green channel from cleaned should blend in at boundary")
    }
}
