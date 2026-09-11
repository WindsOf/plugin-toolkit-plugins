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

    @Test
    fun testDenseMaskBoundingBoxesWithHundredsOfObjects() {
        val mask = BufferedImage(800, 800, BufferedImage.TYPE_BYTE_GRAY)
        val g = mask.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, 800, 800)
        g.color = Color.WHITE

        // Draw 300 dense small text rectangles simulating Chapter Vision output
        for (row in 0 until 15) {
            for (col in 0 until 20) {
                val x = 20 + col * 38
                val y = 20 + row * 50
                g.fillRect(x, y, 16, 12)
            }
        }
        g.dispose()

        val boxes = InpaintingUtils.findMaskBoundingBoxes(mask, padding = 4)
        assertTrue(boxes.isNotEmpty(), "Bounding boxes should be extracted for dense objects")
        assertTrue(boxes.size <= 300, "Boxes should be correctly clustered/merged")
    }

    @Test
    fun testDirectFloatBufferAllocation() {
        val buffer = ImageTensorUtils.allocateDirectFloatBuffer(1024)
        assertTrue(buffer.isDirect, "Allocated FloatBuffer must be off-heap direct buffer")
        assertEquals(1024, buffer.capacity())
    }

    @Test
    fun testPureKotlinInpaintingHighDensityPatch() {
        val patchImg = BufferedImage(150, 150, BufferedImage.TYPE_INT_RGB)
        val gImg = patchImg.createGraphics()
        gImg.color = Color.WHITE
        gImg.fillRect(0, 0, 150, 150)
        gImg.color = Color.BLACK
        gImg.fillRect(30, 30, 90, 90)
        gImg.dispose()

        val patchMask = BufferedImage(150, 150, BufferedImage.TYPE_BYTE_GRAY)
        val gMask = patchMask.createGraphics()
        gMask.color = Color.BLACK
        gMask.fillRect(0, 0, 150, 150)
        gMask.color = Color.WHITE
        gMask.fillRect(30, 30, 90, 90)
        gMask.dispose()

        val inpainted = InpaintingUtils.inpaintPatchPureKotlin(patchImg, patchMask)
        assertNotNull(inpainted)
        assertEquals(150, inpainted.width)
        assertEquals(150, inpainted.height)

        val centerRgb = inpainted.getRGB(75, 75)
        val r = (centerRgb shr 16) and 0xFF
        assertTrue(r > 200, "Reconstructed patch center should be restored to surrounding white color")
    }

    @Test
    fun testInpaintImageIsolatedAlpha() {
        val width = 100
        val height = 100
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val gImg = img.createGraphics()
        gImg.color = Color.WHITE
        gImg.fillRect(0, 0, width, height)
        gImg.color = Color.RED
        gImg.fillRect(30, 30, 40, 40)
        gImg.dispose()

        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val gMask = mask.createGraphics()
        gMask.color = Color.BLACK
        gMask.fillRect(0, 0, width, height)
        gMask.color = Color.WHITE
        gMask.fillRect(30, 30, 40, 40)
        gMask.dispose()

        val isolated = InpaintingUtils.inpaintImageIsolated(img, mask, roiPaddingPx = 10, featherRadiusPx = 2)
        assertEquals(width, isolated.width)
        assertEquals(height, isolated.height)

        // Pixel outside mask (e.g. at 5, 5) -> alpha must be 0
        val outRgb = isolated.getRGB(5, 5)
        val outAlpha = (outRgb ushr 24) and 0xFF
        assertEquals(0, outAlpha, "Pixel outside masked regions must have alpha = 0")

        // Pixel inside mask (e.g. at 50, 50) -> alpha must be 255
        val inRgb = isolated.getRGB(50, 50)
        val inAlpha = (inRgb ushr 24) and 0xFF
        assertEquals(255, inAlpha, "Pixel inside masked region must have alpha = 255")

        val r = (inRgb shr 16) and 0xFF
        assertTrue(r > 200, "Inpainted patch should be reconstructed white color")
    }

    @Test
    fun testRenderDebugVisualization() {
        val width = 200
        val height = 200
        val baseImg = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImg.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.dispose()

        val balloonObj = SegmentedObject(
            label = "balloon",
            confidence = 0.95,
            box = DetectionBox("balloon", 0.95, 0.1, 0.1, 0.5, 0.5),
            polygon = listOf(PolygonPoint(0.1, 0.1), PolygonPoint(0.5, 0.1), PolygonPoint(0.5, 0.5), PolygonPoint(0.1, 0.5))
        )
        val textObj = SegmentedObject(
            label = "text",
            confidence = 0.98,
            box = DetectionBox("text", 0.98, 0.2, 0.2, 0.4, 0.4),
            polygon = listOf(PolygonPoint(0.2, 0.2), PolygonPoint(0.4, 0.2), PolygonPoint(0.4, 0.4), PolygonPoint(0.2, 0.4))
        )

        val debugImg = InpaintingUtils.renderDebugVisualization(
            baseImage = baseImg,
            objects = listOf(balloonObj, textObj),
            candidateBoxes = listOf(balloonObj.box)
        )

        assertNotNull(debugImg)
        assertEquals(width, debugImg.width)
        assertEquals(height, debugImg.height)

        // Pixel in drawn area should not be pure white anymore (it has colored alpha overlay)
        val centerRgb = debugImg.getRGB(60, 60)
        assertTrue(centerRgb != Color.WHITE.rgb, "Overlay should have rendered colors on debug visualization")
    }

    @Test
    fun testRenderDebugVisualizationWithSlicesAndSegmentationRois() {
        val width = 300
        val height = 300
        val baseImg = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = baseImg.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.dispose()

        val slices = listOf(
            SliceWindow(0, 0, 150, 150),
            SliceWindow(100, 100, 150, 150)
        )

        val segRois = listOf(
            DetectionBox("balloon", 0.95, 0.1, 0.1, 0.5, 0.5)
        )

        val debugImg = InpaintingUtils.renderDebugVisualization(
            baseImage = baseImg,
            objects = emptyList(),
            candidateBoxes = emptyList(),
            slices = slices,
            segmentationRois = segRois
        )

        assertNotNull(debugImg)
        assertEquals(width, debugImg.width)
        assertEquals(height, debugImg.height)
    }

    @Test
    fun testSnapToMultiple() {
        assertEquals(32, ImageTensorUtils.snapToMultiple(10, 32))
        assertEquals(32, ImageTensorUtils.snapToMultiple(32, 32))
        assertEquals(64, ImageTensorUtils.snapToMultiple(33, 32))
        assertEquals(256, ImageTensorUtils.snapToMultiple(250, 32))
    }

    @Test
    fun testAnalyzeRegionHomogeneitySolidWhite() {
        val width = 100
        val height = 100
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        g.fillRect(30, 30, 40, 40) // Masked text
        g.dispose()

        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, width, height)
        gM.color = Color.WHITE
        gM.fillRect(30, 30, 40, 40)
        gM.dispose()

        val region = SliceWindow(20, 20, 60, 60)
        val result = InpaintingUtils.analyzeRegionHomogeneity(img, mask, region)

        assertTrue(result.isHomogeneous, "Solid white bubble background must be detected as homogeneous")
        assertEquals(255, result.meanR)
        assertEquals(255, result.meanG)
        assertEquals(255, result.meanB)
        assertTrue(result.stdDev <= 1.0)
    }

    @Test
    fun testAnalyzeRegionHomogeneityGradient() {
        val width = 100
        val height = 100
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val shade = (100 + x).coerceIn(0, 255)
                val rgb = (shade shl 16) or (shade shl 8) or shade
                img.setRGB(x, y, rgb)
            }
        }

        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, width, height)
        gM.color = Color.WHITE
        gM.fillRect(30, 30, 40, 40)
        gM.dispose()

        val region = SliceWindow(20, 20, 60, 60)
        val result = InpaintingUtils.analyzeRegionHomogeneity(img, mask, region)

        assertTrue(result.isGradient, "Smooth linear X-gradient must be detected as gradient")
        assertTrue(result.aR > 0.5, "Gradient slope aR must be positive along X-axis")
    }

    @Test
    fun testInpaintProductionHybridSolid() {
        val width = 100
        val height = 100
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        g.fillRect(35, 35, 30, 30) // Text
        g.dispose()

        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, width, height)
        gM.color = Color.WHITE
        gM.fillRect(35, 35, 30, 30)
        gM.dispose()

        val cleaned = InpaintingUtils.inpaintProductionHybrid(
            sourceImage = img,
            mask = mask,
            deterministicFill = true
        )

        val centerRgb = cleaned.getRGB(50, 50)
        val r = (centerRgb shr 16) and 0xFF
        val gChan = (centerRgb shr 8) and 0xFF
        val b = centerRgb and 0xFF

        assertEquals(255, r)
        assertEquals(255, gChan)
        assertEquals(255, b)
    }

    @Test
    fun testInpaintProductionHybridIsolatedAlpha() {
        val width = 100
        val height = 100
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        g.fillRect(35, 35, 30, 30)
        g.dispose()

        val mask = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, width, height)
        gM.color = Color.WHITE
        gM.fillRect(35, 35, 30, 30)
        gM.dispose()

        val isolated = InpaintingUtils.inpaintProductionHybridIsolated(
            sourceImage = img,
            mask = mask,
            deterministicFill = true
        )

        assertEquals(width, isolated.width)
        assertEquals(height, isolated.height)

        // Pixel outside mask (10, 10) must be fully transparent (alpha == 0)
        val outAlpha = (isolated.getRGB(10, 10) ushr 24) and 0xFF
        assertEquals(0, outAlpha)

        // Pixel inside mask (50, 50) must be visible (alpha == 255)
        val inAlpha = (isolated.getRGB(50, 50) ushr 24) and 0xFF
        assertEquals(255, inAlpha)
    }

    @Test
    fun testPoissonBlendingSolvesBoundaryContinuity() {
        val w = 80
        val h = 80
        val orig = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gO = orig.createGraphics()
        gO.color = Color(100, 100, 100)
        gO.fillRect(0, 0, w, h)
        gO.dispose()

        // Cleaned has sharp contrast (200, 200, 200)
        val cleaned = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gC = cleaned.createGraphics()
        gC.color = Color(200, 200, 200)
        gC.fillRect(0, 0, w, h)
        gC.dispose()

        val mask = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, w, h)
        gM.color = Color.WHITE
        gM.fillRect(20, 20, 40, 40) // Mask from (20, 20) to (60, 60)
        gM.dispose()

        val direct = InpaintingUtils.applyDirectPaste(cleaned, orig, mask)
        val poisson = InpaintingUtils.applyPoissonBlending(cleaned, orig, mask, iterations = 50)

        // Pixel outside mask (10, 10) must be untouched target (100, 100, 100)
        val outRgb = poisson.getRGB(10, 10)
        assertEquals(100, (outRgb shr 16) and 0xFF)

        // Pixel right at the boundary inside the mask (20, 20)
        // Under direct paste, this is 200 (sharp seam jump: 100 -> 200)
        val directSeamR = (direct.getRGB(20, 20) shr 16) and 0xFF
        assertEquals(200, directSeamR)

        // Under Poisson, boundary pixel (20, 20) is pulled close to background (100) to eliminate seam
        val poissonSeamR = (poisson.getRGB(20, 20) shr 16) and 0xFF
        assertTrue(poissonSeamR < 150, "Poisson must pull boundary pixels toward target background (got $poissonSeamR)")

        // Direct paste and Poisson MUST NOT be identical (resolving reported bug)
        var diffCount = 0
        for (y in 20 until 60) {
            for (x in 20 until 60) {
                if (poisson.getRGB(x, y) != direct.getRGB(x, y)) {
                    diffCount++
                }
            }
        }
        assertTrue(diffCount > 0, "Poisson blending must produce distinct pixel values from direct paste")
    }

    @Test
    fun testModifiedPoissonPreventsColorBleedInCenter() {
        val w = 80
        val h = 80
        val orig = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gO = orig.createGraphics()
        gO.color = Color(30, 30, 30) // Dark background
        gO.fillRect(0, 0, w, h)
        gO.dispose()

        // Cleaned has bright white patch (240, 240, 240)
        val cleaned = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gC = cleaned.createGraphics()
        gC.color = Color(240, 240, 240)
        gC.fillRect(0, 0, w, h)
        gC.dispose()

        val mask = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, w, h)
        gM.color = Color.WHITE
        gM.fillRect(15, 15, 50, 50)
        gM.dispose()

        val purePoisson = InpaintingUtils.applyPoissonBlending(cleaned, orig, mask, iterations = 40)
        val modPoisson = InpaintingUtils.applyModifiedPoissonBlending(cleaned, orig, mask, decayRadius = 10, iterations = 40)

        // In pure Poisson, the dark boundary diffuses throughout the hole, significantly darkening the center (40, 40)
        val pureCenterR = (purePoisson.getRGB(40, 40) shr 16) and 0xFF

        // In Modified Poisson, boundary attenuation preserves the bright center color
        val modCenterR = (modPoisson.getRGB(40, 40) shr 16) and 0xFF

        assertTrue(
            modCenterR > pureCenterR,
            "Modified Poisson must preserve higher brightness in hole center than pure Poisson (mod: $modCenterR vs pure: $pureCenterR)"
        )
        assertTrue(modCenterR >= 200, "Modified Poisson center should remain close to original inpainting brightness (240)")

        // Boundary pixel (15, 15) in Modified Poisson should still smoothly transition to background (30)
        val modEdgeR = (modPoisson.getRGB(15, 15) shr 16) and 0xFF
        assertTrue(modEdgeR < 150, "Modified Poisson boundary must adapt toward background (got $modEdgeR)")
    }

    @Test
    fun testLaplacianPyramidBlendingFusesMultiScale() {
        val w = 64
        val h = 64
        val orig = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gO = orig.createGraphics()
        gO.color = Color(50, 100, 150)
        gO.fillRect(0, 0, w, h)
        gO.dispose()

        val cleaned = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gC = cleaned.createGraphics()
        gC.color = Color(200, 150, 100)
        gC.fillRect(0, 0, w, h)
        gC.dispose()

        val mask = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, w, h)
        gM.color = Color.WHITE
        gM.fillRect(16, 16, 32, 32)
        gM.dispose()

        val blended = InpaintingUtils.applyLaplacianPyramidBlending(cleaned, orig, mask, levels = 3)
        assertEquals(w, blended.width)
        assertEquals(h, blended.height)

        // Pixel outside mask should be close to orig
        val outRgb = blended.getRGB(5, 5)
        val outR = (outRgb shr 16) and 0xFF
        assertTrue(outR in 40..60, "Outside pixel should be close to background R=50 (got $outR)")

        // Pixel inside mask should reflect cleaned image
        val inRgb = blended.getRGB(32, 32)
        val inR = (inRgb shr 16) and 0xFF
        assertTrue(inR > 150, "Inside pixel should reflect inpainting R=200 (got $inR)")
    }

    @Test
    fun testBlendPatchRoutesAllBlendingModes() {
        val w = 50
        val h = 50
        val orig = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gO = orig.createGraphics()
        gO.color = Color.BLUE
        gO.fillRect(0, 0, w, h)
        gO.dispose()

        val cleaned = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gC = cleaned.createGraphics()
        gC.color = Color.RED
        gC.fillRect(0, 0, w, h)
        gC.dispose()

        val mask = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val gM = mask.createGraphics()
        gM.color = Color.BLACK
        gM.fillRect(0, 0, w, h)
        gM.color = Color.WHITE
        gM.fillRect(15, 15, 20, 20)
        gM.dispose()

        for (mode in BlendingMode.entries) {
            val options = InpaintingOptions(blendingMode = mode, featherRadius = 2)
            val result = InpaintingUtils.blendPatch(cleaned, orig, mask, options)
            assertNotNull(result)
            assertEquals(w, result.width)
            assertEquals(h, result.height)
        }
    }
}

