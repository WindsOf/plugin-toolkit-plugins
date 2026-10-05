package com.wip.psdbuilder

import com.wip.kpsd.PsdBounds
import com.wip.kpsd.TextBoundary
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Custom [TextBoundary] implementation that dynamically constrains text line widths
 * to the interior geometry of an arbitrary speech balloon polygon.
 */
class PolygonTextBoundary(
    val polygon: List<Point2D.Double>,
    val padding: Float = 0f,
    visualCenter: Point2D.Double? = null
) : TextBoundary {

    val visualCenter: Point2D.Double? = visualCenter ?: computeCentroid(polygon)

    /**
     * Calculates the maximum available text width at a given vertical offset [y]
     * relative to the shape center.
     *
     * @param y Vertical offset relative to the shape's visual center (0 is center).
     * @param bounds The encompassing bounding box for the text layer.
     * @return Available horizontal width in pixels.
     */
    override fun getAvailableWidth(y: Float, bounds: PsdBounds): Float {
        if (polygon.size < 3) {
            val usableHeight = bounds.height - (padding * 2f)
            val usableWidth = bounds.width - (padding * 2f)
            val dy = abs(y)
            if (dy >= usableHeight / 2f) return 0f
            return max(0f, usableWidth)
        }

        val centerY = visualCenter?.y?.toFloat() ?: (bounds.top + bounds.height / 2f)
        val centerX = visualCenter?.x ?: ((bounds.left + bounds.right) / 2f).toDouble()
        val scanlineY = (centerY + y).toDouble()

        // Find all X coordinates where the horizontal scanline intersects polygon segments
        val intersections = mutableListOf<Double>()
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val p1 = polygon[i]
            val p2 = polygon[j]

            val y1 = p1.y
            val y2 = p2.y

            if ((y1 <= scanlineY && y2 > scanlineY) || (y2 <= scanlineY && y1 > scanlineY)) {
                val dy = y2 - y1
                if (dy != 0.0) {
                    val x = p1.x + (scanlineY - y1) * (p2.x - p1.x) / dy
                    intersections.add(x)
                }
            }
            j = i
        }

        if (intersections.size < 2) {
            return 0f
        }

        intersections.sort()

        // For speech balloons, find the interior span containing centerX
        var spanLeft = 0.0
        var spanRight = 0.0
        var foundSpan = false
        for (k in 0 until intersections.size - 1 step 2) {
            val l = intersections[k]
            val r = intersections[k + 1]
            if (centerX in l..r) {
                spanLeft = l
                spanRight = r
                foundSpan = true
                break
            }
        }
        if (!foundSpan) {
            return 0f
        }

        // Measure symmetric width centered at centerX so text doesn't overflow either edge
        val maxHalf = max(0.0, min(centerX - spanLeft, spanRight - centerX))
        val symmetricSpan = (2.0 * maxHalf).toFloat()

        val usableWidth = max(0f, symmetricSpan - 2f * padding)
        val maxBoxUsable = max(0f, bounds.width - 2f * padding)
        return min(usableWidth, maxBoxUsable)
    }

    companion object {
        fun computeCentroid(polygon: List<Point2D.Double>): Point2D.Double? {
            if (polygon.size < 3) return null
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

            val meanX = polygon.sumOf { it.x } / polygon.size
            val meanY = polygon.sumOf { it.y } / polygon.size
            return Point2D.Double(meanX, meanY)
        }
    }
}
