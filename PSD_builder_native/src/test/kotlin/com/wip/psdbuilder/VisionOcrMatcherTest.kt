package com.wip.psdbuilder

import com.wip.common.models.DetectionBox
import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import java.awt.geom.Point2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionOcrMatcherTest {

    @Test
    fun testPointInPolygon() {
        // Square polygon from (100, 100) to (300, 300)
        val square = listOf(
            Point2D.Double(100.0, 100.0),
            Point2D.Double(300.0, 100.0),
            Point2D.Double(300.0, 300.0),
            Point2D.Double(100.0, 300.0)
        )

        assertTrue(VisionOcrMatcher.isPointInPolygon(200.0, 200.0, square))
        assertTrue(VisionOcrMatcher.isPointInPolygon(150.0, 150.0, square))
        assertFalse(VisionOcrMatcher.isPointInPolygon(50.0, 50.0, square))
        assertFalse(VisionOcrMatcher.isPointInPolygon(350.0, 200.0, square))
    }

    @Test
    fun testBoxIoU() {
        val boxA = doubleArrayOf(100.0, 100.0, 200.0, 200.0) // 100x100 = 10000
        val boxB = doubleArrayOf(100.0, 100.0, 200.0, 200.0)
        assertEquals(1.0, VisionOcrMatcher.boxIoU(boxA, boxB), 0.001)

        val boxC = doubleArrayOf(150.0, 100.0, 250.0, 200.0) // 50x100 overlap = 5000, union = 15000
        assertEquals(5000.0 / 15000.0, VisionOcrMatcher.boxIoU(boxA, boxC), 0.001)

        val boxDisjoint = doubleArrayOf(300.0, 300.0, 400.0, 400.0)
        assertEquals(0.0, VisionOcrMatcher.boxIoU(boxA, boxDisjoint), 0.001)
    }

    @Test
    fun testComputeVisualCenter() {
        val square = listOf(
            Point2D.Double(100.0, 100.0),
            Point2D.Double(300.0, 100.0),
            Point2D.Double(300.0, 300.0),
            Point2D.Double(100.0, 300.0)
        )
        val center = VisionOcrMatcher.computeVisualCenter(square)
        assertEquals(200.0, center.x, 0.01)
        assertEquals(200.0, center.y, 0.01)
    }

    @Test
    fun testMatchOcrWithBalloonSegmentation() {
        val width = 1000.0
        val height = 1000.0

        val segmentedBalloon = SegmentedObject(
            label = "speech_balloon",
            confidence = 0.95,
            box = DetectionBox(ymin = 0.2, xmin = 0.2, ymax = 0.6, xmax = 0.6),
            polygon = listOf(
                PolygonPoint(0.2, 0.2),
                PolygonPoint(0.6, 0.2),
                PolygonPoint(0.6, 0.6),
                PolygonPoint(0.2, 0.6)
            ),
            shape = "oval",
            area = 160000.0
        )

        val texts = listOf("Speech inside balloon", "Outside text")
        val ocrBoxes = listOf(
            listOf(0.3, 0.3, 0.5, 0.5), // Inside balloon (200, 200) to (600, 600)
            listOf(0.8, 0.8, 0.9, 0.9)  // Outside
        )

        val matches = VisionOcrMatcher.match(
            texts = texts,
            ocrBoxes = ocrBoxes,
            visionObjects = listOf(segmentedBalloon),
            imageWidth = width,
            imageHeight = height
        )

        assertEquals(2, matches.size)

        // First item should match the balloon
        val match1 = matches[0]
        assertNotNull(match1.matchedBalloon)
        assertEquals("speech_balloon", match1.matchedBalloon?.label)
        assertNotNull(match1.visualCenter)
        assertEquals(400.0, match1.visualCenter?.x ?: 0.0, 1.0)
        assertEquals(400.0, match1.visualCenter?.y ?: 0.0, 1.0)
        assertTrue(match1.matchScore > 0.4)

        // Second item outside should not match
        val match2 = matches[1]
        assertNull(match2.matchedBalloon)
    }
}
