package com.wip.psdbuilder

import com.wip.kpsd.PsdBounds
import java.awt.geom.Point2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolygonTextBoundaryTest {

    @Test
    fun testPolygonBoundaryWidth() {
        // Square polygon from (100, 100) to (300, 300)
        val square = listOf(
            Point2D.Double(100.0, 100.0),
            Point2D.Double(300.0, 100.0),
            Point2D.Double(300.0, 300.0),
            Point2D.Double(100.0, 300.0)
        )

        val visualCenter = Point2D.Double(200.0, 200.0)
        val boundary = PolygonTextBoundary(
            polygon = square,
            padding = 10f,
            visualCenter = visualCenter
        )

        val bounds = PsdBounds(left = 100f, top = 100f, right = 300f, bottom = 300f)

        // At center (y = 0 relative to visualCenter.y 200), width is 200 - (2 * 10) = 180
        val centerWidth = boundary.getAvailableWidth(0f, bounds)
        assertEquals(180f, centerWidth, 0.5f)

        // At y = 50 (scanline at y = 250), width is still 180 for a square
        val offCenterWidth = boundary.getAvailableWidth(50f, bounds)
        assertEquals(180f, offCenterWidth, 0.5f)

        // Outside polygon (y = 150 -> scanline at 350), width is 0
        val outsideWidth = boundary.getAvailableWidth(150f, bounds)
        assertEquals(0f, outsideWidth)
    }

    @Test
    fun testDiamondPolygonNarrowing() {
        // Diamond from (200, 100) top, (300, 200) right, (200, 300) bottom, (100, 200) left
        val diamond = listOf(
            Point2D.Double(200.0, 100.0),
            Point2D.Double(300.0, 200.0),
            Point2D.Double(200.0, 300.0),
            Point2D.Double(100.0, 200.0)
        )

        val visualCenter = Point2D.Double(200.0, 200.0)
        val boundary = PolygonTextBoundary(
            polygon = diamond,
            padding = 0f,
            visualCenter = visualCenter
        )

        val bounds = PsdBounds(left = 100f, top = 100f, right = 300f, bottom = 300f)

        // Center width at y = 0 -> 200px
        val centerWidth = boundary.getAvailableWidth(0f, bounds)
        assertEquals(200f, centerWidth, 1.0f)

        // Halfway up at y = -50 (y = 150) -> width should narrow to 100px
        val halfUpWidth = boundary.getAvailableWidth(-50f, bounds)
        assertEquals(100f, halfUpWidth, 1.0f)

        // Near top at y = -90 (y = 110) -> width should be 20px
        val topWidth = boundary.getAvailableWidth(-90f, bounds)
        assertEquals(20f, topWidth, 1.0f)
    }
}
