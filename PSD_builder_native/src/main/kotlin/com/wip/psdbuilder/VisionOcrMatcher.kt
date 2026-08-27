package com.wip.psdbuilder

import com.wip.common.models.PolygonPoint
import com.wip.common.models.SegmentedObject
import com.wip.kpsd.PsdBounds
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Result of matching an OCR text detection to a vision instance segmentation balloon.
 */
data class MatchedBalloonText(
    val text: String,
    val textIndex: Int,
    val ocrBox: DoubleArray,
    val ocrBalloonBox: DoubleArray?,
    val matchedBalloon: SegmentedObject?,
    val polygonPixels: List<Point2D.Double>?,
    val polygonBounds: PsdBounds?,
    val visualCenter: Point2D.Double?,
    val matchScore: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MatchedBalloonText

        if (text != other.text) return false
        if (textIndex != other.textIndex) return false
        if (!ocrBox.contentEquals(other.ocrBox)) return false
        if (ocrBalloonBox != null) {
            if (other.ocrBalloonBox == null) return false
            if (!ocrBalloonBox.contentEquals(other.ocrBalloonBox)) return false
        } else if (other.ocrBalloonBox != null) return false
        if (matchedBalloon != other.matchedBalloon) return false
        if (matchScore != other.matchScore) return false

        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + textIndex
        result = 31 * result + ocrBox.contentHashCode()
        result = 31 * result + (ocrBalloonBox?.contentHashCode() ?: 0)
        result = 31 * result + (matchedBalloon?.hashCode() ?: 0)
        result = 31 * result + matchScore.hashCode()
        return result
    }
}

/**
 * Matches OCR text items with instance segmentation speech balloons from Vision models.
 */
object VisionOcrMatcher {

    /**
     * Checks if a 2D point (px, py) is strictly inside a polygon using ray casting algorithm.
     */
    fun isPointInPolygon(px: Double, py: Double, polygon: List<Point2D.Double>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y

            val intersect = ((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi + 1e-9) + xi)
            if (intersect) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Calculates the bounding box intersection area.
     * Box format: [ymin, xmin, ymax, xmax]
     */
    fun boxIntersectionArea(a: DoubleArray, b: DoubleArray): Double {
        if (a.size < 4 || b.size < 4) return 0.0
        val ymin = max(a[0], b[0])
        val xmin = max(a[1], b[1])
        val ymax = min(a[2], b[2])
        val xmax = min(a[3], b[3])
        val w = max(0.0, xmax - xmin)
        val h = max(0.0, ymax - ymin)
        return w * h
    }

    /**
     * Calculates the area of a bounding box.
     */
    fun boxArea(box: DoubleArray): Double {
        if (box.size < 4) return 0.0
        val w = max(0.0, box[3] - box[1])
        val h = max(0.0, box[2] - box[0])
        return w * h
    }

    /**
     * Calculates Intersection over Union (IoU) between two bounding boxes.
     */
    fun boxIoU(a: DoubleArray, b: DoubleArray): Double {
        val inter = boxIntersectionArea(a, b)
        if (inter <= 0.0) return 0.0
        val union = boxArea(a) + boxArea(b) - inter
        if (union <= 0.0) return 0.0
        return inter / union
    }

    /**
     * Estimates the containment ratio IoA = Area(Box ∩ Polygon) / Area(Box) using grid sampling.
     */
    fun boxPolygonContainment(box: DoubleArray, polygon: List<Point2D.Double>): Double {
        if (box.size < 4 || polygon.size < 3) return 0.0
        val ymin = box[0]
        val xmin = box[1]
        val ymax = box[2]
        val xmax = box[3]
        val w = xmax - xmin
        val h = ymax - ymin
        if (w <= 0.0 || h <= 0.0) return 0.0

        val samplesX = 5
        val samplesY = 5
        var insideCount = 0
        val total = samplesX * samplesY

        for (iy in 0 until samplesY) {
            val sy = ymin + (iy + 0.5) * (h / samplesY)
            for (ix in 0 until samplesX) {
                val sx = xmin + (ix + 0.5) * (w / samplesX)
                if (isPointInPolygon(sx, sy, polygon)) {
                    insideCount++
                }
            }
        }
        return insideCount.toDouble() / total.toDouble()
    }

    /**
     * Computes the visual centroid of a polygon.
     */
    fun computeVisualCenter(polygon: List<Point2D.Double>): Point2D.Double {
        if (polygon.isEmpty()) return Point2D.Double(0.0, 0.0)
        var sumX = 0.0
        var sumY = 0.0
        var signedArea = 0.0

        for (i in polygon.indices) {
            val p0 = polygon[i]
            val p1 = polygon[(i + 1) % polygon.size]
            val a = p0.x * p1.y - p1.x * p0.y
            signedArea += a
            sumX += (p0.x + p1.x) * a
            sumY += (p0.y + p1.y) * a
        }

        signedArea *= 0.5
        if (abs(signedArea) > 1e-6) {
            val cx = sumX / (6.0 * signedArea)
            val cy = sumY / (6.0 * signedArea)
            return Point2D.Double(cx, cy)
        }

        // Fallback to arithmetic mean
        val meanX = polygon.sumOf { it.x } / polygon.size
        val meanY = polygon.sumOf { it.y } / polygon.size
        return Point2D.Double(meanX, meanY)
    }

    /**
     * Converts normalized [PolygonPoint] vertices to absolute pixel coordinates.
     */
    fun toPixelPolygon(polygon: List<PolygonPoint>, width: Double, height: Double): List<Point2D.Double> {
        return polygon.map { pt ->
            val px = if (pt.x <= 1.0) pt.x * width else pt.x
            val py = if (pt.y <= 1.0) pt.y * height else pt.y
            Point2D.Double(px, py)
        }
    }

    /**
     * Converts normalized or absolute bounding box coordinates to absolute pixel values.
     */
    fun toAbsoluteBox(box: List<Double>, width: Double, height: Double): DoubleArray {
        if (box.size < 4) return doubleArrayOf()
        val ymin = if (box[0] in 0.0..1.0 && box[2] in 0.0..1.0) box[0] * height else box[0]
        val xmin = if (box[1] in 0.0..1.0 && box[3] in 0.0..1.0) box[1] * width else box[1]
        val ymax = if (box[2] in 0.0..1.0 && box[0] in 0.0..1.0) box[2] * height else box[2]
        val xmax = if (box[3] in 0.0..1.0 && box[1] in 0.0..1.0) box[3] * width else box[3]
        return doubleArrayOf(ymin, xmin, ymax, xmax)
    }

    /**
     * Matches all OCR items against available Vision balloon segmentations.
     */
    fun match(
        texts: List<String>,
        ocrBoxes: List<List<Double>>,
        ocrBalloonBoxes: List<List<Double>>? = null,
        visionObjects: List<SegmentedObject>? = null,
        imageWidth: Double,
        imageHeight: Double,
        matchThreshold: Double = 0.25
    ): List<MatchedBalloonText> {
        val results = mutableListOf<MatchedBalloonText>()
        val balloonObjects = visionObjects?.filter { obj ->
            val label = obj.label.trim().lowercase()
            label == "balloon" || label == "speech_balloon" || label == "thought_balloon" || label.contains("balloon")
        } ?: emptyList()

        for ((index, text) in texts.withIndex()) {
            val ocrBoxRaw = ocrBoxes.getOrNull(index) ?: emptyList()
            val ocrBalloonBoxRaw = ocrBalloonBoxes?.getOrNull(index)

            val ocrBox = toAbsoluteBox(ocrBoxRaw, imageWidth, imageHeight)
            val ocrBalloonBox = ocrBalloonBoxRaw?.let { toAbsoluteBox(it, imageWidth, imageHeight) }

            if (balloonObjects.isEmpty() || ocrBox.size < 4) {
                results.add(
                    MatchedBalloonText(
                        text = text,
                        textIndex = index,
                        ocrBox = ocrBox,
                        ocrBalloonBox = ocrBalloonBox,
                        matchedBalloon = null,
                        polygonPixels = null,
                        polygonBounds = null,
                        visualCenter = null,
                        matchScore = 0.0
                    )
                )
                continue
            }

            val textCenterX = (ocrBox[1] + ocrBox[3]) / 2.0
            val textCenterY = (ocrBox[0] + ocrBox[2]) / 2.0

            var bestObject: SegmentedObject? = null
            var bestScore = 0.0
            var bestPixelPoly: List<Point2D.Double>? = null
            var bestBounds: PsdBounds? = null
            var bestVisualCenter: Point2D.Double? = null

            for (obj in balloonObjects) {
                val segBox = doubleArrayOf(
                    obj.box.ymin * imageHeight,
                    obj.box.xmin * imageWidth,
                    obj.box.ymax * imageHeight,
                    obj.box.xmax * imageWidth
                )

                val pixelPoly = if (obj.polygon.size >= 3) {
                    toPixelPolygon(obj.polygon, imageWidth, imageHeight)
                } else {
                    listOf(
                        Point2D.Double(segBox[1], segBox[0]),
                        Point2D.Double(segBox[3], segBox[0]),
                        Point2D.Double(segBox[3], segBox[2]),
                        Point2D.Double(segBox[1], segBox[2])
                    )
                }

                val segCenterX = (segBox[1] + segBox[3]) / 2.0
                val segCenterY = (segBox[0] + segBox[2]) / 2.0
                val diag = sqrt(imageWidth * imageWidth + imageHeight * imageHeight)
                val dist = sqrt((textCenterX - segCenterX) * (textCenterX - segCenterX) + (textCenterY - segCenterY) * (textCenterY - segCenterY)) / diag
                val distScore = (1.0 - dist).coerceIn(0.0, 1.0)

                val score: Double
                if (ocrBalloonBox != null && ocrBalloonBox.size >= 4) {
                    val boxIou = boxIoU(ocrBalloonBox, segBox)
                    val containment = boxPolygonContainment(ocrBox, pixelPoly)
                    score = 0.6 * boxIou + 0.4 * containment
                } else {
                    val boxIou = boxIoU(ocrBox, segBox)
                    val containment = boxPolygonContainment(ocrBox, pixelPoly)
                    val isCenterInside = isPointInPolygon(textCenterX, textCenterY, pixelPoly)
                    val centerBonus = if (isCenterInside) 0.2 else 0.0
                    score = 0.5 * containment + 0.3 * boxIou + 0.2 * distScore + centerBonus
                }

                if (score > bestScore) {
                    bestScore = score
                    bestObject = obj
                    bestPixelPoly = pixelPoly
                    bestBounds = PsdBounds(
                        left = segBox[1].toFloat(),
                        top = segBox[0].toFloat(),
                        right = segBox[3].toFloat(),
                        bottom = segBox[2].toFloat()
                    )
                    bestVisualCenter = computeVisualCenter(pixelPoly)
                }
            }

            if (bestScore >= matchThreshold && bestObject != null) {
                results.add(
                    MatchedBalloonText(
                        text = text,
                        textIndex = index,
                        ocrBox = ocrBox,
                        ocrBalloonBox = ocrBalloonBox,
                        matchedBalloon = bestObject,
                        polygonPixels = bestPixelPoly,
                        polygonBounds = bestBounds,
                        visualCenter = bestVisualCenter,
                        matchScore = bestScore
                    )
                )
            } else {
                results.add(
                    MatchedBalloonText(
                        text = text,
                        textIndex = index,
                        ocrBox = ocrBox,
                        ocrBalloonBox = ocrBalloonBox,
                        matchedBalloon = null,
                        polygonPixels = null,
                        polygonBounds = null,
                        visualCenter = null,
                        matchScore = bestScore
                    )
                )
            }
        }

        return results
    }
}
