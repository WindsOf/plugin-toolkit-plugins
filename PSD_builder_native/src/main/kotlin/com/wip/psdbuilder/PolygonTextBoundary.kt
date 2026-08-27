package com.wip.psdbuilder

import com.wip.kpsd.PsdBounds
import com.wip.kpsd.TextBoundary
import java.awt.geom.Point2D
import kotlin.math.max
import kotlin.math.min

/**
 * Custom [TextBoundary] implementation that dynamically constrains text line widths
 * to the interior geometry of an arbitrary speech balloon polygon.
 */
class PolygonTextBoundary(
    val polygon: List<Point2D.Double>,
    val padding: Float = 0f,
    val visualCenter: Point2D.Double? = null
) : TextBoundary {

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
            val dy = kotlin.math.abs(y)
            if (dy >= usableHeight / 2f) return 0f
            return max(0f, usableWidth)
        }

        val centerY = visualCenter?.y?.toFloat() ?: (bounds.top + bounds.height / 2f)
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

        // For speech balloons, measure the primary interior span
        val minX = intersections.first()
        val maxX = intersections.last()
        val span = (maxX - minX).toFloat()

        val usableWidth = max(0f, span - 2f * padding)
        val maxBoxUsable = max(0f, bounds.width - 2f * padding)
        return min(usableWidth, maxBoxUsable)
    }
}
