package com.wip.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.annotations.CapabilityResult
import org.wip.plugintoolkit.api.annotations.ComplexObject

/**
 * Normalized 2D polygon vertex coordinate [0.0, 1.0].
 */
@Serializable
data class PolygonPoint(
    val x: Double,
    val y: Double
)

/**
 * A single segmented object with bounding box, polygon contour, class label, and confidence score.
 */
@ComplexObject(
    id = "com.wip.common.models.SegmentedObject",
    description = "A segmented object with bounding box, polygon vertices, and class classification",
    version = 1
)
@Serializable
data class SegmentedObject(
    @CapabilityResult(name = "label", description = "Class label of the segmented object (e.g. text, balloon, watermark)")
    val label: String,
    @CapabilityResult(name = "confidence", description = "Detection confidence score between 0.0 and 1.0")
    val confidence: Double,
    @CapabilityResult(name = "box", description = "Normalized bounding box of the object")
    val box: DetectionBox,
    @CapabilityResult(name = "polygon", description = "List of normalized polygon vertices outlining the segmented instance")
    val polygon: List<PolygonPoint> = emptyList(),
    @CapabilityResult(name = "shape", description = "Geometric shape classification, e.g. 'oval', 'rectangular', 'polygon'")
    val shape: String? = null,
    @CapabilityResult(name = "area", description = "Normalized area of the polygon mask relative to the image size")
    val area: Double? = null
)

/**
 * Vision inference result containing all detected and segmented objects for a single image.
 */
@ComplexObject(
    id = "com.wip.common.models.VisionResult",
    description = "Instance segmentation and detection results for an image",
    version = 1
)
@Serializable
data class VisionResult(
    @CapabilityResult(name = "objects", description = "List of segmented objects detected in the image")
    val objects: List<SegmentedObject>,
    @CapabilityResult(name = "imageWidth", description = "Original image width in pixels")
    val imageWidth: Int,
    @CapabilityResult(name = "imageHeight", description = "Original image height in pixels")
    val imageHeight: Int,
    @CapabilityResult(name = "pageName", description = "Name or relative path of the source image file")
    val pageName: String = "",
    @CapabilityResult(name = "maskPath", description = "Optional path to the rendered segmentation mask image", semanticTypes = ["path/file"])
    val maskPath: String? = null
)

/**
 * Result collection of vision processing for an entire chapter/folder of images.
 */
@ComplexObject(
    id = "com.wip.common.models.ChapterVisionResult",
    description = "Collection of vision segmentation results for a chapter/folder of images",
    version = 1
)
@Serializable
data class ChapterVisionResult(
    @CapabilityResult(name = "results", description = "List of vision results per processed image page")
    val results: List<VisionResult>,
    @CapabilityResult(name = "totalObjectsDetected", description = "Total number of segmented objects found across all pages")
    val totalObjectsDetected: Int
)

/**
 * Inpainting / cleaner result for a single image.
 */
@ComplexObject(
    id = "com.wip.common.models.CleanerResult",
    description = "Result of image inpainting/cleaning containing output path and statistics",
    version = 1
)
@Serializable
data class CleanerResult(
    @CapabilityResult(
        name = "cleaned image",
        description = "Path to the cleaned/inpainted output image file",
        semanticTypes = ["path/file"]
    )
    val cleanedImagePath: String,
    @CapabilityResult(
        name = "mask path",
        description = "Path to the binary inpainting mask used for cleaning",
        semanticTypes = ["path/file"]
    )
    val maskPath: String? = null,
    @CapabilityResult(
        name = "cleaned objects count",
        description = "Total number of segmented text instances removed from the image"
    )
    val cleanedObjectsCount: Int
)

/**
 * Inpainting / cleaner result for an entire chapter / folder of images.
 */
@ComplexObject(
    id = "com.wip.common.models.ChapterCleanerResult",
    description = "Collection of cleaned image paths for a chapter/folder of images",
    version = 1
)
@Serializable
data class ChapterCleanerResult(
    @CapabilityResult(
        name = "cleaned images",
        description = "List of paths to the cleaned output image files"
    )
    val cleanedImagePaths: List<String>,
    @CapabilityResult(
        name = "mask paths",
        description = "List of paths to generated binary mask files"
    )
    val maskPaths: List<String> = emptyList(),
    @CapabilityResult(
        name = "total cleaned pages",
        description = "Total count of pages successfully cleaned"
    )
    val totalCleanedPages: Int
)
