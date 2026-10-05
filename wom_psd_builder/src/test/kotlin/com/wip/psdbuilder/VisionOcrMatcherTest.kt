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
        val balloon = match1.matchedBalloon
        assertNotNull(balloon)
        assertEquals("speech_balloon", balloon.label)
        val vCenter = match1.visualCenter
        assertNotNull(vCenter)
        assertEquals(400.0, vCenter.x, 1.0)
        assertEquals(400.0, vCenter.y, 1.0)
        assertTrue(match1.matchScore > 0.4)

        // Second item outside should not match
        val match2 = matches[1]
        assertNull(match2.matchedBalloon)
    }

    @Test
    fun testMatchOcrWithInaccurateOcrBalloonBox() {
        val width = 1000.0
        val height = 1000.0

        val segmentedBalloon = SegmentedObject(
            label = "speech_balloon",
            confidence = 0.98,
            box = DetectionBox(ymin = 0.1, xmin = 0.1, ymax = 0.7, xmax = 0.7),
            polygon = listOf(
                PolygonPoint(0.1, 0.1),
                PolygonPoint(0.7, 0.1),
                PolygonPoint(0.7, 0.7),
                PolygonPoint(0.1, 0.7)
            ),
            shape = "oval",
            area = 360000.0
        )

        val texts = listOf("Dialogue inside large bubble")
        val ocrBoxes = listOf(listOf(0.3, 0.3, 0.45, 0.55)) // Text inside (300, 300) to (450, 550)
        val ocrBalloonBoxes = listOf(listOf(0.28, 0.28, 0.47, 0.57)) // Inaccurate tight OCR balloon box

        val matches = VisionOcrMatcher.match(
            texts = texts,
            ocrBoxes = ocrBoxes,
            ocrBalloonBoxes = ocrBalloonBoxes,
            visionObjects = listOf(segmentedBalloon),
            imageWidth = width,
            imageHeight = height
        )

        assertEquals(1, matches.size)
        assertNotNull(
            matches[0].matchedBalloon,
            "Should match cleaner balloon directly based on containment and center"
        )
        val bounds = matches[0].polygonBounds
        assertNotNull(bounds)
        assertEquals(100f, bounds.left, 1f)
        assertEquals(100f, bounds.top, 1f)
        assertEquals(700f, bounds.right, 1f)
        assertEquals(700f, bounds.bottom, 1f)
    }

    @Test
    fun testIsBalloonContainer() {
        assertTrue(VisionOcrMatcher.isBalloonContainer("balloon"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("bubble"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("speech_balloon"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("speech balloon"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("circular"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("circular (98%)"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("irregular"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("rectangular"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("jagged"))
        assertTrue(VisionOcrMatcher.isBalloonContainer("spiky"))

        assertFalse(VisionOcrMatcher.isBalloonContainer("text"))
        assertFalse(VisionOcrMatcher.isBalloonContainer("speech"))
        assertFalse(VisionOcrMatcher.isBalloonContainer("sfx"))
        assertFalse(VisionOcrMatcher.isBalloonContainer("watermark"))
        assertFalse(VisionOcrMatcher.isBalloonContainer("non_text"))
        assertFalse(VisionOcrMatcher.isBalloonContainer("none"))
    }

    @Test
    fun testMatchPrefersBalloonOverTextElement() {
        val width = 1000.0
        val height = 1000.0

        // Large speech bubble (circular)
        val circularBalloon = SegmentedObject(
            label = "circular",
            confidence = 0.98,
            box = DetectionBox(ymin = 0.10, xmin = 0.10, ymax = 0.70, xmax = 0.70),
            polygon = listOf(
                PolygonPoint(0.10, 0.40),
                PolygonPoint(0.40, 0.10),
                PolygonPoint(0.70, 0.40),
                PolygonPoint(0.40, 0.70)
            ),
            shape = "oval",
            area = 360000.0
        )

        // Tight text polygon (reclassified as "speech")
        val textElement = SegmentedObject(
            label = "speech",
            confidence = 0.95,
            box = DetectionBox(ymin = 0.35, xmin = 0.30, ymax = 0.45, xmax = 0.50),
            polygon = listOf(
                PolygonPoint(0.30, 0.35),
                PolygonPoint(0.50, 0.35),
                PolygonPoint(0.50, 0.45),
                PolygonPoint(0.30, 0.45)
            ),
            shape = "rectangular",
            area = 20000.0
        )

        val texts = listOf("...BY STICKING")
        val ocrBoxes = listOf(listOf(0.35, 0.30, 0.45, 0.50))

        val matches = VisionOcrMatcher.match(
            texts = texts,
            ocrBoxes = ocrBoxes,
            visionObjects = listOf(textElement, circularBalloon),
            imageWidth = width,
            imageHeight = height
        )

        assertEquals(1, matches.size)
        val matched = matches[0].matchedBalloon
        assertNotNull(matched, "Should match the circular speech balloon")
        assertEquals("circular", matched.label, "Must match the balloon container, NOT the text element")
        val bounds = matches[0].polygonBounds
        assertNotNull(bounds)
        assertEquals(100f, bounds.left, 1f, "Bounding box must be the whole balloon, not the tight text")
        assertEquals(700f, bounds.right, 1f)
    }
}
