package com.wip.common.models

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OcrModelsTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun testOCRResultSerialization() {
        val ocr = OCRResult(
            texts = listOf("Hello", "World"),
            bb = listOf(
                listOf(0.1, 0.2, 0.3, 0.4),
                listOf(0.5, 0.6, 0.7, 0.8)
            ),
            pageNumbers = listOf(1, 1),
            pageNames = listOf("page1.png", "page1.png"),
            failedFiles = emptyList()
        )

        val encoded = json.encodeToString(OCRResult.serializer(), ocr)
        assertNotNull(encoded)

        val decoded = json.decodeFromString(OCRResult.serializer(), encoded)
        assertEquals(ocr, decoded)
        assertEquals(2, decoded.texts.size)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), decoded.bb[0])
    }

    @Test
    fun testAdvancedOCRResultSerialization() {
        val advancedOcr = AdvancedOCRResult(
            texts = listOf("Speech text"),
            balloonBoxes = listOf(listOf(0.1, 0.1, 0.4, 0.4)),
            textBoxes = listOf(listOf(0.15, 0.15, 0.35, 0.35)),
            shapes = listOf("oval"),
            fontStyles = listOf("bold"),
            fontFamilies = listOf("sans-serif"),
            textAngles = listOf(0.0),
            isSparse = listOf(false),
            textColors = listOf("#000000"),
            hasBorder = listOf(true),
            borderColors = listOf("#FFFFFF"),
            pageNumbers = listOf(1),
            pageNames = listOf("001.png"),
            failedFiles = emptyList()
        )

        val encoded = json.encodeToString(AdvancedOCRResult.serializer(), advancedOcr)
        assertNotNull(encoded)

        val decoded = json.decodeFromString(AdvancedOCRResult.serializer(), encoded)
        assertEquals(advancedOcr, decoded)
        assertEquals("oval", decoded.shapes.first())
        assertEquals("#000000", decoded.textColors.first())
    }

    @Test
    fun testOcrVisionMergerFiltersHallucinationsAndPreservesCoordinates() {
        val ocr = OCRResult(
            texts = listOf(
                "(no text)",
                "Top balloon line 1",
                "Top balloon line 2",
                "The image contains no text. The OCR result \"1\" is a hallucination",
                "Bottom balloon"
            ),
            bb = listOf(
                listOf(0.0, 0.0, 1.0, 1.0),
                listOf(0.10, 0.10, 0.15, 0.30),
                listOf(0.16, 0.10, 0.20, 0.30),
                listOf(0.0, 0.0, 1.0, 1.0),
                listOf(0.60, 0.20, 0.70, 0.40)
            ),
            pageNumbers = listOf(1, 1, 1, 1, 1),
            pageNames = listOf("001.png", "001.png", "001.png", "001.png", "001.png"),
            failedFiles = emptyList()
        )

        val vision = VisionResult(
            objects = listOf(
                SegmentedObject(
                    label = "balloon",
                    confidence = 0.95,
                    box = DetectionBox(ymin = 0.08, xmin = 0.08, ymax = 0.22, xmax = 0.32)
                ),
                SegmentedObject(
                    label = "panel", // Non-balloon label that shouldn't erroneously merge everything
                    confidence = 0.99,
                    box = DetectionBox(ymin = 0.0, xmin = 0.0, ymax = 1.0, xmax = 1.0)
                )
            ),
            imageWidth = 1000,
            imageHeight = 1000,
            pageName = "1.png"
        )

        val merged = OcrVisionMerger.mergeOcrResult(ocr, vision)
        assertEquals(2, merged.texts.size, "Ghost and hallucination layers must be dropped")
        assertEquals("Top balloon line 1 Top balloon line 2", merged.texts[0])
        assertEquals("Bottom balloon", merged.texts[1])
        assertEquals(0.10, merged.bb[0][0])
        assertEquals(0.60, merged.bb[1][0])
    }

    @Test
    fun testChapterVisionZeroPaddedMatching() {
        val ocr = OCRResult(
            texts = listOf("P1 Text", "P2 Text"),
            bb = listOf(listOf(0.1, 0.1, 0.2, 0.2), listOf(0.1, 0.1, 0.2, 0.2)),
            pageNumbers = listOf(1, 2),
            pageNames = listOf("1.png", "02.png"),
            failedFiles = emptyList()
        )

        val chapterVision = ChapterVisionResult(
            results = listOf(
                VisionResult(
                    objects = listOf(SegmentedObject(label = "balloon", confidence = 0.9, box = DetectionBox(ymin = 0.05, xmin = 0.05, ymax = 0.3, xmax = 0.3))),
                    imageWidth = 1000,
                    imageHeight = 1000,
                    pageName = "001.png"
                ),
                VisionResult(
                    objects = listOf(SegmentedObject(label = "balloon", confidence = 0.9, box = DetectionBox(ymin = 0.05, xmin = 0.05, ymax = 0.3, xmax = 0.3))),
                    imageWidth = 1000,
                    imageHeight = 1000,
                    pageName = "page_2.png"
                )
            ),
            totalObjectsDetected = 2
        )

        val merged = OcrVisionMerger.mergeChapterOcrResult(ocr, chapterVision)
        assertEquals(2, merged.texts.size)
        assertEquals("P1 Text", merged.texts[0])
        assertEquals("P2 Text", merged.texts[1])
    }
}
