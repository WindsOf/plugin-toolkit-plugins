package com.wip.common.models

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SahiInferenceTest {

    @Test
    fun testSliceGenerationLongStrip() {
        val config = SahiConfig(
            sliceWidth = 640,
            sliceHeight = 640,
            overlapWidthRatio = 0.25f,
            overlapHeightRatio = 0.25f,
            includeFullImage = true
        )

        // 1,000 x 5,000 px vertical strip
        val slices = SahiInferenceRunner.generateSlices(1000, 5000, config)
        assertTrue(slices.isNotEmpty())

        // Verify full image tile is included at the end
        val fullImageSlice = slices.last()
        assertTrue(fullImageSlice.isFullImage)
        assertEquals(1000, fullImageSlice.width)
        assertEquals(5000, fullImageSlice.height)

        // Verify tile slices cover entire area
        val tileSlices = slices.filter { !it.isFullImage }
        val maxReachedY = tileSlices.maxOf { it.ymax }
        val maxReachedX = tileSlices.maxOf { it.xmax }
        assertEquals(5000, maxReachedY)
        assertEquals(1000, maxReachedX)
    }

    @Test
    fun testSliceGenerationSmallImage() {
        val config = SahiConfig(
            sliceWidth = 640,
            sliceHeight = 640,
            includeFullImage = true
        )

        // 400 x 400 px image
        val slices = SahiInferenceRunner.generateSlices(400, 400, config)
        assertEquals(1, slices.size)
        assertEquals(0, slices[0].x)
        assertEquals(0, slices[0].y)
        assertEquals(400, slices[0].width)
        assertEquals(400, slices[0].height)
    }

    @Test
    fun testGlobalCoordinateRemapping() {
        val fullWidth = 1000
        val fullHeight = 4000
        val config = SahiConfig()

        // Tile at offset (0, 1000), size 640x640
        val slice = SliceWindow(x = 0, y = 1000, width = 640, height = 640)

        // Local box inside tile: xmin=100px (100/640=0.15625), ymin=200px (200/640=0.3125), xmax=300px, ymax=400px
        val localBox = DetectionBox(
            label = "balloon",
            confidence = 0.95,
            ymin = 0.3125,
            xmin = 0.15625,
            ymax = 0.625,
            xmax = 0.46875
        )

        val globalBox = SahiInferenceRunner.remapToGlobal(localBox, slice, fullWidth, fullHeight, config)
        assertNotNull(globalBox)
        assertEquals("balloon", globalBox.label)
        assertEquals(0.95, globalBox.confidence)

        // Expected global pixel coordinates:
        // xmin = 0 + 100 = 100 -> 100 / 1000 = 0.10
        // ymin = 1000 + 200 = 1200 -> 1200 / 4000 = 0.30
        // xmax = 0 + 300 = 300 -> 300 / 1000 = 0.30
        // ymax = 1000 + 400 = 1400 -> 1400 / 4000 = 0.35
        assertEquals(0.10, globalBox.xmin, 0.0001)
        assertEquals(0.30, globalBox.ymin, 0.0001)
        assertEquals(0.30, globalBox.xmax, 0.0001)
        assertEquals(0.35, globalBox.ymax, 0.0001)
    }

    @Test
    fun testIoUCalculation() {
        val boxA = DetectionBox("balloon", 0.9, ymin = 0.1, xmin = 0.1, ymax = 0.3, xmax = 0.3)
        val boxB = DetectionBox("balloon", 0.8, ymin = 0.1, xmin = 0.1, ymax = 0.3, xmax = 0.3) // Identical
        val boxC = DetectionBox("balloon", 0.7, ymin = 0.5, xmin = 0.5, ymax = 0.7, xmax = 0.7) // Disjoint

        assertEquals(1.0, NmsUtils.calculateIoU(boxA, boxB), 0.0001)
        assertEquals(0.0, NmsUtils.calculateIoU(boxA, boxC), 0.0001)
    }

    @Test
    fun testNmsSuppressesOverlappingBoxes() {
        // Two overlapping detections of the same balloon across adjacent tiles
        val box1 = DetectionBox("balloon", 0.92, ymin = 0.20, xmin = 0.20, ymax = 0.35, xmax = 0.35)
        val box2 = DetectionBox("balloon", 0.85, ymin = 0.21, xmin = 0.21, ymax = 0.36, xmax = 0.36)
        // A distinct separate text detection
        val box3 = DetectionBox("text", 0.88, ymin = 0.70, xmin = 0.70, ymax = 0.80, xmax = 0.80)

        val merged = NmsUtils.applyNms(listOf(box1, box2, box3), iouThreshold = 0.45, scoreThreshold = 0.25)
        assertEquals(2, merged.size)
        assertEquals(0.92, merged[0].confidence)
        assertEquals("balloon", merged[0].label)
        assertEquals("text", merged[1].label)
    }

    @Test
    fun testWbfFusesOverlappingBoxes() {
        val box1 = DetectionBox("balloon", 0.90, ymin = 0.10, xmin = 0.10, ymax = 0.30, xmax = 0.30)
        val box2 = DetectionBox("balloon", 0.90, ymin = 0.12, xmin = 0.12, ymax = 0.32, xmax = 0.32)

        val fused = NmsUtils.applyWbf(listOf(box1, box2), iouThreshold = 0.45)
        assertEquals(1, fused.size)
        assertEquals(0.11, fused[0].ymin, 0.001)
        assertEquals(0.11, fused[0].xmin, 0.001)
        assertEquals(0.31, fused[0].ymax, 0.001)
        assertEquals(0.31, fused[0].xmax, 0.001)
    }

    @Test
    fun testImageTensorFloatBufferConversion() {
        val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED // RGB (255, 0, 0)
        g.fillRect(0, 0, 100, 100)
        g.dispose()

        val buffer = ImageTensorUtils.imageToFloatBuffer(img, targetWidth = 64, targetHeight = 64)
        assertEquals(1 * 3 * 64 * 64, buffer.capacity())

        // Check R channel is ~1.0f and G, B channels are ~0.0f
        val rVal = buffer.get(0)
        val gVal = buffer.get(64 * 64)
        val bVal = buffer.get(2 * 64 * 64)

        assertEquals(1.0f, rVal, 0.01f)
        assertEquals(0.0f, gVal, 0.01f)
        assertEquals(0.0f, bVal, 0.01f)
    }

    @Test
    fun testMultiScaleSliceGeneration() {
        val config2x = SahiConfig(
            sliceWidth = 640,
            sliceHeight = 640,
            overlapWidthRatio = 0.20f,
            overlapHeightRatio = 0.20f,
            tileScale = 2.0,
            includeFullImage = false
        )

        // 2000 x 3000 px image
        val slices = SahiInferenceRunner.generateSlices(2000, 3000, config2x)
        assertTrue(slices.isNotEmpty())

        // With tileScale = 2.0, effective tile size is 1280x1280
        val firstSlice = slices[0]
        assertEquals(1280, firstSlice.width)
        assertEquals(1280, firstSlice.height)
        assertEquals(0, firstSlice.x)
        assertEquals(0, firstSlice.y)

        // Verify full image coverage
        val maxReachedY = slices.maxOf { it.ymax }
        val maxReachedX = slices.maxOf { it.xmax }
        assertEquals(3000, maxReachedY)
        assertEquals(2000, maxReachedX)
    }
}
