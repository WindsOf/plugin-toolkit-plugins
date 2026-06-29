package com.wip.common.models

import kotlinx.serialization.Serializable
import org.wip.plugintoolkit.api.annotations.CapabilityResult

@Serializable
data class OCRResult(
    @CapabilityResult(
        name = "extracted text",
        description = "a list of strings representing the extracted text"
    )
    val texts: List<String>,
    @CapabilityResult(
        name = "bounding box",
        description = "(xmin, ymin, xmax, ymax), a list of lists of doubles representing the bounding box coordinates for each extracted text",
        semanticTypes = ["wom/bounding-box"]
    )
    val bb: List<List<Double>>,
    @CapabilityResult(
        name = "page number",
        description = "a list of integers representing the page number (1-indexed) for each extracted text"
    )
    val pageNumbers: List<Int>,
    @CapabilityResult(
        name = "page name",
        description = "a list of strings representing the filename/page name for each extracted text"
    )
    val pageNames: List<String>,
    @CapabilityResult(
        name = "failed files",
        description = "a list of strings representing the filenames that failed to process"
    )
    val failedFiles: List<String>
)

@Serializable
data class AdvancedOCRResult(
    @CapabilityResult(
        name = "extracted text",
        description = "a list of strings representing the extracted text"
    )
    val texts: List<String>,
    @CapabilityResult(
        name = "balloon bounding box",
        description = "(ymin, xmin, ymax, xmax), bounding box coordinates of the speech balloon",
        semanticTypes = ["wom/bounding-box"]
    )
    val balloonBoxes: List<List<Double>>,
    @CapabilityResult(
        name = "text bounding box",
        description = "(ymin, xmin, ymax, xmax), bounding box coordinates tightly wrapping the text",
        semanticTypes = ["wom/bounding-box"]
    )
    val textBoxes: List<List<Double>>,
    @CapabilityResult(
        name = "balloon shapes",
        description = "shape of the speech balloon. Valid values are strictly: 'oval' or 'rectangular'",
        semanticTypes = ["wom/shape"]
    )
    val shapes: List<String>,
    @CapabilityResult(
        name = "font styles",
        description = "style of the text font. Valid values are strictly: 'normal', 'italic', 'bold', 'bold-italic'"
    )
    val fontStyles: List<String>,
    @CapabilityResult(
        name = "font families",
        description = "general classification of the font, e.g., 'sans-serif', 'serif', 'handwritten', 'screaming'"
    )
    val fontFamilies: List<String>,
    @CapabilityResult(
        name = "text angles",
        description = "rotation angle of the text in degrees"
    )
    val textAngles: List<Double>,
    @CapabilityResult(
        name = "is sparse",
        description = "whether the text is sparsely distributed inside the bounding box"
    )
    val isSparse: List<Boolean>,
    @CapabilityResult(
        name = "text colors",
        description = "color of the text"
    )
    val textColors: List<String>,
    @CapabilityResult(
        name = "has border",
        description = "whether the text has an outline/stroke"
    )
    val hasBorder: List<Boolean>,
    @CapabilityResult(
        name = "border colors",
        description = "color of the text outline/stroke if present"
    )
    val borderColors: List<String>,
    @CapabilityResult(
        name = "page number",
        description = "a list of integers representing the page number (1-indexed) for each extracted text"
    )
    val pageNumbers: List<Int>,
    @CapabilityResult(
        name = "page name",
        description = "a list of strings representing the filename/page name for each extracted text"
    )
    val pageNames: List<String>,
    @CapabilityResult(
        name = "failed files",
        description = "a list of strings representing the filenames that failed to process"
    )
    val failedFiles: List<String>
)

@Serializable
data class Balloon(
    val xmin: Double,
    val ymin: Double,
    val xmax: Double,
    val ymax: Double,
    val text: String
)

@Serializable
data class BalloonsResponse(
    val balloons: List<Balloon>
)

@Serializable
data class AdvancedBalloon(
    val balloon_box_2d: List<Double>,
    val text_box_2d: List<Double>,
    val shape: String,
    val fontStyle: String,
    val fontFamily: String,
    val textAngle: Double,
    val isSparse: Boolean,
    val textColor: String,
    val hasBorder: Boolean,
    val borderColor: String,
    val text: String
)

@Serializable
data class AdvancedBalloonsResponse(
    val balloons: List<AdvancedBalloon>
)

data class OcrServiceResult(
    val texts: List<String>,
    val bb: List<List<Double>>,
    val pageNumbers: List<Int>,
    val pageNames: List<String>,
    val failedFiles: List<String>
)

data class AdvancedOcrServiceResult(
    val texts: List<String>,
    val balloonBoxes: List<List<Double>>,
    val textBoxes: List<List<Double>>,
    val shapes: List<String>,
    val fontStyles: List<String>,
    val fontFamilies: List<String>,
    val textAngles: List<Double>,
    val isSparse: List<Boolean>,
    val textColors: List<String>,
    val hasBorder: List<Boolean>,
    val borderColors: List<String>,
    val pageNumbers: List<Int>,
    val pageNames: List<String>,
    val failedFiles: List<String>
)
