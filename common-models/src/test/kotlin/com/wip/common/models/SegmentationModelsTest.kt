package com.wip.common.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SegmentationModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun testSegmentedObjectSerialization() {
        val box = DetectionBox(
            label = "text",
            confidence = 0.96,
            ymin = 0.1,
            xmin = 0.2,
            ymax = 0.3,
            xmax = 0.4
        )
        val polygon = listOf(
            PolygonPoint(0.2, 0.1),
            PolygonPoint(0.4, 0.1),
            PolygonPoint(0.4, 0.3),
            PolygonPoint(0.2, 0.3)
        )
        val obj = SegmentedObject(
            label = "text",
            confidence = 0.96,
            box = box,
            polygon = polygon,
            shape = "rectangular",
            area = 0.04
        )

        val jsonStr = json.encodeToString(SegmentedObject.serializer(), obj)
        assertTrue(jsonStr.contains("\"label\": \"text\""))

        val decoded = json.decodeFromString(SegmentedObject.serializer(), jsonStr)
        assertEquals("text", decoded.label)
        assertEquals(0.96, decoded.confidence)
        assertEquals(4, decoded.polygon.size)
        assertEquals(0.2, decoded.polygon[0].x)
        assertEquals(0.1, decoded.polygon[0].y)
    }

    @Test
    fun testVisionResultSerialization() {
        val obj1 = SegmentedObject(
            label = "balloon",
            confidence = 0.99,
            box = DetectionBox("balloon", 0.99, 0.05, 0.1, 0.25, 0.5),
            polygon = listOf(PolygonPoint(0.1, 0.05), PolygonPoint(0.5, 0.05), PolygonPoint(0.5, 0.25), PolygonPoint(0.1, 0.25)),
            shape = "oval"
        )
        val obj2 = SegmentedObject(
            label = "text",
            confidence = 0.94,
            box = DetectionBox("text", 0.94, 0.08, 0.15, 0.20, 0.45),
            polygon = listOf(PolygonPoint(0.15, 0.08), PolygonPoint(0.45, 0.08), PolygonPoint(0.45, 0.20), PolygonPoint(0.15, 0.20)),
            shape = "rectangular"
        )

        val result = VisionResult(
            objects = listOf(obj1, obj2),
            imageWidth = 1000,
            imageHeight = 2000,
            pageName = "page_001.png",
            maskPath = "masks/page_001_mask.png"
        )

        val jsonStr = json.encodeToString(VisionResult.serializer(), result)
        val decoded = json.decodeFromString(VisionResult.serializer(), jsonStr)

        assertEquals(2, decoded.objects.size)
        assertEquals(1000, decoded.imageWidth)
        assertEquals(2000, decoded.imageHeight)
        assertEquals("page_001.png", decoded.pageName)
        assertEquals("masks/page_001_mask.png", decoded.maskPath)
        assertEquals("balloon", decoded.objects[0].label)
        assertEquals("text", decoded.objects[1].label)
    }

    @Test
    fun testCleanerResultSerialization() {
        val cleanerResult = CleanerResult(
            cleanedImagePath = "/tmp/out/page_001.png",
            maskPath = "/tmp/out/page_001_mask.png",
            cleanedObjectsCount = 5
        )

        val jsonStr = json.encodeToString(CleanerResult.serializer(), cleanerResult)
        val decoded = json.decodeFromString(CleanerResult.serializer(), jsonStr)

        assertEquals("/tmp/out/page_001.png", decoded.cleanedImagePath)
        assertEquals("/tmp/out/page_001_mask.png", decoded.maskPath)
        assertEquals(5, decoded.cleanedObjectsCount)
    }

    @Test
    fun testVisionResultWithDebugImagePath() {
        val result = VisionResult(
            objects = emptyList(),
            imageWidth = 800,
            imageHeight = 1200,
            pageName = "test.png",
            maskPath = "/path/test_mask.png",
            debugImagePath = "/path/test_vision_debug.png"
        )
        val jsonStr = json.encodeToString(VisionResult.serializer(), result)
        val decoded = json.decodeFromString(VisionResult.serializer(), jsonStr)
        assertEquals("/path/test_vision_debug.png", decoded.debugImagePath)
    }

    @Test
    fun testApplySegmentationNms() {
        val obj1 = SegmentedObject(
            label = "balloon",
            confidence = 0.95,
            box = DetectionBox("balloon", 0.95, 0.1, 0.1, 0.5, 0.5)
        )
        val obj2 = SegmentedObject(
            label = "balloon",
            confidence = 0.80,
            box = DetectionBox("balloon", 0.80, 0.11, 0.11, 0.51, 0.51) // Heavy overlap with obj1
        )
        val obj3 = SegmentedObject(
            label = "text",
            confidence = 0.90,
            box = DetectionBox("text", 0.90, 0.15, 0.15, 0.4, 0.4) // Different class
        )

        val filtered = NmsUtils.applySegmentationNms(listOf(obj1, obj2, obj3), iouThreshold = 0.45)
        assertEquals(2, filtered.size, "Overlapping balloon obj2 should be suppressed by obj1")
        assertEquals(0.95, filtered[0].confidence)
        assertEquals("text", filtered[1].label)
    }
}
