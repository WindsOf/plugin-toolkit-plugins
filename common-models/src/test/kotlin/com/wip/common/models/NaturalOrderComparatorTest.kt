package com.wip.common.models

import kotlinx.io.files.Path
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderComparatorTest {

    @Test
    fun testNaturalOrderSortingStrings() {
        val input = listOf("10.png", "1.png", "2.png", "20.png", "11.png", "21.png", "3.png")
        val expected = listOf("1.png", "2.png", "3.png", "10.png", "11.png", "20.png", "21.png")
        val actual = input.sortedNaturally()
        assertEquals(expected, actual)
    }

    @Test
    fun testNaturalOrderSortingWithPrefixAndPadding() {
        val input = listOf("page_10.jpg", "page_1.jpg", "page_02.jpg", "page_003.jpg", "page_20.jpg", "page_2.jpg")
        val sorted = input.sortedNaturally()
        assertEquals("page_1.jpg", sorted[0])
        assertEquals("page_2.jpg", sorted[1])
        assertEquals("page_02.jpg", sorted[2])
        assertEquals("page_003.jpg", sorted[3])
        assertEquals("page_10.jpg", sorted[4])
        assertEquals("page_20.jpg", sorted[5])
    }

    @Test
    fun testNaturalOrderSortingFiles() {
        val input = listOf(
            File("c:/test/10.png"),
            File("c:/test/1.png"),
            File("c:/test/2.png"),
            File("c:/test/100.png")
        )
        val sorted = input.sortedNaturally()
        assertEquals(listOf("1.png", "2.png", "10.png", "100.png"), sorted.map { it.name })
    }

    @Test
    fun testNaturalOrderSortingPaths() {
        val input = listOf(
            Path("folder/10.png"),
            Path("folder/1.png"),
            Path("folder/2.png")
        )
        val sorted = input.sortedNaturally()
        assertEquals(listOf("1.png", "2.png", "10.png"), sorted.map { it.name })
    }

    @Test
    fun testLargeNumbersNoOverflow() {
        val s1 = "image_1000000000000000000000000.png"
        val s2 = "image_2000000000000000000000000.png"
        assertTrue(NaturalOrderComparator.compare(s1, s2) < 0)
    }
}
