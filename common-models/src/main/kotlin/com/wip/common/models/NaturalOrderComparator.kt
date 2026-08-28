package com.wip.common.models

import kotlinx.io.files.Path
import java.io.File

/**
 * Natural order (alphanumeric) comparator for sorting filenames and strings naturally
 * so that numeric substrings are compared by value (e.g., "1.png", "2.png", "10.png").
 */
object NaturalOrderComparator : Comparator<String> {

    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        var i = 0
        var j = 0
        val len1 = s1.length
        val len2 = s2.length

        while (i < len1 && j < len2) {
            val c1 = s1[i]
            val c2 = s2[j]

            if (c1.isDigit() && c2.isDigit()) {
                val start1 = i
                while (i < len1 && s1[i].isDigit()) i++
                val start2 = j
                while (j < len2 && s2[j].isDigit()) j++

                val numStr1 = s1.substring(start1, i)
                val numStr2 = s2.substring(start2, j)

                var nonZero1 = 0
                while (nonZero1 < numStr1.length && numStr1[nonZero1] == '0') nonZero1++
                var nonZero2 = 0
                while (nonZero2 < numStr2.length && numStr2[nonZero2] == '0') nonZero2++

                val sig1 = numStr1.substring(nonZero1)
                val sig2 = numStr2.substring(nonZero2)

                if (sig1.length != sig2.length) {
                    return sig1.length.compareTo(sig2.length)
                }

                val cmp = sig1.compareTo(sig2)
                if (cmp != 0) return cmp

                if (numStr1.length != numStr2.length) {
                    return numStr1.length.compareTo(numStr2.length)
                }
            } else {
                val lc1 = c1.lowercaseChar()
                val lc2 = c2.lowercaseChar()
                if (lc1 != lc2) {
                    return lc1.compareTo(lc2)
                }
                i++
                j++
            }
        }

        return len1.compareTo(len2)
    }

    val STRING_COMPARATOR: Comparator<String> = this
    val FILE_COMPARATOR: Comparator<File> = Comparator { f1, f2 -> compare(f1?.name, f2?.name) }
    val PATH_COMPARATOR: Comparator<Path> = Comparator { p1, p2 -> compare(p1?.name, p2?.name) }
}

@JvmName("sortedFilesNaturally")
fun List<File>.sortedNaturally(): List<File> = sortedWith(NaturalOrderComparator.FILE_COMPARATOR)

@JvmName("sortedFileArrayNaturally")
fun Array<File>.sortedNaturally(): List<File> = sortedWith(NaturalOrderComparator.FILE_COMPARATOR)

@JvmName("sortedPathsNaturally")
fun List<Path>.sortedNaturally(): List<Path> = sortedWith(NaturalOrderComparator.PATH_COMPARATOR)

@JvmName("sortedStringsNaturally")
fun List<String>.sortedNaturally(): List<String> = sortedWith(NaturalOrderComparator.STRING_COMPARATOR)
