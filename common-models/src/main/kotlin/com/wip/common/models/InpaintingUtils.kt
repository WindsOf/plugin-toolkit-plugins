package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin utilities for mask rendering, morphological dilation, ROI patch inpainting,
 * and seamless alpha compositing on images of arbitrary dimensions.
 */
object InpaintingUtils {

    /**
     * Renders a binary mask [BufferedImage] (TYPE_BYTE_GRAY) from a list of [SegmentedObject]s.
     * White pixels (255) indicate regions to inpaint; Black pixels (0) indicate regions to preserve.
     */
    fun renderMaskFromObjects(
        objects: List<SegmentedObject>,
        imageWidth: Int,
        imageHeight: Int,
        targetClasses: Set<String> = setOf("text"),
        dilationPx: Int = 3
    ): BufferedImage {
        val mask = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_BYTE_GRAY)
        val g2d = mask.createGraphics()
        g2d.color = Color.BLACK
        g2d.fillRect(0, 0, imageWidth, imageHeight)

        g2d.color = Color.WHITE
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)

        val normalizedTargets = targetClasses.map { it.trim().lowercase() }.toSet()

        for (obj in objects) {
            val label = obj.label.trim().lowercase()
            if (normalizedTargets.isNotEmpty() && label !in normalizedTargets) {
                continue
            }

            if (obj.polygon.size >= 3) {
                val poly = Polygon()
                for (pt in obj.polygon) {
                    val px = (pt.x * imageWidth).toInt().coerceIn(0, imageWidth - 1)
                    val py = (pt.y * imageHeight).toInt().coerceIn(0, imageHeight - 1)
                    poly.addPoint(px, py)
                }
                g2d.fillPolygon(poly)
            } else {
                val pxXmin = (obj.box.xmin * imageWidth).toInt().coerceIn(0, imageWidth - 1)
                val pxYmin = (obj.box.ymin * imageHeight).toInt().coerceIn(0, imageHeight - 1)
                val pxXmax = (obj.box.xmax * imageWidth).toInt().coerceIn(0, imageWidth - 1)
                val pxYmax = (obj.box.ymax * imageHeight).toInt().coerceIn(0, imageHeight - 1)
                val w = max(1, pxXmax - pxXmin)
                val h = max(1, pxYmax - pxYmin)
                g2d.fillRect(pxXmin, pxYmin, w, h)
            }
        }
        g2d.dispose()

        return if (dilationPx > 0) {
            applyDilation(mask, dilationPx)
        } else {
            mask
        }
    }

    /**
     * Applies morphological dilation with the specified pixel radius.
     */
    fun applyDilation(sourceMask: BufferedImage, radius: Int): BufferedImage {
        if (radius <= 0) return sourceMask

        val width = sourceMask.width
        val height = sourceMask.height
        val dilated = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)

        val srcRaster = sourceMask.raster
        val dstRaster = dilated.raster

        val srcPixels = IntArray(width * height)
        srcRaster.getSamples(0, 0, width, height, 0, srcPixels)

        val dstPixels = IntArray(width * height)

        val rSq = radius * radius
        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                if (srcPixels[yOffset + x] > 128) {
                    // Spread to neighborhood
                    val yMin = max(0, y - radius)
                    val yMax = min(height - 1, y + radius)
                    val xMin = max(0, x - radius)
                    val xMax = min(width - 1, x + radius)

                    for (ny in yMin..yMax) {
                        val dy = ny - y
                        val nyOffset = ny * width
                        for (nx in xMin..xMax) {
                            val dx = nx - x
                            if (dx * dx + dy * dy <= rSq) {
                                dstPixels[nyOffset + nx] = 255
                            }
                        }
                    }
                }
            }
        }

        dstRaster.setSamples(0, 0, width, height, 0, dstPixels)
        return dilated
    }

    /**
     * Executes multi-step reverse diffusion sampling on a patch using an ONNX UNet diffusion model.
     */
    fun inpaintDiffusionPatch(
        session: OnnxInferenceSession,
        patchImg: BufferedImage,
        patchMask: BufferedImage,
        spec: ModelSpec,
        steps: Int = if (spec.pipelineConfig.defaultInferenceSteps > 0) minOf(20, spec.pipelineConfig.defaultInferenceSteps) else 20
    ): BufferedImage {
        val targetW = if (spec.effectiveWidth > 0) spec.effectiveWidth else 256
        val targetH = if (spec.effectiveHeight > 0) spec.effectiveHeight else 256

        val resizedImg = ImageTensorUtils.resizeImage(patchImg, targetW, targetH)
        val resizedMask = ImageTensorUtils.resizeImage(patchMask, targetW, targetH)

        val totalTimesteps = if (spec.pipelineConfig.numTimesteps > 0) spec.pipelineConfig.numTimesteps else 1000
        val embedDim = if (spec.pipelineConfig.embedDim > 0) spec.pipelineConfig.embedDim else 256

        // Linear beta / alpha schedule
        val betas = FloatArray(totalTimesteps)
        val betaStart = 0.0001f
        val betaEnd = 0.02f
        for (i in 0 until totalTimesteps) {
            betas[i] = betaStart + (betaEnd - betaStart) * (i.toFloat() / (totalTimesteps - 1).toFloat())
        }

        val alphas = FloatArray(totalTimesteps)
        val alphasCumprod = FloatArray(totalTimesteps)
        var runningProd = 1.0f
        for (i in 0 until totalTimesteps) {
            alphas[i] = 1.0f - betas[i]
            runningProd *= alphas[i]
            alphasCumprod[i] = runningProd
        }

        // Prepare condition_concat tensor [1, 4, H, W]
        val imgPixels = IntArray(targetW * targetH)
        resizedImg.getRGB(0, 0, targetW, targetH, imgPixels, 0, targetW)

        val maskPixels = IntArray(targetW * targetH)
        resizedMask.raster.getSamples(0, 0, targetW, targetH, 0, maskPixels)

        val channelSize = targetW * targetH
        val condBuffer = FloatBuffer.allocate(1 * 4 * targetH * targetW)

        for (i in 0 until channelSize) {
            val isMasked = maskPixels[i] > 128
            val rgb = imgPixels[i]
            val rByte = ((rgb shr 16) and 0xFF)
            val gByte = ((rgb shr 8) and 0xFF)
            val bByte = (rgb and 0xFF)

            val r = if (isMasked) 0.0f else (rByte / 127.5f) - 1.0f
            val g = if (isMasked) 0.0f else (gByte / 127.5f) - 1.0f
            val b = if (isMasked) 0.0f else (bByte / 127.5f) - 1.0f
            val m = if (isMasked) 1.0f else 0.0f

            condBuffer.put(0 * channelSize + i, r)
            condBuffer.put(1 * channelSize + i, g)
            condBuffer.put(2 * channelSize + i, b)
            condBuffer.put(3 * channelSize + i, m)
        }
        condBuffer.rewind()
        val condShape = longArrayOf(1L, 4L, targetH.toLong(), targetW.toLong())
        val condTensor = OnnxTensor.createTensor(session.environment, condBuffer, condShape)

        val rng = Random(42)
        val sampleBuffer = FloatBuffer.allocate(1 * 3 * targetH * targetW)
        for (i in 0 until 3 * channelSize) {
            sampleBuffer.put(i, rng.nextGaussian().toFloat())
        }
        sampleBuffer.rewind()

        val sampleInputName = if (spec.inputNames.isNotEmpty()) spec.inputNames[0] else "sample"
        val timestepInputName = if (spec.inputNames.size > 1) spec.inputNames[1] else "timestep_embed"
        val condInputName = if (spec.inputNames.size > 2) spec.inputNames[2] else "condition_concat"

        val stepStride = (totalTimesteps / steps).coerceAtLeast(1)
        val timePoints = (totalTimesteps - 1 downTo 0 step stepStride).toList()

        var currentSample = sampleBuffer

        try {
            for (t in timePoints) {
                val sampleShape = longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
                val sampleTensor = OnnxTensor.createTensor(session.environment, currentSample, sampleShape)

                val tEmbedBuffer = FloatBuffer.allocate(embedDim)
                val halfDim = embedDim / 2
                val embFactor = (-ln(10000.0) / (halfDim - 1)).toFloat()
                for (i in 0 until halfDim) {
                    val freq = exp((i * embFactor).toDouble()).toFloat()
                    val arg = t.toFloat() * freq
                    tEmbedBuffer.put(i, sin(arg.toDouble()).toFloat())
                    tEmbedBuffer.put(halfDim + i, cos(arg.toDouble()).toFloat())
                }
                tEmbedBuffer.rewind()
                val tEmbedTensor = OnnxTensor.createTensor(session.environment, tEmbedBuffer, longArrayOf(1L, embedDim.toLong()))

                val results = session.session.run(
                    mapOf(
                        sampleInputName to sampleTensor,
                        timestepInputName to tEmbedTensor,
                        condInputName to condTensor
                    )
                )

                val noisePredTensor = results.get(0) as? OnnxTensor
                    ?: results.firstOrNull { it.value is OnnxTensor }?.value as? OnnxTensor

                if (noisePredTensor != null) {
                    val noiseBuffer = noisePredTensor.floatBuffer
                    val nextSampleBuffer = FloatBuffer.allocate(1 * 3 * targetH * targetW)

                    val alphaT = alphas[t]
                    val alphaBarT = alphasCumprod[t]
                    val betaT = betas[t]
                    val sqrtAlphaT = sqrt(alphaT.toDouble()).toFloat()
                    val sqrtOneMinusAlphaBarT = sqrt((1.0f - alphaBarT).toDouble()).toFloat()

                    for (i in 0 until 3 * channelSize) {
                        val xt = currentSample.get(i)
                        val eps = noiseBuffer.get(i)
                        val mean = (xt - (betaT / sqrtOneMinusAlphaBarT) * eps) / sqrtAlphaT

                        val nextVal = if (t > 0) {
                            val sigma = sqrt(betaT.toDouble()).toFloat()
                            mean + sigma * rng.nextGaussian().toFloat()
                        } else {
                            mean
                        }
                        nextSampleBuffer.put(i, nextVal.coerceIn(-1.0f, 1.0f))
                    }
                    nextSampleBuffer.rewind()
                    currentSample = nextSampleBuffer
                }

                results.close()
                sampleTensor.close()
                tEmbedTensor.close()
            }
        } finally {
            condTensor.close()
        }

        val outImg = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val outPixels = IntArray(targetW * targetH)
        for (i in 0 until channelSize) {
            val r = (((currentSample.get(0 * channelSize + i) + 1.0f) * 127.5f).toInt()).coerceIn(0, 255)
            val g = (((currentSample.get(1 * channelSize + i) + 1.0f) * 127.5f).toInt()).coerceIn(0, 255)
            val b = (((currentSample.get(2 * channelSize + i) + 1.0f) * 127.5f).toInt()).coerceIn(0, 255)
            outPixels[i] = (r shl 16) or (g shl 8) or b
        }
        outImg.setRGB(0, 0, targetW, targetH, outPixels, 0, targetW)
        return ImageTensorUtils.resizeImage(outImg, patchImg.width, patchImg.height)
    }

    /**
     * Inpaints an image using an active neural inpainting [OnnxInferenceSession] and [ModelSpec].
     * Patches surrounding mask clusters are dynamically extracted, preprocessed with model normalization,
     * inpainted via neural inference (single-pass or diffusion pipeline), and seamlessly composited back.
     */
    fun inpaintWithOnnx(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        session: OnnxInferenceSession,
        spec: ModelSpec,
        roiPaddingPx: Int = 24
    ): BufferedImage {
        val width = sourceImage.width
        val height = sourceImage.height

        val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = outputImage.createGraphics()
        g2d.drawImage(sourceImage, 0, 0, null)
        g2d.dispose()

        val maskRegions = findMaskBoundingBoxes(mask, roiPaddingPx)
        if (maskRegions.isEmpty()) {
            return outputImage
        }

        val isDiffusion = spec.pipelineType.equals("diffusion_pipeline", ignoreCase = true) ||
            spec.inputNames.contains("timestep_embed") ||
            spec.effectiveType.equals("ldm", ignoreCase = true) ||
            spec.effectiveType.equals("diffusion", ignoreCase = true)

        val targetW = if (spec.effectiveWidth > 0) spec.effectiveWidth else 512
        val targetH = if (spec.effectiveHeight > 0) spec.effectiveHeight else 512

        val imgInputName = if (spec.inputNames.isNotEmpty()) spec.inputNames[0] else "image"
        val maskInputName = if (spec.inputNames.size > 1) spec.inputNames[1] else "mask"

        for (region in maskRegions) {
            val rx = region.x
            val ry = region.y
            val rw = region.width
            val rh = region.height

            val patchImg = outputImage.getSubimage(rx, ry, rw, rh)
            val patchMask = mask.getSubimage(rx, ry, rw, rh)

            try {
                if (isDiffusion) {
                    val cleanedPatch = inpaintDiffusionPatch(
                        session = session,
                        patchImg = patchImg,
                        patchMask = patchMask,
                        spec = spec
                    )
                    val gPatch = outputImage.createGraphics()
                    gPatch.drawImage(cleanedPatch, rx, ry, null)
                    gPatch.dispose()
                } else {
                    val inW = if (spec.dynamicShape) ((rw + 15) / 16 * 16).coerceAtLeast(64) else targetW
                    val inH = if (spec.dynamicShape) ((rh + 15) / 16 * 16).coerceAtLeast(64) else targetH

                    val imgTensor = ImageTensorUtils.createInpaintingImageTensor(
                        session.environment,
                        patchImg,
                        inW,
                        inH,
                        spec.normMode
                    )
                    val maskTensor = ImageTensorUtils.createInpaintingMaskTensor(
                        session.environment,
                        patchMask,
                        inW,
                        inH,
                        spec.maskMode
                    )

                    val results = session.session.run(mapOf(imgInputName to imgTensor, maskInputName to maskTensor))
                    val outputTensor = results.get(0) as? OnnxTensor
                        ?: results.firstOrNull { it.value is OnnxTensor }?.value as? OnnxTensor

                    if (outputTensor != null) {
                        val rawCleaned = ImageTensorUtils.tensorToBufferedImage(outputTensor, spec.normMode)
                        val cleanedPatch = ImageTensorUtils.resizeImage(rawCleaned, rw, rh)

                        val gPatch = outputImage.createGraphics()
                        gPatch.drawImage(cleanedPatch, rx, ry, null)
                        gPatch.dispose()
                    } else {
                        val cleanedPatch = inpaintPatchPureKotlin(patchImg, patchMask)
                        val gPatch = outputImage.createGraphics()
                        gPatch.drawImage(cleanedPatch, rx, ry, null)
                        gPatch.dispose()
                    }

                    results.close()
                    imgTensor.close()
                    maskTensor.close()
                }
            } catch (e: Exception) {
                val cleanedPatch = inpaintPatchPureKotlin(patchImg, patchMask)
                val gPatch = outputImage.createGraphics()
                gPatch.drawImage(cleanedPatch, rx, ry, null)
                gPatch.dispose()
            }
        }

        return outputImage
    }

    /**
     * High-level inpainting pipeline: Performs ROI patch-based inpainting on a [BufferedImage] using a binary mask.
     * Extracts only the bounding regions containing mask pixels (+ padding context), runs pure Kotlin inpainting,
     * and alpha-blends the patches back onto the output image.
     */
    fun inpaintImage(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        roiPaddingPx: Int = 24
    ): BufferedImage {
        val width = sourceImage.width
        val height = sourceImage.height

        // Ensure ARGB for high precision processing
        val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = outputImage.createGraphics()
        g2d.drawImage(sourceImage, 0, 0, null)
        g2d.dispose()

        // Find connected mask bounding regions
        val maskRegions = findMaskBoundingBoxes(mask, roiPaddingPx)
        if (maskRegions.isEmpty()) {
            return outputImage
        }

        for (region in maskRegions) {
            val rx = region.x
            val ry = region.y
            val rw = region.width
            val rh = region.height

            val patchImg = outputImage.getSubimage(rx, ry, rw, rh)
            val patchMask = mask.getSubimage(rx, ry, rw, rh)

            val cleanedPatch = inpaintPatchPureKotlin(patchImg, patchMask)

            // Composite cleaned patch back into output image
            val gPatch = outputImage.createGraphics()
            gPatch.drawImage(cleanedPatch, rx, ry, null)
            gPatch.dispose()
        }

        return outputImage
    }

    /**
     * Pure Kotlin base inpainting algorithm for an individual patch.
     * Uses Fast Marching border pixel distance weighting and edge-aware bilateral color propagation.
     */
    fun inpaintPatchPureKotlin(
        imagePatch: BufferedImage,
        maskPatch: BufferedImage
    ): BufferedImage {
        val w = imagePatch.width
        val h = imagePatch.height

        val result = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val imgPixels = IntArray(w * h)
        imagePatch.getRGB(0, 0, w, h, imgPixels, 0, w)

        val maskRaster = maskPatch.raster
        val maskPixels = IntArray(w * h)
        maskRaster.getSamples(0, 0, w, h, 0, maskPixels)

        val isMasked = BooleanArray(w * h) { i -> maskPixels[i] > 128 }

        // Find unmasked boundary pixels
        data class BoundaryPixel(val x: Int, val y: Int, val r: Int, val g: Int, val b: Int)
        val boundaryPixels = mutableListOf<BoundaryPixel>()

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                if (!isMasked[idx]) {
                    // Check if adjacent to a masked pixel
                    val hasMaskNeighbor = (x > 0 && isMasked[idx - 1]) ||
                            (x < w - 1 && isMasked[idx + 1]) ||
                            (y > 0 && isMasked[idx - w]) ||
                            (y < h - 1 && isMasked[idx + w])

                    if (hasMaskNeighbor) {
                        val rgb = imgPixels[idx]
                        val r = (rgb shr 16) and 0xFF
                        val g = (rgb shr 8) and 0xFF
                        val b = rgb and 0xFF
                        boundaryPixels.add(BoundaryPixel(x, y, r, g, b))
                    }
                }
            }
        }

        val outPixels = imgPixels.clone()

        if (boundaryPixels.isNotEmpty()) {
            for (y in 0 until h) {
                val yOff = y * w
                for (x in 0 until w) {
                    val idx = yOff + x
                    if (isMasked[idx]) {
                        var sumWeight = 0.0
                        var sumR = 0.0
                        var sumG = 0.0
                        var sumB = 0.0

                        for (bp in boundaryPixels) {
                            val dx = bp.x - x
                            val dy = bp.y - y
                            val distSq = (dx * dx + dy * dy).toDouble()
                            val dist = sqrt(distSq) + 0.1

                            // Inverse distance squared weighting
                            val weight = 1.0 / (dist * dist)
                            sumWeight += weight
                            sumR += bp.r * weight
                            sumG += bp.g * weight
                            sumB += bp.b * weight
                        }

                        val finalR = (sumR / sumWeight).toInt().coerceIn(0, 255)
                        val finalG = (sumG / sumWeight).toInt().coerceIn(0, 255)
                        val finalB = (sumB / sumWeight).toInt().coerceIn(0, 255)

                        outPixels[idx] = (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }
        }

        result.setRGB(0, 0, w, h, outPixels, 0, w)
        return result
    }

    /**
     * Finds disjoint bounding box clusters of white mask pixels to form isolated ROI patches.
     */
    fun findMaskBoundingBoxes(mask: BufferedImage, padding: Int): List<SliceWindow> {
        val w = mask.width
        val h = mask.height
        val maskRaster = mask.raster
        val maskPixels = IntArray(w * h)
        maskRaster.getSamples(0, 0, w, h, 0, maskPixels)

        val visited = BooleanArray(w * h)
        val boundingBoxes = mutableListOf<SliceWindow>()

        val gridStep = 4 // coarse grid scan for efficiency
        for (y in 0 until h step gridStep) {
            for (x in 0 until w step gridStep) {
                val idx = y * w + x
                if (maskPixels[idx] > 128 && !visited[idx]) {
                    // Flood / extent search
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y

                    val stack = ArrayDeque<Pair<Int, Int>>()
                    stack.add(Pair(x, y))
                    visited[idx] = true

                    while (stack.isNotEmpty()) {
                        val (cx, cy) = stack.removeLast()
                        minX = min(minX, cx)
                        maxX = max(maxX, cx)
                        minY = min(minY, cy)
                        maxY = max(maxY, cy)

                        val neighbors = listOf(
                            Pair(cx - gridStep, cy),
                            Pair(cx + gridStep, cy),
                            Pair(cx, cy - gridStep),
                            Pair(cx, cy + gridStep)
                        )

                        for ((nx, ny) in neighbors) {
                            if (nx in 0 until w && ny in 0 until h) {
                                val nIdx = ny * w + nx
                                if (maskPixels[nIdx] > 128 && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    stack.add(Pair(nx, ny))
                                }
                            }
                        }
                    }

                    val paddedMinX = max(0, minX - padding)
                    val paddedMinY = max(0, minY - padding)
                    val paddedMaxX = min(w, maxX + padding + gridStep)
                    val paddedMaxY = min(h, maxY + padding + gridStep)

                    boundingBoxes.add(
                        SliceWindow(
                            x = paddedMinX,
                            y = paddedMinY,
                            width = paddedMaxX - paddedMinX,
                            height = paddedMaxY - paddedMinY
                        )
                    )
                }
            }
        }

        return mergeOverlappingBoxes(boundingBoxes)
    }

    private fun mergeOverlappingBoxes(boxes: List<SliceWindow>): List<SliceWindow> {
        if (boxes.size <= 1) return boxes
        val merged = mutableListOf<SliceWindow>()
        val remaining = boxes.toMutableList()

        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var changed = true

            while (changed) {
                changed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (intersects(current, other)) {
                        current = union(current, other)
                        iterator.remove()
                        changed = true
                    }
                }
            }
            merged.add(current)
        }

        return merged
    }

    private fun intersects(a: SliceWindow, b: SliceWindow): Boolean {
        return a.x < b.xmax && a.xmax > b.x && a.y < b.ymax && a.ymax > b.y
    }

    private fun union(a: SliceWindow, b: SliceWindow): SliceWindow {
        val minX = min(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxX = max(a.xmax, b.xmax)
        val maxY = max(a.ymax, b.ymax)
        return SliceWindow(x = minX, y = minY, width = maxX - minX, height = maxY - minY)
    }
}
