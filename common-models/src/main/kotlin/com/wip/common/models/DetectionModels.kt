package com.wip.common.models

import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.annotations.CapabilityResult
import org.wip.plugintoolkit.api.annotations.ComplexObject

/**
 * Detection result for a single detected bounding box.
 */
@ComplexObject(
    id = "com.wip.common.models.DetectionBox",
    description = "A single detected box with label and confidence score",
    version = 1
)
@Serializable
data class DetectionBox(
    @CapabilityResult(name = "label", description = "Class label of the detection")
    val label: String = "",
    @CapabilityResult(name = "confidence", description = "Detection confidence score between 0.0 and 1.0")
    val confidence: Double = 1.0,
    @CapabilityResult(name = "ymin", description = "Normalized top coordinate")
    val ymin: Double,
    @CapabilityResult(name = "xmin", description = "Normalized left coordinate")
    val xmin: Double,
    @CapabilityResult(name = "ymax", description = "Normalized bottom coordinate")
    val ymax: Double,
    @CapabilityResult(name = "xmax", description = "Normalized right coordinate")
    val xmax: Double
)

/**
 * Result collection of object detections for an image.
 */
@ComplexObject(
    id = "com.wip.common.models.DetectionResult",
    description = "List of detections with coordinates and classes",
    version = 1
)
@Serializable
data class DetectionResult(
    val boxes: List<DetectionBox>,
    val imageWidth: Int,
    val imageHeight: Int
)
