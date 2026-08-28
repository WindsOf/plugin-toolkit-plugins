package com.wip.common.models

import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Utility to merge multiple OCR detections that fall within the same visual speech balloon or shape.
 */
object OcrVisionMerger {

    private class DisjointSet(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val nxt = parent[curr]
                parent[curr] = root
                curr = nxt
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }
    }

    private fun isPointInPolygon(px: Double, py: Double, polygon: List<PolygonPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y

            val intersect = ((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersect) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun normalizeBox(box: List<Double>, imgW: Double, imgH: Double): DoubleArray {
        if (box.size < 4) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var y0 = min(box[0], box[2])
        var x0 = min(box[1], box[3])
        var y1 = max(box[0], box[2])
        var x1 = max(box[1], box[3])

        val isPixel = (y1 > 1.0 || x1 > 1.0)
        if (isPixel) {
            val effectiveH = if (imgH > 0.0) imgH else 1.0
            val effectiveW = if (imgW > 0.0) imgW else 1.0
            y0 /= effectiveH
            x0 /= effectiveW
            y1 /= effectiveH
            x1 /= effectiveW
        }
        return doubleArrayOf(y0.coerceIn(0.0, 1.0), x0.coerceIn(0.0, 1.0), y1.coerceIn(0.0, 1.0), x1.coerceIn(0.0, 1.0))
    }

    private fun normalizeVisionBox(box: DetectionBox, imgW: Double, imgH: Double): DoubleArray {
        var y0 = min(box.ymin, box.ymax)
        var x0 = min(box.xmin, box.xmax)
        var y1 = max(box.ymin, box.ymax)
        var x1 = max(box.xmin, box.xmax)

        val isPixel = (y1 > 1.0 || x1 > 1.0)
        if (isPixel) {
            val effectiveH = if (imgH > 0.0) imgH else 1.0
            val effectiveW = if (imgW > 0.0) imgW else 1.0
            y0 /= effectiveH
            x0 /= effectiveW
            y1 /= effectiveH
            x1 /= effectiveW
        }
        return doubleArrayOf(y0.coerceIn(0.0, 1.0), x0.coerceIn(0.0, 1.0), y1.coerceIn(0.0, 1.0), x1.coerceIn(0.0, 1.0))
    }

    private fun normalizePolygon(polygon: List<PolygonPoint>, imgW: Double, imgH: Double): List<PolygonPoint> {
        if (polygon.isEmpty()) return emptyList()
        val isPixel = polygon.any { it.x > 1.0 || it.y > 1.0 }
        if (!isPixel) return polygon
        val effectiveH = if (imgH > 0.0) imgH else 1.0
        val effectiveW = if (imgW > 0.0) imgW else 1.0
        return polygon.map {
            PolygonPoint(
                x = (it.x / effectiveW).coerceIn(0.0, 1.0),
                y = (it.y / effectiveH).coerceIn(0.0, 1.0)
            )
        }
    }

    private fun isContainedInVisionObject(
        normOcr: DoubleArray,
        visionObj: SegmentedObject,
        imgW: Double,
        imgH: Double,
        minCoverage: Double = 0.20
    ): Boolean {
        val ocrY0 = normOcr[0]
        val ocrX0 = normOcr[1]
        val ocrY1 = normOcr[2]
        val ocrX1 = normOcr[3]
        val ocrArea = (ocrX1 - ocrX0) * (ocrY1 - ocrY0)
        val cx = (ocrX0 + ocrX1) / 2.0
        val cy = (ocrY0 + ocrY1) / 2.0

        val normPoly = normalizePolygon(visionObj.polygon, imgW, imgH)
        if (normPoly.size >= 3) {
            if (isPointInPolygon(cx, cy, normPoly)) {
                return true
            }
        }

        val normV = normalizeVisionBox(visionObj.box, imgW, imgH)
        val vY0 = normV[0]
        val vX0 = normV[1]
        val vY1 = normV[2]
        val vX1 = normV[3]

        if (cx in vX0..vX1 && cy in vY0..vY1) {
            return true
        }

        val interXmin = max(ocrX0, vX0)
        val interYmin = max(ocrY0, vY0)
        val interXmax = min(ocrX1, vX1)
        val interYmax = min(ocrY1, vY1)
        val interW = max(0.0, interXmax - interXmin)
        val interH = max(0.0, interYmax - interYmin)
        val interArea = interW * interH

        return if (ocrArea > 1e-6) (interArea / ocrArea) >= minCoverage else false
    }

    private fun inferImageDimensions(ocrBoxes: List<List<Double>>, visionResult: VisionResult): Pair<Double, Double> {
        var imgW = visionResult.imageWidth.toDouble()
        var imgH = visionResult.imageHeight.toDouble()

        if (imgW <= 0.0 || imgH <= 0.0) {
            var maxOcrX = 0.0
            var maxOcrY = 0.0
            for (b in ocrBoxes) {
                if (b.size >= 4) {
                    maxOcrY = max(maxOcrY, max(b[0], b[2]))
                    maxOcrX = max(maxOcrX, max(b[1], b[3]))
                }
            }
            for (obj in visionResult.objects) {
                maxOcrY = max(maxOcrY, max(obj.box.ymin, obj.box.ymax))
                maxOcrX = max(maxOcrX, max(obj.box.xmin, obj.box.xmax))
                for (p in obj.polygon) {
                    maxOcrY = max(maxOcrY, p.y)
                    maxOcrX = max(maxOcrX, p.x)
                }
            }
            if (maxOcrX > 1.0) imgW = maxOcrX
            if (maxOcrY > 1.0) imgH = maxOcrY
        }
        return Pair(imgW, imgH)
    }

    fun mergeOcrResult(
        ocrData: OCRResult,
        visionResult: VisionResult,
        matchThreshold: Double = 0.20,
        separator: String = " "
    ): OCRResult {
        val n = ocrData.texts.size
        if (n <= 1 || visionResult.objects.isEmpty()) {
            return ocrData
        }

        val (imgW, imgH) = inferImageDimensions(ocrData.bb, visionResult)
        val normOcrBoxes = Array(n) { i ->
            val b = ocrData.bb.getOrNull(i) ?: listOf(0.0, 0.0, 0.0, 0.0)
            normalizeBox(b, imgW, imgH)
        }

        val dsu = DisjointSet(n)
        val validObjects = visionResult.objects.filter {
            !it.label.contains("watermark", ignoreCase = true)
        }

        for (vObj in validObjects) {
            val matchingIndices = mutableListOf<Int>()
            for (i in 0 until n) {
                if (isContainedInVisionObject(normOcrBoxes[i], vObj, imgW, imgH, matchThreshold)) {
                    matchingIndices.add(i)
                }
            }
            if (matchingIndices.size > 1) {
                val first = matchingIndices[0]
                for (k in 1 until matchingIndices.size) {
                    dsu.union(first, matchingIndices[k])
                }
            }
        }

        val groupMap = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = dsu.find(i)
            groupMap.getOrPut(root) { mutableListOf() }.add(i)
        }

        val orderedRoots = mutableListOf<Int>()
        for (i in 0 until n) {
            val root = dsu.find(i)
            if (!orderedRoots.contains(root)) {
                orderedRoots.add(root)
            }
        }

        val newTexts = mutableListOf<String>()
        val newBoxes = mutableListOf<List<Double>>()
        val newPageNumbers = mutableListOf<Int>()
        val newPageNames = mutableListOf<String>()

        for (root in orderedRoots) {
            val groupIndices = groupMap[root] ?: continue

            // Sort top-to-bottom strictly by ymin, then by xmin
            groupIndices.sortWith(Comparator { a, b ->
                val bA = ocrData.bb.getOrNull(a) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val bB = ocrData.bb.getOrNull(b) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val yA = min(bA[0], bA[2])
                val yB = min(bB[0], bB[2])
                val cmpY = yA.compareTo(yB)
                if (cmpY != 0) cmpY else {
                    val xA = min(bA[1], bA[3])
                    val xB = min(bB[1], bB[3])
                    xA.compareTo(xB)
                }
            })

            val mergedText = groupIndices.joinToString(separator) { ocrData.texts[it] }

            var minY = Double.MAX_VALUE
            var minX = Double.MAX_VALUE
            var maxY = Double.MIN_VALUE
            var maxX = Double.MIN_VALUE

            for (idx in groupIndices) {
                val b = ocrData.bb[idx]
                if (b.size >= 4) {
                    minY = min(minY, min(b[0], b[2]))
                    minX = min(minX, min(b[1], b[3]))
                    maxY = max(maxY, max(b[0], b[2]))
                    maxX = max(maxX, max(b[1], b[3]))
                }
            }

            val mergedBox = if (minY != Double.MAX_VALUE) {
                listOf(minY, minX, maxY, maxX)
            } else {
                ocrData.bb[groupIndices[0]]
            }

            val firstIdx = groupIndices[0]
            newTexts.add(mergedText)
            newBoxes.add(mergedBox)
            if (firstIdx < ocrData.pageNumbers.size) newPageNumbers.add(ocrData.pageNumbers[firstIdx])
            if (firstIdx < ocrData.pageNames.size) newPageNames.add(ocrData.pageNames[firstIdx])
        }

        return ocrData.copy(
            texts = newTexts,
            bb = newBoxes,
            pageNumbers = newPageNumbers,
            pageNames = newPageNames
        )
    }

    fun mergeAdvancedOcrResult(
        ocrData: AdvancedOCRResult,
        visionResult: VisionResult,
        matchThreshold: Double = 0.20,
        separator: String = " "
    ): AdvancedOCRResult {
        val n = ocrData.texts.size
        if (n <= 1 || visionResult.objects.isEmpty()) {
            return ocrData
        }

        val allBoxes = ocrData.balloonBoxes.ifEmpty { ocrData.textBoxes }
        val (imgW, imgH) = inferImageDimensions(allBoxes, visionResult)
        val normBoxes = Array(n) { i ->
            val b = ocrData.balloonBoxes.getOrNull(i)?.takeIf { it.size >= 4 }
                ?: ocrData.textBoxes.getOrNull(i)
                ?: listOf(0.0, 0.0, 0.0, 0.0)
            normalizeBox(b, imgW, imgH)
        }

        val dsu = DisjointSet(n)
        val validObjects = visionResult.objects.filter {
            !it.label.contains("watermark", ignoreCase = true)
        }

        for (vObj in validObjects) {
            val matchingIndices = mutableListOf<Int>()
            for (i in 0 until n) {
                if (isContainedInVisionObject(normBoxes[i], vObj, imgW, imgH, matchThreshold)) {
                    matchingIndices.add(i)
                }
            }
            if (matchingIndices.size > 1) {
                val first = matchingIndices[0]
                for (k in 1 until matchingIndices.size) {
                    dsu.union(first, matchingIndices[k])
                }
            }
        }

        val groupMap = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = dsu.find(i)
            groupMap.getOrPut(root) { mutableListOf() }.add(i)
        }

        val orderedRoots = mutableListOf<Int>()
        for (i in 0 until n) {
            val root = dsu.find(i)
            if (!orderedRoots.contains(root)) {
                orderedRoots.add(root)
            }
        }

        val newTexts = mutableListOf<String>()
        val newBalloonBoxes = mutableListOf<List<Double>>()
        val newTextBoxes = mutableListOf<List<Double>>()
        val newShapes = mutableListOf<String>()
        val newFontStyles = mutableListOf<String>()
        val newFontFamilies = mutableListOf<String>()
        val newTextAngles = mutableListOf<Double>()
        val newIsSparse = mutableListOf<Boolean>()
        val newTextColors = mutableListOf<String>()
        val newHasBorder = mutableListOf<Boolean>()
        val newBorderColors = mutableListOf<String>()
        val newPageNumbers = mutableListOf<Int>()
        val newPageNames = mutableListOf<String>()

        for (root in orderedRoots) {
            val groupIndices = groupMap[root] ?: continue

            // Sort top-to-bottom strictly by ymin, then by xmin
            groupIndices.sortWith(Comparator { a, b ->
                val bA = ocrData.textBoxes.getOrNull(a)?.takeIf { it.size >= 4 } ?: ocrData.balloonBoxes.getOrNull(a) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val bB = ocrData.textBoxes.getOrNull(b)?.takeIf { it.size >= 4 } ?: ocrData.balloonBoxes.getOrNull(b) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val yA = min(bA[0], bA[2])
                val yB = min(bB[0], bB[2])
                val cmpY = yA.compareTo(yB)
                if (cmpY != 0) cmpY else {
                    val xA = min(bA[1], bA[3])
                    val xB = min(bB[1], bB[3])
                    xA.compareTo(xB)
                }
            })

            val mergedText = groupIndices.joinToString(separator) { ocrData.texts[it] }

            var minBY = Double.MAX_VALUE; var minBX = Double.MAX_VALUE; var maxBY = Double.MIN_VALUE; var maxBX = Double.MIN_VALUE
            var minTY = Double.MAX_VALUE; var minTX = Double.MAX_VALUE; var maxTY = Double.MIN_VALUE; var maxTX = Double.MIN_VALUE

            for (idx in groupIndices) {
                val bBox = ocrData.balloonBoxes.getOrNull(idx)
                if (bBox != null && bBox.size >= 4) {
                    minBY = min(minBY, min(bBox[0], bBox[2]))
                    minBX = min(minBX, min(bBox[1], bBox[3]))
                    maxBY = max(maxBY, max(bBox[0], bBox[2]))
                    maxBX = max(maxBX, max(bBox[1], bBox[3]))
                }
                val tBox = ocrData.textBoxes.getOrNull(idx)
                if (tBox != null && tBox.size >= 4) {
                    minTY = min(minTY, min(tBox[0], tBox[2]))
                    minTX = min(minTX, min(tBox[1], tBox[3]))
                    maxTY = max(maxTY, max(tBox[0], tBox[2]))
                    maxTX = max(maxTX, max(tBox[1], tBox[3]))
                }
            }

            val mergedBalloonBox = if (minBY != Double.MAX_VALUE) listOf(minBY, minBX, maxBY, maxBX) else ocrData.balloonBoxes[groupIndices[0]]
            val mergedTextBox = if (minTY != Double.MAX_VALUE) listOf(minTY, minTX, maxTY, maxTX) else (ocrData.textBoxes.getOrNull(groupIndices[0]) ?: mergedBalloonBox)

            val firstIdx = groupIndices[0]
            newTexts.add(mergedText)
            newBalloonBoxes.add(mergedBalloonBox)
            newTextBoxes.add(mergedTextBox)
            if (firstIdx < ocrData.shapes.size) newShapes.add(ocrData.shapes[firstIdx])
            if (firstIdx < ocrData.fontStyles.size) newFontStyles.add(ocrData.fontStyles[firstIdx])
            if (firstIdx < ocrData.fontFamilies.size) newFontFamilies.add(ocrData.fontFamilies[firstIdx])
            if (firstIdx < ocrData.textAngles.size) newTextAngles.add(ocrData.textAngles[firstIdx])
            if (firstIdx < ocrData.isSparse.size) newIsSparse.add(ocrData.isSparse[firstIdx])
            if (firstIdx < ocrData.textColors.size) newTextColors.add(ocrData.textColors[firstIdx])
            if (firstIdx < ocrData.hasBorder.size) newHasBorder.add(ocrData.hasBorder[firstIdx])
            if (firstIdx < ocrData.borderColors.size) newBorderColors.add(ocrData.borderColors[firstIdx])
            if (firstIdx < ocrData.pageNumbers.size) newPageNumbers.add(ocrData.pageNumbers[firstIdx])
            if (firstIdx < ocrData.pageNames.size) newPageNames.add(ocrData.pageNames[firstIdx])
        }

        return ocrData.copy(
            texts = newTexts,
            balloonBoxes = newBalloonBoxes,
            textBoxes = newTextBoxes,
            shapes = newShapes,
            fontStyles = newFontStyles,
            fontFamilies = newFontFamilies,
            textAngles = newTextAngles,
            isSparse = newIsSparse,
            textColors = newTextColors,
            hasBorder = newHasBorder,
            borderColors = newBorderColors,
            pageNumbers = newPageNumbers,
            pageNames = newPageNames
        )
    }

    fun mergeChapterOcrResult(
        ocrData: OCRResult,
        chapterVisionResult: ChapterVisionResult,
        matchThreshold: Double = 0.20,
        separator: String = " "
    ): OCRResult {
        if (ocrData.texts.isEmpty() || chapterVisionResult.results.isEmpty()) {
            return ocrData
        }

        val visionMap = mutableMapOf<String, VisionResult>()
        for (r in chapterVisionResult.results) {
            val rawName = r.pageName.lowercase()
            visionMap[rawName] = r
            val baseName = File(r.pageName).nameWithoutExtension.lowercase()
            visionMap[baseName] = r
        }

        val pageGroups = mutableMapOf<String, MutableList<Int>>()
        for (i in ocrData.texts.indices) {
            val pageName = ocrData.pageNames.getOrNull(i) ?: ""
            pageGroups.getOrPut(pageName) { mutableListOf() }.add(i)
        }

        val allMergedTexts = mutableListOf<String>()
        val allMergedBb = mutableListOf<List<Double>>()
        val allMergedPageNumbers = mutableListOf<Int>()
        val allMergedPageNames = mutableListOf<String>()

        for ((pageName, indices) in pageGroups) {
            val rawName = pageName.lowercase()
            val baseName = File(pageName).nameWithoutExtension.lowercase()
            val pageNum = indices.firstOrNull()?.let { ocrData.pageNumbers.getOrNull(it) } ?: 1

            val visionResult = visionMap[rawName]
                ?: visionMap[baseName]
                ?: chapterVisionResult.results.firstOrNull { it.pageName.equals(pageName, ignoreCase = true) }
                ?: if (chapterVisionResult.results.size == 1) {
                    chapterVisionResult.results[0]
                } else {
                    chapterVisionResult.results.getOrNull(pageNum - 1)
                }

            val pageOcr = OCRResult(
                texts = indices.map { ocrData.texts[it] },
                bb = indices.map { ocrData.bb[it] },
                pageNumbers = indices.map { ocrData.pageNumbers.getOrElse(it) { pageNum } },
                pageNames = indices.map { ocrData.pageNames.getOrElse(it) { pageName } },
                failedFiles = emptyList()
            )

            val mergedPageOcr = if (visionResult != null) {
                mergeOcrResult(pageOcr, visionResult, matchThreshold, separator)
            } else {
                pageOcr
            }

            allMergedTexts.addAll(mergedPageOcr.texts)
            allMergedBb.addAll(mergedPageOcr.bb)
            allMergedPageNumbers.addAll(mergedPageOcr.pageNumbers)
            allMergedPageNames.addAll(mergedPageOcr.pageNames)
        }

        return ocrData.copy(
            texts = allMergedTexts,
            bb = allMergedBb,
            pageNumbers = allMergedPageNumbers,
            pageNames = allMergedPageNames
        )
    }

    fun mergeChapterAdvancedOcrResult(
        ocrData: AdvancedOCRResult,
        chapterVisionResult: ChapterVisionResult,
        matchThreshold: Double = 0.20,
        separator: String = " "
    ): AdvancedOCRResult {
        if (ocrData.texts.isEmpty() || chapterVisionResult.results.isEmpty()) {
            return ocrData
        }

        val visionMap = mutableMapOf<String, VisionResult>()
        for (r in chapterVisionResult.results) {
            val rawName = r.pageName.lowercase()
            visionMap[rawName] = r
            val baseName = File(r.pageName).nameWithoutExtension.lowercase()
            visionMap[baseName] = r
        }

        val pageGroups = mutableMapOf<String, MutableList<Int>>()
        for (i in ocrData.texts.indices) {
            val pageName = ocrData.pageNames.getOrNull(i) ?: ""
            pageGroups.getOrPut(pageName) { mutableListOf() }.add(i)
        }

        val allTexts = mutableListOf<String>()
        val allBalloonBoxes = mutableListOf<List<Double>>()
        val allTextBoxes = mutableListOf<List<Double>>()
        val allShapes = mutableListOf<String>()
        val allFontStyles = mutableListOf<String>()
        val allFontFamilies = mutableListOf<String>()
        val allTextAngles = mutableListOf<Double>()
        val allIsSparse = mutableListOf<Boolean>()
        val allTextColors = mutableListOf<String>()
        val allHasBorder = mutableListOf<Boolean>()
        val allBorderColors = mutableListOf<String>()
        val allPageNumbers = mutableListOf<Int>()
        val allPageNames = mutableListOf<String>()

        for ((pageName, indices) in pageGroups) {
            val rawName = pageName.lowercase()
            val baseName = File(pageName).nameWithoutExtension.lowercase()
            val pageNum = indices.firstOrNull()?.let { ocrData.pageNumbers.getOrNull(it) } ?: 1

            val visionResult = visionMap[rawName]
                ?: visionMap[baseName]
                ?: chapterVisionResult.results.firstOrNull { it.pageName.equals(pageName, ignoreCase = true) }
                ?: if (chapterVisionResult.results.size == 1) {
                    chapterVisionResult.results[0]
                } else {
                    chapterVisionResult.results.getOrNull(pageNum - 1)
                }

            val pageAdvOcr = AdvancedOCRResult(
                texts = indices.map { ocrData.texts[it] },
                balloonBoxes = indices.map { ocrData.balloonBoxes.getOrElse(it) { emptyList() } },
                textBoxes = indices.map { ocrData.textBoxes.getOrElse(it) { emptyList() } },
                shapes = indices.map { ocrData.shapes.getOrElse(it) { "oval" } },
                fontStyles = indices.map { ocrData.fontStyles.getOrElse(it) { "normal" } },
                fontFamilies = indices.map { ocrData.fontFamilies.getOrElse(it) { "AnimeAce2.0BB" } },
                textAngles = indices.map { ocrData.textAngles.getOrElse(it) { 0.0 } },
                isSparse = indices.map { ocrData.isSparse.getOrElse(it) { false } },
                textColors = indices.map { ocrData.textColors.getOrElse(it) { "#000000" } },
                hasBorder = indices.map { ocrData.hasBorder.getOrElse(it) { false } },
                borderColors = indices.map { ocrData.borderColors.getOrElse(it) { "#FFFFFF" } },
                pageNumbers = indices.map { ocrData.pageNumbers.getOrElse(it) { pageNum } },
                pageNames = indices.map { ocrData.pageNames.getOrElse(it) { pageName } },
                failedFiles = emptyList()
            )

            val mergedPageOcr = if (visionResult != null) {
                mergeAdvancedOcrResult(pageAdvOcr, visionResult, matchThreshold, separator)
            } else {
                pageAdvOcr
            }

            allTexts.addAll(mergedPageOcr.texts)
            allBalloonBoxes.addAll(mergedPageOcr.balloonBoxes)
            allTextBoxes.addAll(mergedPageOcr.textBoxes)
            allShapes.addAll(mergedPageOcr.shapes)
            allFontStyles.addAll(mergedPageOcr.fontStyles)
            allFontFamilies.addAll(mergedPageOcr.fontFamilies)
            allTextAngles.addAll(mergedPageOcr.textAngles)
            allIsSparse.addAll(mergedPageOcr.isSparse)
            allTextColors.addAll(mergedPageOcr.textColors)
            allHasBorder.addAll(mergedPageOcr.hasBorder)
            allBorderColors.addAll(mergedPageOcr.borderColors)
            allPageNumbers.addAll(mergedPageOcr.pageNumbers)
            allPageNames.addAll(mergedPageOcr.pageNames)
        }

        return ocrData.copy(
            texts = allTexts,
            balloonBoxes = allBalloonBoxes,
            textBoxes = allTextBoxes,
            shapes = allShapes,
            fontStyles = allFontStyles,
            fontFamilies = allFontFamilies,
            textAngles = allTextAngles,
            isSparse = allIsSparse,
            textColors = allTextColors,
            hasBorder = allHasBorder,
            borderColors = allBorderColors,
            pageNumbers = allPageNumbers,
            pageNames = allPageNames
        )
    }
}
