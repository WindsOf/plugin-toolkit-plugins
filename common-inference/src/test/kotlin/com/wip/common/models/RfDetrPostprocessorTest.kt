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
    fun testMarchingSquaresContourExtractionOnSquare() {
        val width = 32
        val height = 32
        val grid = Array(height) { BooleanArray(width) }

        // Draw a filled square in the center [8..24, 8..24]
        for (y in 8..24) {
            for (x in 8..24) {
                grid[y][x] = true
            }
        }

        val loops = RfDetrPostprocessor.traceMarchingSquaresContours(grid, width, height, minComponentSize = 16)
        assertTrue(loops.isNotEmpty(), "Should extract at least one closed loop")
        val firstLoop = loops.first()
        assertTrue(firstLoop.size >= 4)

        // Verify loop is closed (first point equals last point)
        assertEquals(firstLoop.first().first, firstLoop.last().first, 0.001)
        assertEquals(firstLoop.first().second, firstLoop.last().second, 0.001)

        // Simplify collinear points along straight square edges
        val simplified = RfDetrPostprocessor.simplifyCollinearPoints(firstLoop)
        // Square loop after collinear simplification should have 5 vertices (4 corners + closing vertex)
        assertEquals(5, simplified.size, "Square contour simplified should have 4 corners + closing vertex")
    }

    @Test
    fun testMarchingSquaresOnCircularMask() {
        val width = 64
        val height = 64
        val grid = Array(height) { BooleanArray(width) }

        val cx = 32.0
        val cy = 32.0
        val radius = 15.0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) {
                    grid[y][x] = true
                }
            }
        }

        val loops = RfDetrPostprocessor.traceMarchingSquaresContours(grid, width, height, minComponentSize = 16)
        assertTrue(loops.isNotEmpty())
        val circleLoop = loops.first()
        assertTrue(circleLoop.size > 20, "Circular mask should have many smooth boundary points")

        // Check loop is fully closed without self-intersections
        assertEquals(circleLoop.first().first, circleLoop.last().first, 0.001)
        assertEquals(circleLoop.first().second, circleLoop.last().second, 0.001)

        // Ensure collinear simplification preserves all curved points
        val simplified = RfDetrPostprocessor.simplifyCollinearPoints(circleLoop)
        assertTrue(simplified.size >= 16, "Circle contour must retain curved points and not collapse into diamond")
    }

    @Test
    fun testMarchingSquaresOnMultipleDisjointBubbles() {
        val width = 64
        val height = 64
        val grid = Array(height) { BooleanArray(width) }

        // Bubble 1 at top left
        for (y in 5..15) {
            for (x in 5..15) {
                grid[y][x] = true
            }
        }

        // Bubble 2 at bottom right
        for (y in 40..55) {
            for (x in 40..55) {
                grid[y][x] = true
            }
        }

        val loops = RfDetrPostprocessor.traceMarchingSquaresContours(grid, width, height, minComponentSize = 16)
        assertEquals(2, loops.size, "Should detect 2 independent closed loops for disjoint bubbles")
    }
}
