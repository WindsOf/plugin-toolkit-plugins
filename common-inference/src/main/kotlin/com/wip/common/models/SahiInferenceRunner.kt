package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import org.wip.plugintoolkit.api.PluginLogger
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin/Java implementation of Slicing Aided Hyper Inference (SAHI) with Global Coordinate Remapping
 * and Cross-Tile NMS merging.
 */
object SahiInferenceRunner {

    /**
     * Generates a grid of overlapping sliding window slices across the image dimensions.
     */
    fun generateSlices(imageWidth: Int, imageHeight: Int, config: SahiConfig): List<SliceWindow> {
        val slices = mutableListOf<SliceWindow>()

        val effectiveW = (config.sliceWidth * config.tileScale).toInt().coerceAtLeast(64)
        val effectiveH = (config.sliceHeight * config.tileScale).toInt().coerceAtLeast(64)
        val sliceW = min(effectiveW, imageWidth)
        val sliceH = min(effectiveH, imageHeight)

        val stepW = max(1, (sliceW * (1.0f - config.overlapWidthRatio)).toInt())
        val stepH = max(1, (sliceH * (1.0f - config.overlapHeightRatio)).toInt())

        var y = 0
        while (y < imageHeight) {
            val actualY = if (y + sliceH > imageHeight) max(0, imageHeight - sliceH) else y
            val actualH = min(sliceH, imageHeight - actualY)

            var x = 0
            while (x < imageWidth) {
                val actualX = if (x + sliceW > imageWidth) max(0, imageWidth - sliceW) else x
                val actualW = min(sliceW, imageWidth - actualX)

                slices.add(SliceWindow(x = actualX, y = actualY, width = actualW, height = actualH, isFullImage = false))

                if (actualX + actualW >= imageWidth) break
                x += stepW
            }

            if (actualY + actualH >= imageHeight) break
            y += stepH
        }

        if (config.includeFullImage && (imageWidth > sliceW || imageHeight > sliceH)) {
            slices.add(SliceWindow(x = 0, y = 0, width = imageWidth, height = imageHeight, isFullImage = true))
        }

        return slices
    }

    /**
     * Runs SAHI sliced instance segmentation on a [BufferedImage] using an ONNX [OnnxInferenceSession].
     */
    fun runSlicedSegmentation(
        image: BufferedImage,
        modelSpec: ModelSpec,
        session: OnnxInferenceSession,
        config: SahiConfig = SahiConfig(),
        logger: PluginLogger? = null
    ): List<SegmentedObject> {
        val slices = generateSlices(image.width, image.height, config)
        logger?.info("SAHI Segmentation: Sliced image (${image.width}x${image.height}) into ${slices.size} tiles with scale ${config.tileScale}.")

        val inputName = session.session.inputNames.iterator().next()
        val allObjects = mutableListOf<SegmentedObject>()

        for ((index, slice) in slices.withIndex()) {
            val tileImg: BufferedImage = if (slice.isFullImage) {
                image
            } else {
                image.getSubimage(slice.x, slice.y, slice.width, slice.height)
            }

            var tensor: OnnxTensor? = null
            try {
                tensor = ImageTensorUtils.createTensor(
                    session.environment,
                    tileImg,
                    modelSpec.inputWidth,
                    modelSpec.inputHeight
                )

                val result = session.session.run(mapOf(inputName to tensor))
                val localObjects = RfDetrPostprocessor.decodeOutputs(result, modelSpec, config.scoreThreshold)
                result.close()

                for (local in localObjects) {
                    val globalObj = RfDetrPostprocessor.remapTileToGlobal(local, slice, image.width, image.height)
                    allObjects.add(globalObj)
                }
            } catch (e: Exception) {
                logger?.warn("Error during segmentation tile [${index + 1}/${slices.size}] inference: ${e.message}")
            } finally {
                tensor?.close()
            }
        }

        val mergedObjects = NmsUtils.applySegmentationNms(allObjects, config.iouThreshold, config.scoreThreshold)
        logger?.info("SAHI Segmentation: Merged ${allObjects.size} raw tile segmentations into ${mergedObjects.size} global segmentations.")
        return mergedObjects
    }

    /**
     * Runs SAHI sliced inference on a [BufferedImage] using an ONNX [OnnxInferenceSession].
     */
    fun runSlicedInference(
        image: BufferedImage,
        modelSpec: ModelSpec,
        session: OnnxInferenceSession,
        config: SahiConfig = SahiConfig(),
        logger: PluginLogger? = null
    ): DetectionResult {
        val slices = generateSlices(image.width, image.height, config)
        logger?.info("SAHI: Sliced image (${image.width}x${image.height}) into ${slices.size} tiles.")

        val inputName = session.session.inputNames.iterator().next()
        val allBoxes = mutableListOf<DetectionBox>()

        for ((index, slice) in slices.withIndex()) {
            val tileImg: BufferedImage = if (slice.isFullImage) {
                image
            } else {
                image.getSubimage(slice.x, slice.y, slice.width, slice.height)
            }

            var tensor: OnnxTensor? = null
            try {
                tensor = ImageTensorUtils.createTensor(
                    session.environment,
                    tileImg,
                    modelSpec.inputWidth,
                    modelSpec.inputHeight
                )

                val result = session.session.run(mapOf(inputName to tensor))
                val localBoxes = YoloPostprocessor.decodeOutputs(result, modelSpec, config.scoreThreshold)
                result.close()

                // Global Coordinate Remapping
                for (local in localBoxes) {
                    val globalBox = remapToGlobal(local, slice, image.width, image.height, config)
                    if (globalBox != null) {
                        allBoxes.add(globalBox)
                    }
                }
            } catch (e: Exception) {
                logger?.warn("Error during tile [${index + 1}/${slices.size}] inference: ${e.message}")
            } finally {
                tensor?.close()
            }
        }

        // Global Cross-Tile NMS
        val mergedBoxes = NmsUtils.applyNms(allBoxes, config.iouThreshold, config.scoreThreshold)
        logger?.info("SAHI: Merged ${allBoxes.size} raw tile detections into ${mergedBoxes.size} global detections.")

        return DetectionResult(
            boxes = mergedBoxes,
            imageWidth = image.width,
            imageHeight = image.height
        )
    }

    /**
     * Projects local tile coordinates back to global full-image normalized coordinates [0.0, 1.0].
     */
    fun remapToGlobal(
        local: DetectionBox,
        slice: SliceWindow,
        fullImageWidth: Int,
        fullImageHeight: Int,
        config: SahiConfig
    ): DetectionBox? {
        if (slice.isFullImage) {
            return local
        }

        val pxXmin = slice.x + local.xmin * slice.width
        val pxYmin = slice.y + local.ymin * slice.height
        val pxXmax = slice.x + local.xmax * slice.width
        val pxYmax = slice.y + local.ymax * slice.height

        // Boundary margin exclusion for artificial tile borders (unless at true image edge)
        val margin = config.edgeMarginFilterPx.toDouble()
        val isLeftEdgeArtificial = slice.x > 0 && (local.xmin * slice.width) <= margin
        val isRightEdgeArtificial = slice.xmax < fullImageWidth && ((1.0 - local.xmax) * slice.width) <= margin
        val isTopEdgeArtificial = slice.y > 0 && (local.ymin * slice.height) <= margin
        val isBottomEdgeArtificial = slice.ymax < fullImageHeight && ((1.0 - local.ymax) * slice.height) <= margin

        if (isLeftEdgeArtificial || isRightEdgeArtificial || isTopEdgeArtificial || isBottomEdgeArtificial) {
            // Drop truncated boundary artifact in favor of adjacent overlapping tile
            return null
        }

        val normXmin = (pxXmin / fullImageWidth.toDouble()).coerceIn(0.0, 1.0)
        val normYmin = (pxYmin / fullImageHeight.toDouble()).coerceIn(0.0, 1.0)
        val normXmax = (pxXmax / fullImageWidth.toDouble()).coerceIn(0.0, 1.0)
        val normYmax = (pxYmax / fullImageHeight.toDouble()).coerceIn(0.0, 1.0)

        if (normXmax <= normXmin || normYmax <= normYmin) return null

        return DetectionBox(
            label = local.label,
            confidence = local.confidence,
            ymin = normYmin,
            xmin = normXmin,
            ymax = normYmax,
            xmax = normXmax
        )
    }
}
