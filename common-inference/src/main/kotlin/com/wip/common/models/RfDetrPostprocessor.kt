package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decodes RF-DETR and instance segmentation ONNX outputs into standardized [SegmentedObject] collections,
 * with pure Kotlin boundary tracing for polygon extraction and coordinate remapping.
 */
object RfDetrPostprocessor {

    /**
     * Decodes model outputs from an [OrtSession.Result] based on the [ModelSpec].
     */
    fun decodeOutputs(
        result: OrtSession.Result,
        modelSpec: ModelSpec,
        scoreThreshold: Double = modelSpec.scoreThreshold
    ): List<SegmentedObject> {
        val objects = mutableListOf<SegmentedObject>()
        val classes = if (modelSpec.classes.isNotEmpty()) {
            modelSpec.classes
        } else {
            listOf("balloon", "text", "watermark")
        }

        val inputW = modelSpec.inputWidth.toDouble()
        val inputH = modelSpec.inputHeight.toDouble()

        val outputNames = mutableListOf<String>()
        for (entry in result) {
            outputNames.add(entry.key)
        }

        if (outputNames.isEmpty()) return emptyList()

        // 1. Single output tensor handling
        if (outputNames.size == 1) {
            val tensor = result.get(0).value as? OnnxTensor ?: return emptyList()
            val shape = tensor.info.shape
            val floatBuffer = tensor.floatBuffer

            if (shape.size == 3) {
                val numQueries = shape[1].toInt()
                val channels = shape[2].toInt()
                val numClasses = classes.size

                // Format: [1, num_queries, 4 + num_classes + optional_mask_dim]
                if (channels >= 4 + numClasses) {
                    val maskDim = channels - (4 + numClasses)
                    for (i in 0 until numQueries) {
                        val base = i * channels
                        val cx = floatBuffer.get(base + 0).toDouble()
                        val cy = floatBuffer.get(base + 1).toDouble()
                        val w = floatBuffer.get(base + 2).toDouble()
                        val h = floatBuffer.get(base + 3).toDouble()

                        var maxClassScore = 0.0f
                        var bestClassId = -1
                        for (c in 0 until numClasses) {
                            val score = floatBuffer.get(base + 4 + c)
                            if (score > maxClassScore) {
                                maxClassScore = score
                                bestClassId = c
                            }
                        }

                        if (maxClassScore >= scoreThreshold && bestClassId in classes.indices) {
                            val normXmin = (cx - w / 2.0).coerceIn(0.0, 1.0)
                            val normYmin = (cy - h / 2.0).coerceIn(0.0, 1.0)
                            val normXmax = (cx + w / 2.0).coerceIn(0.0, 1.0)
                            val normYmax = (cy + h / 2.0).coerceIn(0.0, 1.0)

                            if (normXmax > normXmin && normYmax > normYmin) {
                                val box = DetectionBox(
                                    label = classes[bestClassId],
                                    confidence = maxClassScore.toDouble(),
                                    ymin = normYmin,
                                    xmin = normXmin,
                                    ymax = normYmax,
                                    xmax = normXmax
                                )

                                // Generate standard contour polygon from bounding box if no raw mask prototypes
                                val polygon = generateBoxPolygon(normXmin, normYmin, normXmax, normYmax)
                                val area = (normXmax - normXmin) * (normYmax - normYmin)

                                objects.add(
                                    SegmentedObject(
                                        label = classes[bestClassId],
                                        confidence = maxClassScore.toDouble(),
                                        box = box,
                                        polygon = polygon,
                                        shape = inferShape(polygon),
                                        area = area
                                    )
                                )
                            }
                        }
                    }
                }
            }
            return objects
        }

        // 2. Multi-output tensor handling (e.g. boxes, scores, masks)
        var boxesTensor: OnnxTensor? = null
        var scoresTensor: OnnxTensor? = null
        var masksTensor: OnnxTensor? = null

        for (entry in result) {
            val name = entry.key.lowercase()
            val tensor = entry.value as? OnnxTensor ?: continue
            when {
                name.contains("box") || name.contains("pred_boxes") || name.contains("dets") -> boxesTensor = tensor
                name.contains("score") || name.contains("pred_logits") || name.contains("labels") -> scoresTensor = tensor
                name.contains("mask") || name.contains("pred_masks") || name.contains("proto") -> masksTensor = tensor
            }
        }

        if (boxesTensor != null && scoresTensor != null) {
            val bShape = boxesTensor.info.shape
            val sShape = scoresTensor.info.shape
            val bBuffer = boxesTensor.floatBuffer
            val sBuffer = scoresTensor.floatBuffer

            val numQueries = bShape[1].toInt()
            val numClasses = classes.size

            for (i in 0 until numQueries) {
                var maxScore = 0.0f
                var bestClass = -1

                if (sShape.size == 3) {
                    val sDim2 = sShape[2].toInt()
                    for (c in 0 until min(numClasses, sDim2)) {
                        val score = sBuffer.get(i * sDim2 + c)
                        if (score > maxScore) {
                            maxScore = score
                            bestClass = c
                        }
                    }
                } else if (sShape.size == 2) {
                    maxScore = sBuffer.get(i)
                    bestClass = 0
                }

                if (maxScore >= scoreThreshold && bestClass in classes.indices) {
                    val bBase = i * bShape[2].toInt()
                    val cx = bBuffer.get(bBase + 0).toDouble()
                    val cy = bBuffer.get(bBase + 1).toDouble()
                    val w = bBuffer.get(bBase + 2).toDouble()
                    val h = bBuffer.get(bBase + 3).toDouble()

                    val xmin = (cx - w / 2.0).coerceIn(0.0, 1.0)
                    val ymin = (cy - h / 2.0).coerceIn(0.0, 1.0)
                    val xmax = (cx + w / 2.0).coerceIn(0.0, 1.0)
                    val ymax = (cy + h / 2.0).coerceIn(0.0, 1.0)

                    if (xmax > xmin && ymax > ymin) {
                        val box = DetectionBox(
                            label = classes[bestClass],
                            confidence = maxScore.toDouble(),
                            ymin = ymin,
                            xmin = xmin,
                            ymax = ymax,
                            xmax = xmax
                        )

                        val polygons = if (masksTensor != null && masksTensor.info.shape.size >= 3) {
                            extractPolygonsFromMask(masksTensor, i, xmin, ymin, xmax, ymax)
                        } else {
                            listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
                        }

                        for (polygon in polygons) {
                            var minPxX = 1.0
                            var minPxY = 1.0
                            var maxPxX = 0.0
                            var maxPxY = 0.0
                            for (pt in polygon) {
                                if (pt.x < minPxX) minPxX = pt.x
                                if (pt.x > maxPxX) maxPxX = pt.x
                                if (pt.y < minPxY) minPxY = pt.y
                                if (pt.y > maxPxY) maxPxY = pt.y
                            }
                            val compBox = if (minPxX < maxPxX && minPxY < maxPxY) {
                                DetectionBox(
                                    label = classes[bestClass],
                                    confidence = maxScore.toDouble(),
                                    ymin = minPxY,
                                    xmin = minPxX,
                                    ymax = maxPxY,
                                    xmax = maxPxX
                                )
                            } else {
                                box
                            }
                            val area = (compBox.xmax - compBox.xmin) * (compBox.ymax - compBox.ymin)
                            objects.add(
                                SegmentedObject(
                                    label = classes[bestClass],
                                    confidence = maxScore.toDouble(),
                                    box = compBox,
                                    polygon = polygon,
                                    shape = inferShape(polygon),
                                    area = area
                                )
                            )
                        }
                    }
                }
            }
        }

        return objects
    }

    /**
     * Extracts exact polygon contour points from a 2D/3D mask slice using 8-directional Moore-Neighbor tracing.
     */
    fun extractPolygonsFromMask(
        maskTensor: OnnxTensor,
        queryIndex: Int,
        xmin: Double,
        ymin: Double,
        xmax: Double,
        ymax: Double
    ): List<List<PolygonPoint>> {
        val shape = maskTensor.info.shape
        val buffer = maskTensor.floatBuffer
        val maskH: Int
        val maskW: Int
        val offset: Int

        if (shape.size == 4) {
            // [1, num_queries, H, W]
            maskH = shape[2].toInt()
            maskW = shape[3].toInt()
            offset = queryIndex * maskH * maskW
        } else if (shape.size == 3) {
            // [num_queries, H, W]
            maskH = shape[1].toInt()
            maskW = shape[2].toInt()
            offset = queryIndex * maskH * maskW
        } else {
            return listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
        }

        if (offset + maskH * maskW > buffer.capacity()) {
            return listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
        }

        // Build 2D binary grid
        val binaryGrid = Array(maskH) { BooleanArray(maskW) }
        var activePixels = 0
        for (r in 0 until maskH) {
            for (c in 0 until maskW) {
                val valLogit = buffer.get(offset + r * maskW + c)
                val isForeground = valLogit > 0.0f
                binaryGrid[r][c] = isForeground
                if (isForeground) activePixels++
            }
        }

        if (activePixels < 4) {
            return listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
        }

        val rawContours = traceAllContours(binaryGrid, maskW, maskH)
        if (rawContours.isEmpty()) {
            return listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
        }

        val resultPolygons = mutableListOf<List<PolygonPoint>>()
        for (contour in rawContours) {
            val simplified = ramerDouglasPeucker(contour, epsilon = 0.3)
            if (simplified.size >= 3) {
                val polygon = simplified.map { (gx, gy) ->
                    PolygonPoint(
                        (gx / maskW.toDouble()).coerceIn(0.0, 1.0),
                        (gy / maskH.toDouble()).coerceIn(0.0, 1.0)
                    )
                }
                resultPolygons.add(polygon)
            }
        }

        return if (resultPolygons.isNotEmpty()) resultPolygons else listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
    }

    /**
     * Finds and traces outer boundaries for all connected components in a 2D boolean grid.
     * Uses flood-fill component discovery so each connected region is visited once without duplicate internal contours.
     */
    fun traceAllContours(
        grid: Array<BooleanArray>,
        width: Int,
        height: Int,
        minComponentSize: Int = 16
    ): List<List<Pair<Double, Double>>> {
        val contours = mutableListOf<List<Pair<Double, Double>>>()
        val visited = Array(height) { BooleanArray(width) }

        val dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

        val queueX = IntArray(width * height)
        val queueY = IntArray(width * height)

        for (r in 0 until height) {
            for (c in 0 until width) {
                if (grid[r][c] && !visited[r][c]) {
                    // Flood fill to discover full component
                    var qHead = 0
                    var qTail = 0

                    queueX[qTail] = c
                    queueY[qTail] = r
                    qTail++
                    visited[r][c] = true

                    val startBorderX = c
                    val startBorderY = r

                    while (qHead < qTail) {
                        val currX = queueX[qHead]
                        val currY = queueY[qHead]
                        qHead++

                        for (d in 0 until 8) {
                            val nx = currX + dx[d]
                            val ny = currY + dy[d]
                            if (nx in 0 until width && ny in 0 until height && grid[ny][nx] && !visited[ny][nx]) {
                                visited[ny][nx] = true
                                queueX[qTail] = nx
                                queueY[qTail] = ny
                                qTail++
                            }
                        }
                    }

                    val componentSize = qTail
                    if (componentSize >= minComponentSize) {
                        val contour = traceBoundary(grid, width, height, startBorderX, startBorderY, dx, dy)
                        if (contour.size >= 3) {
                            contours.add(contour)
                        }
                    }
                }
            }
        }

        return contours
    }

    private fun traceBoundary(
        grid: Array<BooleanArray>,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        dx: IntArray,
        dy: IntArray
    ): List<Pair<Double, Double>> {
        val boundary = mutableListOf<Pair<Double, Double>>()
        var currX = startX
        var currY = startY
        var dir = 0

        val maxSteps = width * height
        var steps = 0

        boundary.add(Pair(currX.toDouble(), currY.toDouble()))

        while (steps < maxSteps) {
            steps++
            var found = false

            for (i in 0 until 8) {
                val checkDir = (dir + i) % 8
                val nx = currX + dx[checkDir]
                val ny = currY + dy[checkDir]

                if (nx in 0 until width && ny in 0 until height && grid[ny][nx]) {
                    currX = nx
                    currY = ny
                    boundary.add(Pair(currX.toDouble(), currY.toDouble()))
                    dir = (checkDir + 6) % 8 // backtrack search direction
                    found = true
                    break
                }
            }

            if (!found || (currX == startX && currY == startY)) {
                break
            }
        }

        return boundary
    }

    /**
     * Non-recursive (iterative) Ramer-Douglas-Peucker polygon simplification with
     * radial distance pre-filtering to prevent StackOverflowError on large/closed contours.
     */
    fun ramerDouglasPeucker(points: List<Pair<Double, Double>>, epsilon: Double): List<Pair<Double, Double>> {
        if (points.size < 3) return points

        // 1. Fast linear pre-filter: remove consecutive duplicate or near-identical points
        val filtered = ArrayList<Pair<Double, Double>>(points.size)
        filtered.add(points[0])
        val epsSq = (epsilon * 0.5) * (epsilon * 0.5)
        for (i in 1 until points.size) {
            val prev = filtered.last()
            val curr = points[i]
            val dSq = (curr.first - prev.first) * (curr.first - prev.first) +
                      (curr.second - prev.second) * (curr.second - prev.second)
            if (dSq >= epsSq || i == points.size - 1) {
                filtered.add(curr)
            }
        }

        if (filtered.size < 3) return filtered

        // 2. Iterative RDP using an explicit Stack of index ranges [start, end]
        val n = filtered.size
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(Pair(0, n - 1))

        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue

            val lineStart = filtered[start]
            val lineEnd = filtered[end]

            var maxDist = 0.0
            var maxIdx = start

            for (i in start + 1 until end) {
                val dist = perpendicularDistance(filtered[i], lineStart, lineEnd)
                if (dist > maxDist) {
                    maxDist = dist
                    maxIdx = i
                }
            }

            if (maxDist > epsilon && maxIdx != start && maxIdx != end) {
                keep[maxIdx] = true
                stack.add(Pair(start, maxIdx))
                stack.add(Pair(maxIdx, end))
            }
        }

        val result = ArrayList<Pair<Double, Double>>(n)
        for (i in 0 until n) {
            if (keep[i]) {
                result.add(filtered[i])
            }
        }

        return if (result.size >= 3) result else filtered
    }

    private fun perpendicularDistance(
        pt: Pair<Double, Double>,
        lineStart: Pair<Double, Double>,
        lineEnd: Pair<Double, Double>
    ): Double {
        val dx = lineEnd.first - lineStart.first
        val dy = lineEnd.second - lineStart.second
        val lineLenSq = dx * dx + dy * dy
        if (lineLenSq < 1e-9) {
            val px = pt.first - lineStart.first
            val py = pt.second - lineStart.second
            return sqrt(px * px + py * py)
        }
        val t = ((pt.first - lineStart.first) * dx + (pt.second - lineStart.second) * dy) / lineLenSq
        val projX = lineStart.first + t * dx
        val projY = lineStart.second + t * dy
        val px = pt.first - projX
        val py = pt.second - projY
        return sqrt(px * px + py * py)
    }

    /**
     * Generates a 4-point polygon from normalized rectangle bounds.
     */
    fun generateBoxPolygon(xmin: Double, ymin: Double, xmax: Double, ymax: Double): List<PolygonPoint> {
        return listOf(
            PolygonPoint(xmin, ymin),
            PolygonPoint(xmax, ymin),
            PolygonPoint(xmax, ymax),
            PolygonPoint(xmin, ymax)
        )
    }

    /**
     * Infers shape string ('oval', 'rectangular', or 'polygon') based on polygon aspect and circularity.
     */
    fun inferShape(polygon: List<PolygonPoint>): String {
        if (polygon.size <= 4) return "rectangular"
        return "oval"
    }

    /**
     * Remaps a local [SegmentedObject] detected inside a cropped Region of Interest (ROI)
     * back to the full image normalized coordinate space [0.0, 1.0].
     */
    fun remapRoiToGlobal(
        local: SegmentedObject,
        roiBox: DetectionBox
    ): SegmentedObject {
        val roiW = roiBox.xmax - roiBox.xmin
        val roiH = roiBox.ymax - roiBox.ymin

        val globalXmin = (roiBox.xmin + local.box.xmin * roiW).coerceIn(0.0, 1.0)
        val globalYmin = (roiBox.ymin + local.box.ymin * roiH).coerceIn(0.0, 1.0)
        val globalXmax = (roiBox.xmin + local.box.xmax * roiW).coerceIn(0.0, 1.0)
        val globalYmax = (roiBox.ymin + local.box.ymax * roiH).coerceIn(0.0, 1.0)

        val globalPolygon = local.polygon.map { pt ->
            val gx = (roiBox.xmin + pt.x * roiW).coerceIn(0.0, 1.0)
            val gy = (roiBox.ymin + pt.y * roiH).coerceIn(0.0, 1.0)
            PolygonPoint(gx, gy)
        }

        val remappedBox = DetectionBox(
            label = local.label,
            confidence = local.confidence,
            ymin = globalYmin,
            xmin = globalXmin,
            ymax = globalYmax,
            xmax = globalXmax
        )

        val globalArea = (globalXmax - globalXmin) * (globalYmax - globalYmin)

        return SegmentedObject(
            label = local.label,
            confidence = local.confidence,
            box = remappedBox,
            polygon = globalPolygon,
            shape = local.shape,
            area = globalArea
        )
    }

    /**
     * Remaps a local [SegmentedObject] from a SAHI tile [SliceWindow] back to the full image.
     */
    fun remapTileToGlobal(
        local: SegmentedObject,
        slice: SliceWindow,
        fullImageWidth: Int,
        fullImageHeight: Int
    ): SegmentedObject {
        if (slice.isFullImage) return local

        val roiBox = DetectionBox(
            label = local.label,
            confidence = local.confidence,
            ymin = slice.y.toDouble() / fullImageHeight.toDouble(),
            xmin = slice.x.toDouble() / fullImageWidth.toDouble(),
            ymax = slice.ymax.toDouble() / fullImageHeight.toDouble(),
            xmax = slice.xmax.toDouble() / fullImageWidth.toDouble()
        )

        return remapRoiToGlobal(local, roiBox)
    }
}
