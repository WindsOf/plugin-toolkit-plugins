package com.wip.common.models

import kotlinx.serialization.Serializable

/**
 * Configuration options for Slicing Aided Hyper Inference (SAHI).
 */
@Serializable
data class SahiConfig(
    val sliceWidth: Int = 640,
    val sliceHeight: Int = 640,
    val overlapWidthRatio: Float = 0.25f,
    val overlapHeightRatio: Float = 0.25f,
    val includeFullImage: Boolean = true,
    val scoreThreshold: Double = 0.25,
    val iouThreshold: Double = 0.45,
    val edgeMarginFilterPx: Int = 2
)

/**
 * Represents a single tile/patch coordinate window in a large image.
 */
data class SliceWindow(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isFullImage: Boolean = false
) {
    val xmax: Int get() = x + width
    val ymax: Int get() = y + height
}
