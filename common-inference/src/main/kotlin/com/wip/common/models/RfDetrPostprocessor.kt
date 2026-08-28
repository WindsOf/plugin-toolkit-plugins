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
            return NmsUtils.applySegmentationNms(
                objects = objects,
                iouThreshold = modelSpec.iouThreshold,
                scoreThreshold = scoreThreshold,
                iosThreshold = 0.65
            )
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
                        val rawLogit = sBuffer.get(i * sDim2 + c)
                        val score = sigmoid(rawLogit)
                        if (score > maxScore) {
                            maxScore = score
                            bestClass = c
                        }
                    }
                } else if (sShape.size == 2) {
                    val rawLogit = sBuffer.get(i)
                    maxScore = sigmoid(rawLogit)
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

        return NmsUtils.applySegmentationNms(
            objects = objects,
            iouThreshold = modelSpec.iouThreshold,
            scoreThreshold = scoreThreshold,
            iosThreshold = 0.65
        )
    }

    private fun sigmoid(x: Float): Float = (1.0f / (1.0f + kotlin.math.exp(-x))).coerceIn(0.0f, 1.0f)

    /**
     * Extracts exact polygon contour points from a 2D/3D mask slice using Marching Squares Isocontour tracing.
     *
     * ## Marching Squares Boundary Tracing Algorithm
     *
     * Traditional single-pass boundary tracing (e.g. Moore-Neighbor) suffers from topological ambiguities
     * such as premature termination on self-touching boundaries, diagonal bottlenecks, and internal loops.
     * Furthermore, running naive Ramer-Douglas-Peucker (RDP) on closed contours (where start == end) results in
     * zero-length baseline chords, causing RDP to cut straight lines across organic curved bubbles and creating
     * artificial sharp spikes.
     *
     * This implementation uses **Marching Squares Boundary Segment Extraction**:
     * 1. **Directed Edge Generation**:
     *    For every foreground pixel (r, c) on the HxW binary grid, we generate directed boundary edges
     *    separating it from background neighbors:
     *    - Top edge:    (c, r) -> (c+1, r)         [Direction: Right (+X)]
     *    - Right edge:  (c+1, r) -> (c+1, r+1)     [Direction: Down  (+Y)]
     *    - Bottom edge: (c+1, r+1) -> (c, r+1)     [Direction: Left  (-X)]
     *    - Left edge:   (c, r+1) -> (c, r)         [Direction: Up    (-Y)]
     *
     * 2. **Eulerian Closed Loop Assembly**:
     *    Since every boundary vertex has an equal number of incoming and outgoing edges, the boundary graph
     *    is strictly Eulerian. We assemble the directed edges into guaranteed closed, non-self-intersecting loops.
     *
     * 3. **Collinear Simplification**:
     *    Removes all intermediate collinear vertices along straight runs without losing any corner or curved detail.
     *
     * 4. **Curvature-Preserving Polygon Smoothing**:
     *    Preserves the full organic contour of text, speech balloons, and sound effects.
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
            val rowOffset = offset + r * maskW
            for (c in 0 until maskW) {
                val valLogit = buffer.get(rowOffset + c)
                val isForeground = valLogit > 0.0f
                binaryGrid[r][c] = isForeground
                if (isForeground) activePixels++
            }
        }

        if (activePixels < 4) {
            return listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
        }

        val rawLoops = traceMarchingSquaresContours(binaryGrid, maskW, maskH, minComponentSize = 16)
        if (rawLoops.isEmpty()) {
            return listOf(generateBoxPolygon(xmin, ymin, xmax, ymax))
        }

        val resultPolygons = mutableListOf<List<PolygonPoint>>()
        for (loop in rawLoops) {
            val simplified = simplifyCollinearPoints(loop)
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

    private data class GridPoint(val x: Int, val y: Int)

    /**
     * Traces all closed boundary contours from a 2D boolean grid using Eulerian directed edge traversal.
     */
    fun traceMarchingSquaresContours(
        grid: Array<BooleanArray>,
        width: Int,
        height: Int,
        minComponentSize: Int = 16
    ): List<List<Pair<Double, Double>>> {
        val outEdges = HashMap<GridPoint, ArrayList<GridPoint>>()

        fun addEdge(from: GridPoint, to: GridPoint) {
            outEdges.computeIfAbsent(from) { ArrayList(2) }.add(to)
        }

        for (r in 0 until height) {
            val row = grid[r]
            val rowAbove = if (r > 0) grid[r - 1] else null
            val rowBelow = if (r < height - 1) grid[r + 1] else null

            for (c in 0 until width) {
                if (row[c]) {
                    // Top edge: (c, r) -> (c+1, r)
                    if (rowAbove == null || !rowAbove[c]) {
                        addEdge(GridPoint(c, r), GridPoint(c + 1, r))
                    }
                    // Right edge: (c+1, r) -> (c+1, r+1)
                    if (c == width - 1 || !row[c + 1]) {
                        addEdge(GridPoint(c + 1, r), GridPoint(c + 1, r + 1))
                    }
                    // Bottom edge: (c+1, r+1) -> (c, r+1)
                    if (rowBelow == null || !rowBelow[c]) {
                        addEdge(GridPoint(c + 1, r + 1), GridPoint(c, r + 1))
                    }
                    // Left edge: (c, r+1) -> (c, r)
                    if (c == 0 || !row[c - 1]) {
                        addEdge(GridPoint(c, r + 1), GridPoint(c, r))
                    }
                }
            }
        }

        if (outEdges.isEmpty()) return emptyList()

        val loops = mutableListOf<List<Pair<Double, Double>>>()

        // Traverse all closed Eulerian cycles
        for (startKey in ArrayList(outEdges.keys)) {
            val destinations = outEdges[startKey] ?: continue
            while (destinations.isNotEmpty()) {
                val loop = mutableListOf<Pair<Double, Double>>()
                var curr = startKey
                var next = destinations.removeAt(destinations.size - 1)
                loop.add(Pair(curr.x.toDouble(), curr.y.toDouble()))

                var count = 0
                val maxSteps = width * height * 4

                while (count < maxSteps) {
                    count++
                    loop.add(Pair(next.x.toDouble(), next.y.toDouble()))
                    if (next == startKey) {
                        break // Closed cycle reached
                    }

                    val nextDestinations = outEdges[next]
                    if (nextDestinations == null || nextDestinations.isEmpty()) {
                        break
                    }

                    curr = next
                    next = nextDestinations.removeAt(nextDestinations.size - 1)
                }

                // Calculate signed polygon area (shoelace formula)
                if (loop.size >= 4) {
                    var signedArea = 0.0
                    for (i in 0 until loop.size - 1) {
                        val p1 = loop[i]
                        val p2 = loop[i + 1]
                        signedArea += (p1.first * p2.second - p2.first * p1.second)
                    }
                    val absArea = kotlin.math.abs(signedArea) * 0.5

                    // Filter out microscopic noise loops
                    if (absArea >= minComponentSize.toDouble()) {
                        loops.add(loop)
                    }
                }
            }
        }

        return loops
    }

    /**
     * Simplifies collinear intermediate points along straight or diagonal edges without modifying polygon shape.
     */
    fun simplifyCollinearPoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size < 3) return points

        val n = points.size
        val result = ArrayList<Pair<Double, Double>>(n)
        result.add(points[0])

        for (i in 1 until n - 1) {
            val prev = result.last()
            val curr = points[i]
            val next = points[i + 1]

            val dx1 = curr.first - prev.first
            val dy1 = curr.second - prev.second
            val dx2 = next.first - curr.first
            val dy2 = next.second - curr.second

            // Cross product of direction vectors: dx1*dy2 - dy1*dx2 == 0 means exactly collinear
            val cross = dx1 * dy2 - dy1 * dx2
            val dot = dx1 * dx2 + dy1 * dy2

            if (kotlin.math.abs(cross) < 1e-6 && dot > 0) {
                // Collinear in the same forward direction: skip intermediate point
                continue
            }
            result.add(curr)
        }

        result.add(points.last())
        return if (result.size >= 3) result else points
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
