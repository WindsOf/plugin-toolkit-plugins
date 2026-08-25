package com.wip.common.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RfDetrPostprocessorTest {

    @Test
    fun testGenerateBoxPolygon() {
        val poly = RfDetrPostprocessor.generateBoxPolygon(0.1, 0.2, 0.5, 0.6)
        assertEquals(4, poly.size)
        assertEquals(0.1, poly[0].x)
        assertEquals(0.2, poly[0].y)
        assertEquals(0.5, poly[1].x)
        assertEquals(0.2, poly[1].y)
        assertEquals(0.5, poly[2].x)
        assertEquals(0.6, poly[2].y)
        assertEquals(0.1, poly[3].x)
        assertEquals(0.6, poly[3].y)
    }

    @Test
    fun testInferShape() {
        val boxPoly = RfDetrPostprocessor.generateBoxPolygon(0.1, 0.1, 0.3, 0.3)
        assertEquals("rectangular", RfDetrPostprocessor.inferShape(boxPoly))

        val ovalPoly = (0..10).map { PolygonPoint(it * 0.1, it * 0.1) }
        assertEquals("oval", RfDetrPostprocessor.inferShape(ovalPoly))
    }

    @Test
    fun testRemapRoiToGlobal() {
        // Full image is 1000x5000 (webtoon strip)
        // ROI is located at ymin=0.20 (y=1000px), xmin=0.10 (x=100px), ymax=0.30 (y=1500px), xmax=0.50 (x=500px)
        val roiBox = DetectionBox(
            label = "balloon",
            confidence = 0.95,
            ymin = 0.20,
            xmin = 0.10,
            ymax = 0.30,
            xmax = 0.50
        )

        // Inside the ROI crop (normalized 0.0..1.0 relative to ROI):
        // text box is at xmin=0.25, ymin=0.25, xmax=0.75, ymax=0.75
        val localText = SegmentedObject(
            label = "text",
            confidence = 0.92,
            box = DetectionBox("text", 0.92, 0.25, 0.25, 0.75, 0.75),
            polygon = listOf(
                PolygonPoint(0.25, 0.25),
                PolygonPoint(0.75, 0.25),
                PolygonPoint(0.75, 0.75),
                PolygonPoint(0.25, 0.75)
            )
        )

        val globalText = RfDetrPostprocessor.remapRoiToGlobal(localText, roiBox)
        assertEquals("text", globalText.label)
        assertEquals(0.92, globalText.confidence)

        // ROI width = 0.50 - 0.10 = 0.40
        // ROI height = 0.30 - 0.20 = 0.10
        // global xmin = 0.10 + 0.25 * 0.40 = 0.20
        // global ymin = 0.20 + 0.25 * 0.10 = 0.225
        // global xmax = 0.10 + 0.75 * 0.40 = 0.40
        // global ymax = 0.20 + 0.75 * 0.10 = 0.275
        assertEquals(0.20, globalText.box.xmin, 0.0001)
        assertEquals(0.225, globalText.box.ymin, 0.0001)
        assertEquals(0.40, globalText.box.xmax, 0.0001)
        assertEquals(0.275, globalText.box.ymax, 0.0001)

        assertEquals(0.20, globalText.polygon[0].x, 0.0001)
        assertEquals(0.225, globalText.polygon[0].y, 0.0001)
    }

    @Test
    fun testBoundaryTracing() {
        val width = 32
        val height = 32
        val grid = Array(height) { BooleanArray(width) }

        // Draw a filled square in the center
        for (y in 8..24) {
            for (x in 8..24) {
                grid[y][x] = true
            }
        }

        val contours = RfDetrPostprocessor.traceAllContours(grid, width, height)
        assertTrue(contours.isNotEmpty())
        val firstContour = contours.first()
        assertTrue(firstContour.size >= 8)

        // Verify contour points are within grid bounds
        for ((cx, cy) in firstContour) {
            assertTrue(cx in 0.0..width.toDouble())
            assertTrue(cy in 0.0..height.toDouble())
        }

        val simplified = RfDetrPostprocessor.ramerDouglasPeucker(firstContour, 0.5)
        assertTrue(simplified.size >= 4)
    }

    @Test
    fun testRdpOnLargeClosedContourDoesNotOverflow() {
        // Construct a circular closed loop of 5000 points
        val numPoints = 5000
        val circlePoints = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until numPoints) {
            val angle = i * 2.0 * Math.PI / numPoints
            val x = 100.0 + 50.0 * Math.cos(angle)
            val y = 100.0 + 50.0 * Math.sin(angle)
            circlePoints.add(Pair(x, y))
        }
        // Close loop
        circlePoints.add(circlePoints.first())

        val simplified = RfDetrPostprocessor.ramerDouglasPeucker(circlePoints, epsilon = 0.5)
        assertTrue(simplified.isNotEmpty())
        assertTrue(simplified.size < numPoints)
    }
}
