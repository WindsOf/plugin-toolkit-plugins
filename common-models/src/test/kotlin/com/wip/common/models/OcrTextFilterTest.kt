package com.wip.common.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrTextFilterTest {

    @Test
    fun testIsHallucinationOrEmptyBasic() {
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(null))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(""))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("   "))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("(no text)"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("no text"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("none"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("n/a"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("na"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("empty"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("nothing"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[Non-Text]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("non-text"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[non text]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[not text]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[image]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[graphic]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[blank]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("[noise]"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("The image contains no text."))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("The OCR result \"1\" is a hallucination"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("There is no text in this image"))
    }

    @Test
    fun testDegenerateRepetitions() {
        // Degenerate digit sequence matching user sample
        val degenerateDigits = "1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1.1"
        assertTrue(OcrTextFilter.isDegenerateRepetition(degenerateDigits))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(degenerateDigits))

        // Degenerate single digit repeating
        val repeatingOnes = "111111111111111111111"
        assertTrue(OcrTextFilter.isDegenerateRepetition(repeatingOnes))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(repeatingOnes))

        // Degenerate punctuation repeating pattern
        val repeatingSymbols = "!?!?!?!?!?!?!?!?!?"
        assertTrue(OcrTextFilter.isDegenerateRepetition(repeatingSymbols))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(repeatingSymbols))

        // Degenerate word loops
        val repeatingWords = "word word word word word word word"
        assertTrue(OcrTextFilter.isDegenerateRepetition(repeatingWords))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(repeatingWords))
    }

    @Test
    fun testLegitimateMangaTextNotFiltered() {
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("OHH!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("AHEUI'S\nSPOTS...!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("THE LIGHT THAT EMITTED FROM WHAT VIKIR BROUGHT WAS SO STRONG THAT THE RED DEATH DISAPPEARED!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("VIKIR REALLY DID BRING THE CURE!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("AMAZING...!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("HOW MANY PATIENTS DO WE HAVE?"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("hahahaha"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("Cap. 51"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("Chapter 51"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("354"))
    }

    @Test
    fun testPunctuationAndNoiseFiltering() {
        // Legitimate comic dialogue punctuation must NOT be treated as hallucinations
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("..."))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("?!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("!?"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("?"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("...?!"))
        assertFalse(OcrTextFilter.isHallucinationOrEmpty("—"))

        // Pure punctuation noise/artifacts MUST be filtered
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("---"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("--"))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty("."))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(","))
        assertTrue(OcrTextFilter.isHallucinationOrEmpty(":"))
    }

    @Test
    fun testCleanExtractedText() {
        assertEquals("Hello world", OcrTextFilter.cleanExtractedText("<|ref|>Hello world<|/ref|>"))
        assertEquals("Hello world", OcrTextFilter.cleanExtractedText("text [0, 250, 792, 301]Hello world"))
        assertEquals("", OcrTextFilter.cleanExtractedText("image [0, 0, 999, 999]"))
        assertEquals("?!", OcrTextFilter.cleanExtractedText("{sfx} [200, 400, 700, 800] ?!"))
        assertEquals("?!", OcrTextFilter.cleanExtractedText("{speech} [400, 300, 600, 600] ?!"))
        assertEquals("Hello", OcrTextFilter.cleanExtractedText("{speech} [400, 300, 600, 600]: Hello"))
        assertEquals("BOOM", OcrTextFilter.cleanExtractedText("[200, 400, 700, 800] {sfx} BOOM"))
    }
}
