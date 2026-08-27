package com.wip.ocrAI

import com.wip.common.models.ChapterVisionResult
import com.wip.common.models.SegmentedObject
import com.wip.common.models.VisionResult
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Represents a pixel-space crop rectangle within an image.
 */
data class CropRegion(
    val xmin: Int,
    val ymin: Int,
    val xmax: Int,
    val ymax: Int
) {
    val width: Int get() = max(1, xmax - xmin)
    val height: Int get() = max(1, ymax - ymin)

    /**
     * Checks if this region overlaps or intersects with another region.
     */
    fun overlaps(other: CropRegion): Boolean {
        return xmin <= other.xmax && xmax >= other.xmin &&
                ymin <= other.ymax && ymax >= other.ymin
    }

    /**
     * Merges this region with another overlapping region into a bounding union.
     */
    fun union(other: CropRegion): CropRegion {
        return CropRegion(
            xmin = min(xmin, other.xmin),
            ymin = min(ymin, other.ymin),
            xmax = max(xmax, other.xmax),
            ymax = max(ymax, other.ymax)
        )
    }
}

/**
 * Helper object providing functions to compute ROI crops from Vision segmentation results,
 * merge overlapping bounding boxes, and remap OCR coordinates back to the full image space.
 */
object VisionCutoutHelper {

    private val supportedExtensions = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp")

    /**
     * Finds the VisionResult for a specific image file from a ChapterVisionResult collection.
     */
    fun findMatchingVisionResult(pageFile: File, chapterVisionResult: ChapterVisionResult?): VisionResult? {
        if (chapterVisionResult == null || chapterVisionResult.results.isEmpty()) return null

        val fileName = pageFile.name
        val baseName = pageFile.nameWithoutExtension

        return chapterVisionResult.results.firstOrNull { r ->
            var cleanRName = r.pageName
            for (ext in supportedExtensions) {
                cleanRName = cleanRName.removeSuffix(ext)
            }

            r.pageName.equals(fileName, ignoreCase = true) ||
            cleanRName.equals(baseName, ignoreCase = true) ||
            cleanRName.equals(fileName, ignoreCase = true) ||
            r.pageName.equals(baseName, ignoreCase = true)
        }
    }

    /**
     * Computes disjoint ROI crop regions for detected points of interest (balloons, text)
     * expanded with padding and merged where overlapping.
     */
    fun computeCropRegions(
        objects: List<SegmentedObject>,
        imageWidth: Int,
        imageHeight: Int,
        paddingPx: Int = 100,
        targetClasses: Set<String> = setOf("balloon", "text")
    ): List<CropRegion> {
        if (objects.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return emptyList()

        val relevantObjects = objects.filter {
            it.label.lowercase() in targetClasses || targetClasses.isEmpty()
        }.ifEmpty {
            // If no objects match specific labels, take all detected objects
            objects
        }

        if (relevantObjects.isEmpty()) return emptyList()

        val initialRegions = relevantObjects.mapNotNull { obj ->
            val box = obj.box
            val pxXmin = (box.xmin * imageWidth).toInt()
            val pxYmin = (box.ymin * imageHeight).toInt()
            val pxMaxX = (box.xmax * imageWidth).toInt()
            val pxMaxY = (box.ymax * imageHeight).toInt()

            val expandedXmin = max(0, pxXmin - paddingPx)
            val expandedYmin = max(0, pxYmin - paddingPx)
            val expandedXmax = min(imageWidth, pxMaxX + paddingPx)
            val expandedYmax = min(imageHeight, pxMaxY + paddingPx)

            if (expandedXmax > expandedXmin && expandedYmax > expandedYmin) {
                CropRegion(expandedXmin, expandedYmin, expandedXmax, expandedYmax)
            } else null
        }

        if (initialRegions.isEmpty()) return emptyList()

        return mergeOverlappingRegions(initialRegions)
            .sortedWith(compareBy({ it.ymin }, { it.xmin }))
    }

    /**
     * Iteratively merges overlapping rectangles until all rectangles in the list are disjoint.
     */
    fun mergeOverlappingRegions(regions: List<CropRegion>): List<CropRegion> {
        if (regions.size <= 1) return regions

        val merged = mutableListOf<CropRegion>()
        var remaining = regions.toMutableList()

        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var mergedAny: Boolean

            do {
                mergedAny = false
                val nextRemaining = mutableListOf<CropRegion>()
                for (candidate in remaining) {
                    if (current.overlaps(candidate)) {
                        current = current.union(candidate)
                        mergedAny = true
                    } else {
                        nextRemaining.add(candidate)
                    }
                }
                remaining = nextRemaining
            } while (mergedAny)

            merged.add(current)
        }

        return merged
    }

    /**
     * Remaps a local bounding box [ymin, xmin, ymax, xmax] relative to a crop region
     * back to absolute pixel coordinates on the full image.
     */
    fun remapBoxToGlobal(localBox: List<Double>, crop: CropRegion, fullWidth: Double, fullHeight: Double): List<Double> {
        if (localBox.size < 4) return localBox

        val ymin = (crop.ymin + localBox[0]).coerceIn(0.0, fullHeight)
        val xmin = (crop.xmin + localBox[1]).coerceIn(0.0, fullWidth)
        val ymax = (crop.ymin + localBox[2]).coerceIn(0.0, fullHeight)
        val xmax = (crop.xmin + localBox[3]).coerceIn(0.0, fullWidth)

        return listOf(
            min(ymin, ymax),
            min(xmin, xmax),
            max(ymin, ymax),
            max(xmin, xmax)
        )
    }
}

