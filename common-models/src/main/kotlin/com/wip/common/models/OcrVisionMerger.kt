package com.wip.common.models

import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Utility to merge multiple OCR detections that fall within the same visual speech balloon or shape.
 */
object OcrVisionMerger {

    fun isHallucinationOrEmpty(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return true
        val clean = rawText.trim()
            .replace(Regex("(?i)<\\|/?(?:ref|box|det|quad|grounding|image|text)[^>]*\\|>"), "")
            .replace(Regex("(?i)\\b(?:image|figure|table|header|footer|background|watermark)\\s*\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]"), "")
            .replace(Regex("(?i)^\\s*(?:text|balloon|speech|dialogue|caption|title|paragraph|line)\\s*\\[\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]\\s*"), "")
            .trim()
        if (clean.isBlank()) return true
        if (!clean.any { it.isLetterOrDigit() }) return true

        val lower = clean.lowercase()
        val directMatches = setOf(
            "(no text)", "no text", "none", "n/a", "na", "empty", "nothing",
            "no dialogue", "no speech", "no speech bubble", "no speech bubbles",
            "no text detected", "no text found", "no visible text",
            "(nessun testo)", "nessun testo", "nessun dialogo",
            "1", "0", "null", "undefined"
        )
        if (lower in directMatches) return true

        val hallucinationRegexes = listOf(
            Regex("""(?i)^\s*\(?(?:no\s+text|nessun\s+testo|none|empty|nothing|no\s+dialogue|no\s+speech(?:\s+bubbles?)?)\)?\.?\s*$"""),
            Regex("""(?i)\b(?:the\s+image\s+contains\s+no\s+text|image\s+contains\s+no\s+visible\s+text|there\s+is\s+no\s+text\s+in\s+this\s+image|no\s+text\s+(?:found|detected|visible)\s+in\s+the\s+image)\b"""),
            Regex("""(?i)\b(?:the\s+ocr\s+result.*is\s+a\s+hallucination|does\s+not\s+correspond\s+to\s+any\s+content|absence\s+of\s+any\s+visible\s+text)\b"""),
            Regex("""(?i)\b(?:correct\s+ocr\s+output\s+must\s+reflect\s+the\s+absence\s+of|cannot\s+find\s+any\s+text\s+to\s+transcribe|no\s+transcription\s+available)\b""")
        )

        for (regex in hallucinationRegexes) {
            if (regex.containsMatchIn(lower)) {
                return true
            }
        }

        return false
    }

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
        if (ocrArea <= 1e-6) return false

        // Do not match full-screen fallback boxes to small vision objects
        if (ocrArea > 0.85) return false

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

        return (interArea / ocrArea) >= minCoverage
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

    private fun filterValidVisionObjects(objects: List<SegmentedObject>): List<SegmentedObject> {
        val ignoredLabels = setOf("watermark", "panel", "character", "face", "panel_border")
        return objects.filter { obj ->
            val lbl = obj.label.lowercase().trim()
            val isIgnored = ignoredLabels.any { lbl.contains(it) }
            val isRelevant = lbl.contains("balloon") || lbl.contains("bubble") || lbl.contains("text") ||
                    lbl.contains("dialogue") || lbl.contains("sfx") || lbl.isEmpty()
            !isIgnored && isRelevant
        }
    }

    fun mergeOcrResult(
        ocrData: OCRResult,
        visionResult: VisionResult,
        matchThreshold: Double = 0.20,
        separator: String = " "
    ): OCRResult {
        // Pre-filter invalid / hallucinated / empty OCR entries
        val validIndices = ocrData.texts.indices.filter { !isHallucinationOrEmpty(ocrData.texts[it]) }
        if (validIndices.isEmpty()) {
            return ocrData.copy(
                texts = emptyList(),
                bb = emptyList(),
                pageNumbers = emptyList(),
                pageNames = emptyList()
            )
        }

        val cleanOcr = OCRResult(
            texts = validIndices.map { ocrData.texts[it] },
            bb = validIndices.map { ocrData.bb.getOrElse(it) { listOf(0.0, 0.0, 0.0, 0.0) } },
            pageNumbers = validIndices.map { ocrData.pageNumbers.getOrElse(it) { 1 } },
            pageNames = validIndices.map { ocrData.pageNames.getOrElse(it) { "" } },
            failedFiles = ocrData.failedFiles
        )

        val n = cleanOcr.texts.size
        val validObjects = filterValidVisionObjects(visionResult.objects)

        if (n <= 1 || validObjects.isEmpty()) {
            return cleanOcr
        }

        val (imgW, imgH) = inferImageDimensions(cleanOcr.bb, visionResult)
        val normOcrBoxes = Array(n) { i ->
            val b = cleanOcr.bb.getOrNull(i) ?: listOf(0.0, 0.0, 0.0, 0.0)
            normalizeBox(b, imgW, imgH)
        }

        val dsu = DisjointSet(n)

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

        val uniqueRoots = groupMap.keys.toList()

        data class MergedItem(
            val text: String,
            val box: List<Double>,
            val pageNumber: Int,
            val pageName: String
        )

        val mergedItems = mutableListOf<MergedItem>()

        for (root in uniqueRoots) {
            val groupIndices = groupMap[root] ?: continue

            // Sort lines top-to-bottom strictly within the balloon
            groupIndices.sortWith(Comparator { a, b ->
                val bA = cleanOcr.bb.getOrNull(a) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val bB = cleanOcr.bb.getOrNull(b) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val yA = min(bA[0], bA[2])
                val yB = min(bB[0], bB[2])
                val cmpY = yA.compareTo(yB)
                if (cmpY != 0) cmpY else {
                    val xA = min(bA[1], bA[3])
                    val xB = min(bB[1], bB[3])
                    xA.compareTo(xB)
                }
            })

            val mergedText = groupIndices.joinToString(separator) { cleanOcr.texts[it] }.trim()
            if (isHallucinationOrEmpty(mergedText)) continue

            var minY = Double.MAX_VALUE
            var minX = Double.MAX_VALUE
            var maxY = Double.MIN_VALUE
            var maxX = Double.MIN_VALUE

            for (idx in groupIndices) {
                val b = cleanOcr.bb[idx]
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
                cleanOcr.bb[groupIndices[0]]
            }

            val firstIdx = groupIndices[0]
            val pageNum = if (firstIdx < cleanOcr.pageNumbers.size) cleanOcr.pageNumbers[firstIdx] else 1
            val pageName = if (firstIdx < cleanOcr.pageNames.size) cleanOcr.pageNames[firstIdx] else ""

            mergedItems.add(MergedItem(mergedText, mergedBox, pageNum, pageName))
        }

        // Sort all merged balloons top-to-bottom for reading order
        mergedItems.sortWith(compareBy({ it.box.getOrElse(0) { 0.0 } }, { it.box.getOrElse(1) { 0.0 } }))

        return ocrData.copy(
            texts = mergedItems.map { it.text },
            bb = mergedItems.map { it.box },
            pageNumbers = mergedItems.map { it.pageNumber },
            pageNames = mergedItems.map { it.pageName }
        )
    }

    fun mergeAdvancedOcrResult(
        ocrData: AdvancedOCRResult,
        visionResult: VisionResult,
        matchThreshold: Double = 0.20,
        separator: String = " "
    ): AdvancedOCRResult {
        // Pre-filter invalid / hallucinated / empty OCR entries
        val validIndices = ocrData.texts.indices.filter { !isHallucinationOrEmpty(ocrData.texts[it]) }
        if (validIndices.isEmpty()) {
            return ocrData.copy(
                texts = emptyList(),
                balloonBoxes = emptyList(),
                textBoxes = emptyList(),
                shapes = emptyList(),
                fontStyles = emptyList(),
                fontFamilies = emptyList(),
                textAngles = emptyList(),
                isSparse = emptyList(),
                textColors = emptyList(),
                hasBorder = emptyList(),
                borderColors = emptyList(),
                pageNumbers = emptyList(),
                pageNames = emptyList()
            )
        }

        val cleanOcr = AdvancedOCRResult(
            texts = validIndices.map { ocrData.texts[it] },
            balloonBoxes = validIndices.map { ocrData.balloonBoxes.getOrElse(it) { listOf(0.0, 0.0, 0.0, 0.0) } },
            textBoxes = validIndices.map { ocrData.textBoxes.getOrElse(it) { listOf(0.0, 0.0, 0.0, 0.0) } },
            shapes = validIndices.map { ocrData.shapes.getOrElse(it) { "oval" } },
            fontStyles = validIndices.map { ocrData.fontStyles.getOrElse(it) { "normal" } },
            fontFamilies = validIndices.map { ocrData.fontFamilies.getOrElse(it) { "AnimeAce2.0BB" } },
            textAngles = validIndices.map { ocrData.textAngles.getOrElse(it) { 0.0 } },
            isSparse = validIndices.map { ocrData.isSparse.getOrElse(it) { false } },
            textColors = validIndices.map { ocrData.textColors.getOrElse(it) { "#000000" } },
            hasBorder = validIndices.map { ocrData.hasBorder.getOrElse(it) { false } },
            borderColors = validIndices.map { ocrData.borderColors.getOrElse(it) { "#FFFFFF" } },
            pageNumbers = validIndices.map { ocrData.pageNumbers.getOrElse(it) { 1 } },
            pageNames = validIndices.map { ocrData.pageNames.getOrElse(it) { "" } },
            failedFiles = ocrData.failedFiles
        )

        val n = cleanOcr.texts.size
        val validObjects = filterValidVisionObjects(visionResult.objects)

        if (n <= 1 || validObjects.isEmpty()) {
            return cleanOcr
        }

        val allBoxes = cleanOcr.balloonBoxes.ifEmpty { cleanOcr.textBoxes }
        val (imgW, imgH) = inferImageDimensions(allBoxes, visionResult)
        val normBoxes = Array(n) { i ->
            val b = cleanOcr.balloonBoxes.getOrNull(i)?.takeIf { it.size >= 4 }
                ?: cleanOcr.textBoxes.getOrNull(i)
                ?: listOf(0.0, 0.0, 0.0, 0.0)
            normalizeBox(b, imgW, imgH)
        }

        val dsu = DisjointSet(n)

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

        val uniqueRoots = groupMap.keys.toList()

        data class MergedAdvItem(
            val text: String,
            val balloonBox: List<Double>,
            val textBox: List<Double>,
            val shape: String,
            val fontStyle: String,
            val fontFamily: String,
            val textAngle: Double,
            val isSparse: Boolean,
            val textColor: String,
            val hasBorder: Boolean,
            val borderColor: String,
            val pageNumber: Int,
            val pageName: String
        )

        val mergedItems = mutableListOf<MergedAdvItem>()

        for (root in uniqueRoots) {
            val groupIndices = groupMap[root] ?: continue

            // Sort top-to-bottom strictly by ymin, then by xmin
            groupIndices.sortWith(Comparator { a, b ->
                val bA = cleanOcr.textBoxes.getOrNull(a)?.takeIf { it.size >= 4 } ?: cleanOcr.balloonBoxes.getOrNull(a) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val bB = cleanOcr.textBoxes.getOrNull(b)?.takeIf { it.size >= 4 } ?: cleanOcr.balloonBoxes.getOrNull(b) ?: listOf(0.0, 0.0, 0.0, 0.0)
                val yA = min(bA[0], bA[2])
                val yB = min(bB[0], bB[2])
                val cmpY = yA.compareTo(yB)
                if (cmpY != 0) cmpY else {
                    val xA = min(bA[1], bA[3])
                    val xB = min(bB[1], bB[3])
                    xA.compareTo(xB)
                }
            })

            val mergedText = groupIndices.joinToString(separator) { cleanOcr.texts[it] }.trim()
            if (isHallucinationOrEmpty(mergedText)) continue

            var minBY = Double.MAX_VALUE; var minBX = Double.MAX_VALUE; var maxBY = Double.MIN_VALUE; var maxBX = Double.MIN_VALUE
            var minTY = Double.MAX_VALUE; var minTX = Double.MAX_VALUE; var maxTY = Double.MIN_VALUE; var maxTX = Double.MIN_VALUE

            for (idx in groupIndices) {
                val bBox = cleanOcr.balloonBoxes.getOrNull(idx)
                if (bBox != null && bBox.size >= 4) {
                    minBY = min(minBY, min(bBox[0], bBox[2]))
                    minBX = min(minBX, min(bBox[1], bBox[3]))
                    maxBY = max(maxBY, max(bBox[0], bBox[2]))
                    maxBX = max(maxBX, max(bBox[1], bBox[3]))
                }
                val tBox = cleanOcr.textBoxes.getOrNull(idx)
                if (tBox != null && tBox.size >= 4) {
                    minTY = min(minTY, min(tBox[0], tBox[2]))
                    minTX = min(minTX, min(tBox[1], tBox[3]))
                    maxTY = max(maxTY, max(tBox[0], tBox[2]))
                    maxTX = max(maxTX, max(tBox[1], tBox[3]))
                }
            }

            val mergedBalloonBox = if (minBY != Double.MAX_VALUE) listOf(minBY, minBX, maxBY, maxBX) else cleanOcr.balloonBoxes[groupIndices[0]]
            val mergedTextBox = if (minTY != Double.MAX_VALUE) listOf(minTY, minTX, maxTY, maxTX) else (cleanOcr.textBoxes.getOrNull(groupIndices[0]) ?: mergedBalloonBox)

            val firstIdx = groupIndices[0]
            mergedItems.add(
                MergedAdvItem(
                    text = mergedText,
                    balloonBox = mergedBalloonBox,
                    textBox = mergedTextBox,
                    shape = if (firstIdx < cleanOcr.shapes.size) cleanOcr.shapes[firstIdx] else "oval",
                    fontStyle = if (firstIdx < cleanOcr.fontStyles.size) cleanOcr.fontStyles[firstIdx] else "normal",
                    fontFamily = if (firstIdx < cleanOcr.fontFamilies.size) cleanOcr.fontFamilies[firstIdx] else "AnimeAce2.0BB",
                    textAngle = if (firstIdx < cleanOcr.textAngles.size) cleanOcr.textAngles[firstIdx] else 0.0,
                    isSparse = if (firstIdx < cleanOcr.isSparse.size) cleanOcr.isSparse[firstIdx] else false,
                    textColor = if (firstIdx < cleanOcr.textColors.size) cleanOcr.textColors[firstIdx] else "#000000",
                    hasBorder = if (firstIdx < cleanOcr.hasBorder.size) cleanOcr.hasBorder[firstIdx] else false,
                    borderColor = if (firstIdx < cleanOcr.borderColors.size) cleanOcr.borderColors[firstIdx] else "#FFFFFF",
                    pageNumber = if (firstIdx < cleanOcr.pageNumbers.size) cleanOcr.pageNumbers[firstIdx] else 1,
                    pageName = if (firstIdx < cleanOcr.pageNames.size) cleanOcr.pageNames[firstIdx] else ""
                )
            )
        }

        // Sort all merged balloons top-to-bottom for reading order
        mergedItems.sortWith(compareBy({ it.balloonBox.getOrElse(0) { 0.0 } }, { it.balloonBox.getOrElse(1) { 0.0 } }))

        return ocrData.copy(
            texts = mergedItems.map { it.text },
            balloonBoxes = mergedItems.map { it.balloonBox },
            textBoxes = mergedItems.map { it.textBox },
            shapes = mergedItems.map { it.shape },
            fontStyles = mergedItems.map { it.fontStyle },
            fontFamilies = mergedItems.map { it.fontFamily },
            textAngles = mergedItems.map { it.textAngle },
            isSparse = mergedItems.map { it.isSparse },
            textColors = mergedItems.map { it.textColor },
            hasBorder = mergedItems.map { it.hasBorder },
            borderColors = mergedItems.map { it.borderColor },
            pageNumbers = mergedItems.map { it.pageNumber },
            pageNames = mergedItems.map { it.pageName }
        )
    }

    private fun findMatchingVision(
        pageName: String,
        pageNum: Int,
        chapterVisionResult: ChapterVisionResult,
        visionMap: Map<String, VisionResult>
    ): VisionResult? {
        val rawName = pageName.lowercase()
        val baseName = File(pageName).nameWithoutExtension.lowercase()
        val numFromPageName = baseName.filter { it.isDigit() }.toIntOrNull()
        val trimmedZeros = baseName.trimStart('0')

        return visionMap[rawName]
            ?: visionMap[baseName]
            ?: (if (trimmedZeros.isNotEmpty()) visionMap[trimmedZeros] else null)
            ?: (if (numFromPageName != null) visionMap["num_$numFromPageName"] else null)
            ?: chapterVisionResult.results.firstOrNull { r ->
                val rClean = File(r.pageName).nameWithoutExtension.lowercase()
                val rNum = rClean.filter { it.isDigit() }.toIntOrNull()
                r.pageName.equals(pageName, ignoreCase = true) ||
                rClean.equals(baseName, ignoreCase = true) ||
                rClean.trimStart('0').equals(trimmedZeros, ignoreCase = true) ||
                (numFromPageName != null && rNum != null && numFromPageName == rNum)
            }
            ?: if (chapterVisionResult.results.size == 1) {
                chapterVisionResult.results[0]
            } else {
                chapterVisionResult.results.getOrNull(pageNum - 1)
            }
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
            val trimmedZeros = baseName.trimStart('0')
            if (trimmedZeros.isNotEmpty()) {
                visionMap[trimmedZeros] = r
            }
            val num = baseName.filter { it.isDigit() }.toIntOrNull()
            if (num != null) {
                visionMap["num_$num"] = r
            }
        }

        val pageGroups = mutableMapOf<String, MutableList<Int>>()
        for (i in ocrData.texts.indices) {
            val pageName = ocrData.pageNames.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: ocrData.pageNumbers.getOrNull(i)?.toString()
                ?: "page_1"
            pageGroups.getOrPut(pageName) { mutableListOf() }.add(i)
        }

        val allMergedTexts = mutableListOf<String>()
        val allMergedBb = mutableListOf<List<Double>>()
        val allMergedPageNumbers = mutableListOf<Int>()
        val allMergedPageNames = mutableListOf<String>()

        for ((pageName, indices) in pageGroups) {
            val pageNum = indices.firstOrNull()?.let { ocrData.pageNumbers.getOrNull(it) } ?: 1
            val visionResult = findMatchingVision(pageName, pageNum, chapterVisionResult, visionMap)

            val pageOcr = OCRResult(
                texts = indices.map { ocrData.texts[it] },
                bb = indices.map { ocrData.bb.getOrElse(it) { listOf(0.0, 0.0, 0.0, 0.0) } },
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
            val trimmedZeros = baseName.trimStart('0')
            if (trimmedZeros.isNotEmpty()) {
                visionMap[trimmedZeros] = r
            }
            val num = baseName.filter { it.isDigit() }.toIntOrNull()
            if (num != null) {
                visionMap["num_$num"] = r
            }
        }

        val pageGroups = mutableMapOf<String, MutableList<Int>>()
        for (i in ocrData.texts.indices) {
            val pageName = ocrData.pageNames.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: ocrData.pageNumbers.getOrNull(i)?.toString()
                ?: "page_1"
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
            val pageNum = indices.firstOrNull()?.let { ocrData.pageNumbers.getOrNull(it) } ?: 1
            val visionResult = findMatchingVision(pageName, pageNum, chapterVisionResult, visionMap)

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
