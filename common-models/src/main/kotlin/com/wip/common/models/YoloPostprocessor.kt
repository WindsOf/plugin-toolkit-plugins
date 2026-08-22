package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes ONNX output tensors into standardized [DetectionBox] list.
 */
object YoloPostprocessor {

    /**
     * Decodes model outputs from an [OrtSession.Result] based on the [ModelSpec].
     */
    fun decodeOutputs(
        result: OrtSession.Result,
        modelSpec: ModelSpec,
        scoreThreshold: Double = modelSpec.scoreThreshold
    ): List<DetectionBox> {
        val boxes = mutableListOf<DetectionBox>()

        // 1. Check first output tensor
        val firstOutput = result.firstOrNull() ?: return emptyList()
        val tensor = firstOutput.value as? OnnxTensor ?: return emptyList()
        val shape = tensor.info.shape

        val inputW = modelSpec.inputWidth.toDouble()
        val inputH = modelSpec.inputHeight.toDouble()
        val classes = modelSpec.classes

        val floatBuffer = tensor.floatBuffer

        when (shape.size) {
            3 -> {
                val dim1 = shape[1].toInt()
                val dim2 = shape[2].toInt()

                // Format A: [1, N, 6] (End-to-End YOLOv10 format: [xmin, ymin, xmax, ymax, score, class_id])
                if (dim2 == 6) {
                    val numDetections = dim1
                    for (i in 0 until numDetections) {
                        val base = i * 6
                        val xmin = floatBuffer.get(base + 0).toDouble()
                        val ymin = floatBuffer.get(base + 1).toDouble()
                        val xmax = floatBuffer.get(base + 2).toDouble()
                        val ymax = floatBuffer.get(base + 3).toDouble()
                        val score = floatBuffer.get(base + 4).toDouble()
                        val classId = floatBuffer.get(base + 5).toInt()

                        if (score >= scoreThreshold && classId in classes.indices) {
                            val normXmin = (xmin / inputW).coerceIn(0.0, 1.0)
                            val normYmin = (ymin / inputH).coerceIn(0.0, 1.0)
                            val normXmax = (xmax / inputW).coerceIn(0.0, 1.0)
                            val normYmax = (ymax / inputH).coerceIn(0.0, 1.0)

                            if (normXmax > normXmin && normYmax > normYmin) {
                                boxes.add(
                                    DetectionBox(
                                        label = classes[classId],
                                        confidence = score,
                                        ymin = normYmin,
                                        xmin = normXmin,
                                        ymax = normYmax,
                                        xmax = normXmax
                                    )
                                )
                            }
                        }
                    }
                }
                // Format B: [1, 4 + C, N] (YOLOv8 format: [cx, cy, w, h, class_scores...])
                else if (dim1 >= 5 && dim2 > dim1) {
                    val numChannels = dim1
                    val numAnchors = dim2
                    val numClasses = numChannels - 4

                    for (i in 0 until numAnchors) {
                        var maxClassScore = 0.0f
                        var bestClassId = -1

                        for (c in 0 until numClasses) {
                            val classScore = floatBuffer.get((4 + c) * numAnchors + i)
                            if (classScore > maxClassScore) {
                                maxClassScore = classScore
                                bestClassId = c
                            }
                        }

                        if (maxClassScore >= scoreThreshold && bestClassId in classes.indices) {
                            val cx = floatBuffer.get(0 * numAnchors + i).toDouble()
                            val cy = floatBuffer.get(1 * numAnchors + i).toDouble()
                            val w = floatBuffer.get(2 * numAnchors + i).toDouble()
                            val h = floatBuffer.get(3 * numAnchors + i).toDouble()

                            val xmin = ((cx - w / 2.0) / inputW).coerceIn(0.0, 1.0)
                            val ymin = ((cy - h / 2.0) / inputH).coerceIn(0.0, 1.0)
                            val xmax = ((cx + w / 2.0) / inputW).coerceIn(0.0, 1.0)
                            val ymax = ((cy + h / 2.0) / inputH).coerceIn(0.0, 1.0)

                            if (xmax > xmin && ymax > ymin) {
                                boxes.add(
                                    DetectionBox(
                                        label = classes[bestClassId],
                                        confidence = maxClassScore.toDouble(),
                                        ymin = ymin,
                                        xmin = xmin,
                                        ymax = ymax,
                                        xmax = xmax
                                    )
                                )
                            }
                        }
                    }
                }
                // Format C: [1, N, 4 + C] (DETR format)
                else if (dim2 >= 5) {
                    val numDetections = dim1
                    val numChannels = dim2
                    val numClasses = numChannels - 4

                    for (i in 0 until numDetections) {
                        val base = i * numChannels
                        var maxClassScore = 0.0f
                        var bestClassId = -1

                        for (c in 0 until numClasses) {
                            val classScore = floatBuffer.get(base + 4 + c)
                            if (classScore > maxClassScore) {
                                maxClassScore = classScore
                                bestClassId = c
                            }
                        }

                        if (maxClassScore >= scoreThreshold && bestClassId in classes.indices) {
                            val cx = floatBuffer.get(base + 0).toDouble()
                            val cy = floatBuffer.get(base + 1).toDouble()
                            val w = floatBuffer.get(base + 2).toDouble()
                            val h = floatBuffer.get(base + 3).toDouble()

                            val xmin = (cx - w / 2.0).coerceIn(0.0, 1.0)
                            val ymin = (cy - h / 2.0).coerceIn(0.0, 1.0)
                            val xmax = (cx + w / 2.0).coerceIn(0.0, 1.0)
                            val ymax = (cy + h / 2.0).coerceIn(0.0, 1.0)

                            if (xmax > xmin && ymax > ymin) {
                                boxes.add(
                                    DetectionBox(
                                        label = classes[bestClassId],
                                        confidence = maxClassScore.toDouble(),
                                        ymin = ymin,
                                        xmin = xmin,
                                        ymax = ymax,
                                        xmax = xmax
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return boxes
    }
}
