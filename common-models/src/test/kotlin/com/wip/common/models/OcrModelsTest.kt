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
}
