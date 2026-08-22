package com.wip.common.models

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin implementation of Non-Maximum Suppression (NMS) and Weighted Box Fusion (WBF).
 */
object NmsUtils {

    /**
     * Calculates the Intersection over Union (IoU) between two bounding boxes.
     */
    fun calculateIoU(a: DetectionBox, b: DetectionBox): Double {
        val ymin = max(a.ymin, b.ymin)
        val xmin = max(a.xmin, b.xmin)
        val ymax = min(a.ymax, b.ymax)
        val xmax = min(a.xmax, b.xmax)

        val intersectWidth = max(0.0, xmax - xmin)
        val intersectHeight = max(0.0, ymax - ymin)
        val intersectArea = intersectWidth * intersectHeight

        if (intersectArea <= 0.0) return 0.0

        val areaA = max(0.0, a.xmax - a.xmin) * max(0.0, a.ymax - a.ymin)
        val areaB = max(0.0, b.xmax - b.xmin) * max(0.0, b.ymax - b.ymin)
        val unionArea = areaA + areaB - intersectArea

        return if (unionArea <= 0.0) 0.0 else intersectArea / unionArea
    }

    /**
     * Applies class-aware greedy Non-Maximum Suppression (NMS) on a list of detection boxes.
     */
    fun applyNms(
        boxes: List<DetectionBox>,
        iouThreshold: Double = 0.45,
        scoreThreshold: Double = 0.25
    ): List<DetectionBox> {
        val filtered = boxes.filter { it.confidence >= scoreThreshold }
        if (filtered.isEmpty()) return emptyList()

        val results = mutableListOf<DetectionBox>()
        val byClass = filtered.groupBy { it.label }

        for ((_, classBoxes) in byClass) {
            val sorted = classBoxes.sortedByDescending { it.confidence }.toMutableList()

            while (sorted.isNotEmpty()) {
                val current = sorted.removeAt(0)
                results.add(current)

                sorted.removeAll { candidate ->
                    calculateIoU(current, candidate) > iouThreshold
                }
            }
        }

        return results.sortedByDescending { it.confidence }
    }

    /**
     * Applies Weighted Box Fusion (WBF) to fuse overlapping detection boxes across tiles.
     */
    fun applyWbf(
        boxes: List<DetectionBox>,
        iouThreshold: Double = 0.45,
        scoreThreshold: Double = 0.25
    ): List<DetectionBox> {
        val filtered = boxes.filter { it.confidence >= scoreThreshold }
        if (filtered.isEmpty()) return emptyList()

        val results = mutableListOf<DetectionBox>()
        val byClass = filtered.groupBy { it.label }

        for ((label, classBoxes) in byClass) {
            val clusters = mutableListOf<MutableList<DetectionBox>>()

            val sorted = classBoxes.sortedByDescending { it.confidence }
            for (box in sorted) {
                var matchedCluster: MutableList<DetectionBox>? = null
                for (cluster in clusters) {
                    val clusterAvg = computeAverageBox(label, cluster)
                    if (calculateIoU(box, clusterAvg) > iouThreshold) {
                        matchedCluster = cluster
                        break
                    }
                }

                if (matchedCluster != null) {
                    matchedCluster.add(box)
                } else {
                    clusters.add(mutableListOf(box))
                }
            }

            for (cluster in clusters) {
                results.add(computeAverageBox(label, cluster))
            }
        }

        return results.sortedByDescending { it.confidence }
    }

    private fun computeAverageBox(label: String, cluster: List<DetectionBox>): DetectionBox {
        var totalWeight = 0.0
        var weightedYmin = 0.0
        var weightedXmin = 0.0
        var weightedYmax = 0.0
        var weightedXmax = 0.0
        var maxScore = 0.0

        for (box in cluster) {
            val w = box.confidence
            totalWeight += w
            weightedYmin += box.ymin * w
            weightedXmin += box.xmin * w
            weightedYmax += box.ymax * w
            weightedXmax += box.xmax * w
            if (box.confidence > maxScore) {
                maxScore = box.confidence
            }
        }

        val denom = if (totalWeight > 0.0) totalWeight else 1.0
        return DetectionBox(
            label = label,
            confidence = maxScore,
            ymin = weightedYmin / denom,
            xmin = weightedXmin / denom,
            ymax = weightedYmax / denom,
            xmax = weightedXmax / denom
        )
    }
}
