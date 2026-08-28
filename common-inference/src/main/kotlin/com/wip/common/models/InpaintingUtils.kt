package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
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
     * Renders a comprehensive color-coded visual debug image showing all detected/segmented bounding boxes,
     * transparent polygon overlays, class labels, confidence percentages, optional dual-color sliding window tile grids,
     * and optional Stage 2 Segmentation ROI crops.
     */
    fun renderDebugVisualization(
        baseImage: BufferedImage,
        objects: List<SegmentedObject>,
        candidateBoxes: List<DetectionBox> = emptyList(),
        slices: List<SliceWindow> = emptyList(),
        segmentationRois: List<DetectionBox> = emptyList()
    ): BufferedImage {
        val width = baseImage.width
        val height = baseImage.height
        val debugImg = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2d = debugImg.createGraphics()
        g2d.drawImage(baseImage, 0, 0, null)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // 1. Draw SAHI sliding window tile slice grids with alternating dual colors if supplied
        if (slices.isNotEmpty()) {
            val tileColors = listOf(
                Color(0, 220, 255, 230),  // Electric Cyan
                Color(255, 50, 180, 230)  // Hot Magenta
            )
            val tileStroke = java.awt.BasicStroke(2.0f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10.0f, floatArrayOf(8.0f, 6.0f), 0.0f)
            val font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12)
            g2d.font = font

            for ((index, slice) in slices.withIndex()) {
                val color = tileColors[index % tileColors.size]
                g2d.color = color
                g2d.stroke = tileStroke
                g2d.drawRect(slice.x, slice.y, slice.width, slice.height)

                val badgeText = "Tile #${index + 1} (${slice.width}x${slice.height})"
                val fontMetrics = g2d.fontMetrics
                val textW = fontMetrics.stringWidth(badgeText)
                val textH = fontMetrics.height
                val badgeX = (slice.x + 8).coerceIn(4, max(4, width - textW - 12))
                val badgeY = (slice.y + textH + 4).coerceIn(textH + 4, height - 4)

                g2d.color = Color(0, 0, 0, 210)
                g2d.fillRect(badgeX - 4, badgeY - textH + 2, textW + 8, textH + 2)
                g2d.color = color
                g2d.drawRect(badgeX - 4, badgeY - textH + 2, textW + 8, textH + 2)
                g2d.drawString(badgeText, badgeX, badgeY)
            }
        }

        // 2. Draw Stage 2 Segmentation ROI crop boxes in amber/orange with dashed borders if supplied
        if (segmentationRois.isNotEmpty()) {
            val roiStroke = java.awt.BasicStroke(2.0f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10.0f, floatArrayOf(6.0f, 4.0f), 0.0f)
            val font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11)
            g2d.font = font
            val roiColor = Color(255, 160, 0, 220) // Amber / Orange

            for ((index, roi) in segmentationRois.withIndex()) {
                val rx = (roi.xmin * width).toInt().coerceIn(0, width - 1)
                val ry = (roi.ymin * height).toInt().coerceIn(0, height - 1)
                val rw = max(1, ((roi.xmax - roi.xmin) * width).toInt())
                val rh = max(1, ((roi.ymax - roi.ymin) * height).toInt())

                g2d.color = roiColor
                g2d.stroke = roiStroke
                g2d.drawRect(rx, ry, rw, rh)

                val badgeText = "Seg ROI #${index + 1} (${rw}x${rh})"
                val fontMetrics = g2d.fontMetrics
                val textW = fontMetrics.stringWidth(badgeText)
                val textH = fontMetrics.height
                val badgeX = (rx + 4).coerceIn(4, max(4, width - textW - 10))
                val badgeY = (ry + rh - 6).coerceIn(textH + 4, height - 4)

                g2d.color = Color(0, 0, 0, 210)
                g2d.fillRect(badgeX - 3, badgeY - textH + 2, textW + 6, textH + 2)
                g2d.color = roiColor
                g2d.drawRect(badgeX - 3, badgeY - textH + 2, textW + 6, textH + 2)
                g2d.drawString(badgeText, badgeX, badgeY)
            }
        }

        // 3. Draw candidate boxes in thin dashed outline if supplied
        if (candidateBoxes.isNotEmpty()) {
            g2d.stroke = java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(5f, 5f), 0f)
            for (box in candidateBoxes) {
                val bx = (box.xmin * width).toInt().coerceIn(0, width - 1)
                val by = (box.ymin * height).toInt().coerceIn(0, height - 1)
                val bw = max(1, ((box.xmax - box.xmin) * width).toInt())
                val bh = max(1, ((box.ymax - box.ymin) * height).toInt())
                g2d.color = Color(255, 255, 0, 180)
                g2d.drawRect(bx, by, bw, bh)
            }
        }

        // 4. Draw segmented objects: polygon fills, polygon borders, bounding boxes, labels
        for (obj in objects) {
            val label = obj.label.trim().lowercase()
            val (baseColor, fillColor) = when {
                label.contains("balloon") || label.contains("bubble") || label.contains("circular") -> Pair(Color(0, 200, 255), Color(0, 200, 255, 75))
                label == "text" -> Pair(Color(50, 255, 50), Color(50, 255, 50, 80))
                label.contains("watermark") -> Pair(Color(255, 0, 255), Color(255, 0, 255, 80))
                label.contains("panel") -> Pair(Color(255, 140, 0), Color(255, 140, 0, 60))
                else -> Pair(Color(255, 220, 0), Color(255, 220, 0, 75))
            }

            // Fill & draw polygon if available
            if (obj.polygon.size >= 3) {
                val poly = Polygon()
                for (pt in obj.polygon) {
                    val px = (pt.x * width).toInt().coerceIn(0, width - 1)
                    val py = (pt.y * height).toInt().coerceIn(0, height - 1)
                    poly.addPoint(px, py)
                }
                g2d.color = fillColor
                g2d.fillPolygon(poly)
                g2d.color = baseColor
                g2d.stroke = java.awt.BasicStroke(2.5f)
                g2d.drawPolygon(poly)
            }

            // Draw bounding box
            val bx = (obj.box.xmin * width).toInt().coerceIn(0, width - 1)
            val by = (obj.box.ymin * height).toInt().coerceIn(0, height - 1)
            val bw = max(1, ((obj.box.xmax - obj.box.xmin) * width).toInt())
            val bh = max(1, ((obj.box.ymax - obj.box.ymin) * height).toInt())

            g2d.color = baseColor
            g2d.stroke = java.awt.BasicStroke(2.0f)
            g2d.drawRect(bx, by, bw, bh)

            // Draw label badge with dark background
            val tag = "${obj.label} ${(obj.confidence * 100).toInt()}%"
            val fontMetrics = g2d.fontMetrics
            val textWidth = fontMetrics.stringWidth(tag)
            val textHeight = fontMetrics.height
            val tagX = bx + 3
            val tagY = max(textHeight + 2, by - 4)

            g2d.color = Color(0, 0, 0, 190)
            g2d.fillRect(tagX - 2, tagY - textHeight + 2, textWidth + 6, textHeight + 2)
            g2d.color = baseColor
            g2d.drawRect(tagX - 2, tagY - textHeight + 2, textWidth + 6, textHeight + 2)
            g2d.drawString(tag, tagX + 1, tagY)
        }

        g2d.dispose()
        return debugImg
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
     * Uses pre-allocated native direct off-heap FloatBuffers to eliminate JVM heap allocation and churn.
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

        // Prepare condition_concat direct tensor [1, 4, H, W]
        val imgPixels = IntArray(targetW * targetH)
        resizedImg.getRGB(0, 0, targetW, targetH, imgPixels, 0, targetW)

        val maskPixels = IntArray(targetW * targetH)
        resizedMask.raster.getSamples(0, 0, targetW, targetH, 0, maskPixels)

        val channelSize = targetW * targetH
        val condBuffer = ImageTensorUtils.allocateDirectFloatBuffer(1 * 4 * targetH * targetW)

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
        // Pre-allocate ping-pong sample direct buffers and timestep embedding direct buffer
        val sampleBufferA = ImageTensorUtils.allocateDirectFloatBuffer(1 * 3 * targetH * targetW)
        val sampleBufferB = ImageTensorUtils.allocateDirectFloatBuffer(1 * 3 * targetH * targetW)
        val tEmbedBuffer = ImageTensorUtils.allocateDirectFloatBuffer(embedDim)

        for (i in 0 until 3 * channelSize) {
            sampleBufferA.put(i, rng.nextGaussian().toFloat())
        }
        sampleBufferA.rewind()

        val sampleInputName = if (spec.inputNames.isNotEmpty()) spec.inputNames[0] else "sample"
        val timestepInputName = if (spec.inputNames.size > 1) spec.inputNames[1] else "timestep_embed"
        val condInputName = if (spec.inputNames.size > 2) spec.inputNames[2] else "condition_concat"

        val stepStride = (totalTimesteps / steps).coerceAtLeast(1)
        val timePoints = (totalTimesteps - 1 downTo 0 step stepStride).toList()

        var ping = true
        val sampleShape = longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
        val tEmbedShape = longArrayOf(1L, embedDim.toLong())
        val halfDim = embedDim / 2
        val embFactor = (-ln(10000.0) / (halfDim - 1)).toFloat()

        try {
            for (t in timePoints) {
                val currentSample = if (ping) sampleBufferA else sampleBufferB
                val nextSample = if (ping) sampleBufferB else sampleBufferA
                currentSample.rewind()
                nextSample.clear()

                val sampleTensor = OnnxTensor.createTensor(session.environment, currentSample, sampleShape)

                tEmbedBuffer.clear()
                for (i in 0 until halfDim) {
                    val freq = exp((i * embFactor).toDouble()).toFloat()
                    val arg = t.toFloat() * freq
                    tEmbedBuffer.put(i, sin(arg.toDouble()).toFloat())
                    tEmbedBuffer.put(halfDim + i, cos(arg.toDouble()).toFloat())
                }
                tEmbedBuffer.rewind()
                val tEmbedTensor = OnnxTensor.createTensor(session.environment, tEmbedBuffer, tEmbedShape)

                var results: OrtSession.Result? = null
                try {
                    results = session.session.run(
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
                            nextSample.put(i, nextVal.coerceIn(-1.0f, 1.0f))
                        }
                        nextSample.rewind()
                        ping = !ping
                    }
                } finally {
                    results?.close()
                    sampleTensor.close()
                    tEmbedTensor.close()
                }
            }
        } finally {
            condTensor.close()
        }

        val finalSample = if (ping) sampleBufferA else sampleBufferB
        finalSample.rewind()

        val outImg = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val outPixels = IntArray(targetW * targetH)
        for (i in 0 until channelSize) {
            val r = (((finalSample.get(0 * channelSize + i) + 1.0f) * 127.5f).toInt()).coerceIn(0, 255)
            val g = (((finalSample.get(1 * channelSize + i) + 1.0f) * 127.5f).toInt()).coerceIn(0, 255)
            val b = (((finalSample.get(2 * channelSize + i) + 1.0f) * 127.5f).toInt()).coerceIn(0, 255)
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

                    var results: OrtSession.Result? = null
                    try {
                        results = session.session.run(mapOf(imgInputName to imgTensor, maskInputName to maskTensor))
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
                    } finally {
                        results?.close()
                        imgTensor.close()
                        maskTensor.close()
                    }
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

        val outputImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d = outputImage.createGraphics()
        g2d.drawImage(sourceImage, 0, 0, null)
        g2d.dispose()

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

            val gPatch = outputImage.createGraphics()
            gPatch.drawImage(cleanedPatch, rx, ry, null)
            gPatch.dispose()
        }

        return outputImage
    }

    /**
     * Inpaints only the segmented/masked regions and renders them onto an alpha-transparent canvas (TYPE_INT_ARGB).
     * Non-inpainted background pixels remain completely transparent (alpha = 0).
     * Inpainted regions are alpha-composited with soft edge feathering to allow seamless layer-based PSD / compositing workflows.
     */
    fun inpaintImageIsolated(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        session: OnnxInferenceSession? = null,
        spec: ModelSpec? = null,
        roiPaddingPx: Int = 24,
        featherRadiusPx: Int = 2
    ): BufferedImage {
        val width = sourceImage.width
        val height = sourceImage.height

        val fullyCleaned = if (session != null && spec != null) {
            inpaintWithOnnx(
                sourceImage = sourceImage,
                mask = mask,
                session = session,
                spec = spec,
                roiPaddingPx = roiPaddingPx
            )
        } else {
            inpaintImage(
                sourceImage = sourceImage,
                mask = mask,
                roiPaddingPx = roiPaddingPx
            )
        }

        val isolatedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val cleanedPixels = IntArray(width * height)
        fullyCleaned.getRGB(0, 0, width, height, cleanedPixels, 0, width)

        val maskRaster = mask.raster
        val maskPixels = IntArray(width * height)
        maskRaster.getSamples(0, 0, width, height, 0, maskPixels)

        val alphaValues = IntArray(width * height)
        for (i in 0 until width * height) {
            if (maskPixels[i] > 128) {
                alphaValues[i] = 255
            }
        }

        val featheredAlpha = if (featherRadiusPx > 0) {
            applyAlphaFeathering(alphaValues, width, height, featherRadiusPx)
        } else {
            alphaValues
        }

        val outPixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val a = featheredAlpha[i].coerceIn(0, 255)
            if (a > 0) {
                val rgb = cleanedPixels[i] and 0x00FFFFFF
                outPixels[i] = (a shl 24) or rgb
            } else {
                outPixels[i] = 0x00000000
            }
        }

        isolatedImage.setRGB(0, 0, width, height, outPixels, 0, width)
        return isolatedImage
    }

    private fun applyAlphaFeathering(alpha: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val blurred = IntArray(width * height)
        val rSq = radius * radius
        for (y in 0 until height) {
            val yMin = max(0, y - radius)
            val yMax = min(height - 1, y + radius)
            for (x in 0 until width) {
                val idx = y * width + x
                if (alpha[idx] == 255) {
                    blurred[idx] = 255
                } else {
                    val xMin = max(0, x - radius)
                    val xMax = min(width - 1, x + radius)
                    var count = 0
                    var total = 0
                    for (ny in yMin..yMax) {
                        val dy = ny - y
                        val nyOff = ny * width
                        for (nx in xMin..xMax) {
                            val dx = nx - x
                            if (dx * dx + dy * dy <= rSq) {
                                total += alpha[nyOff + nx]
                                count++
                            }
                        }
                    }
                    blurred[idx] = if (count > 0) (total / count).coerceIn(0, 255) else 0
                }
            }
        }
        return blurred
    }

    /**
     * Pure Kotlin base inpainting algorithm for an individual patch.
     * Uses Fast Marching border pixel distance weighting and edge-aware bilateral color propagation
     * with flat primitive arrays to prevent heap object allocations.
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

        var bpCapacity = 1024
        var bpCount = 0
        var bpX = IntArray(bpCapacity)
        var bpY = IntArray(bpCapacity)
        var bpR = IntArray(bpCapacity)
        var bpG = IntArray(bpCapacity)
        var bpB = IntArray(bpCapacity)

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                if (!isMasked[idx]) {
                    val hasMaskNeighbor = (x > 0 && isMasked[idx - 1]) ||
                            (x < w - 1 && isMasked[idx + 1]) ||
                            (y > 0 && isMasked[idx - w]) ||
                            (y < h - 1 && isMasked[idx + w])

                    if (hasMaskNeighbor) {
                        if (bpCount >= bpCapacity) {
                            bpCapacity *= 2
                            bpX = bpX.copyOf(bpCapacity)
                            bpY = bpY.copyOf(bpCapacity)
                            bpR = bpR.copyOf(bpCapacity)
                            bpG = bpG.copyOf(bpCapacity)
                            bpB = bpB.copyOf(bpCapacity)
                        }
                        val rgb = imgPixels[idx]
                        bpX[bpCount] = x
                        bpY[bpCount] = y
                        bpR[bpCount] = (rgb shr 16) and 0xFF
                        bpG[bpCount] = (rgb shr 8) and 0xFF
                        bpB[bpCount] = rgb and 0xFF
                        bpCount++
                    }
                }
            }
        }

        val outPixels = imgPixels.clone()

        if (bpCount > 0) {
            // For dense boundary collections, subsample to maintain high responsiveness
            val step = if (bpCount > 400) max(1, bpCount / 200) else 1

            for (y in 0 until h) {
                val yOff = y * w
                for (x in 0 until w) {
                    val idx = yOff + x
                    if (isMasked[idx]) {
                        var sumWeight = 0.0
                        var sumR = 0.0
                        var sumG = 0.0
                        var sumB = 0.0

                        var k = 0
                        while (k < bpCount) {
                            val dx = bpX[k] - x
                            val dy = bpY[k] - y
                            val distSq = (dx * dx + dy * dy).toDouble()
                            val dist = sqrt(distSq) + 0.1

                            val weight = 1.0 / (dist * dist)
                            sumWeight += weight
                            sumR += bpR[k] * weight
                            sumG += bpG[k] * weight
                            sumB += bpB[k] * weight
                            k += step
                        }

                        if (sumWeight > 0.0) {
                            val finalR = (sumR / sumWeight).toInt().coerceIn(0, 255)
                            val finalG = (sumG / sumWeight).toInt().coerceIn(0, 255)
                            val finalB = (sumB / sumWeight).toInt().coerceIn(0, 255)
                            outPixels[idx] = (finalR shl 16) or (finalG shl 8) or finalB
                        }
                    }
                }
            }
        }

        result.setRGB(0, 0, w, h, outPixels, 0, w)
        return result
    }

    /**
     * Finds disjoint bounding box clusters of white mask pixels to form isolated ROI patches.
     * Uses a non-allocating stack with primitive coordinates for fast, memory-safe execution.
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
        var stackCapacity = 2048
        var stackSize = 0
        var stackX = IntArray(stackCapacity)
        var stackY = IntArray(stackCapacity)

        for (y in 0 until h step gridStep) {
            for (x in 0 until w step gridStep) {
                val idx = y * w + x
                if (maskPixels[idx] > 128 && !visited[idx]) {
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y

                    // Push root
                    stackX[0] = x
                    stackY[0] = y
                    stackSize = 1
                    visited[idx] = true

                    while (stackSize > 0) {
                        stackSize--
                        val cx = stackX[stackSize]
                        val cy = stackY[stackSize]

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        // Left
                        val leftX = cx - gridStep
                        if (leftX >= 0) {
                            val nIdx = cy * w + leftX
                            if (maskPixels[nIdx] > 128 && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (stackSize >= stackCapacity) {
                                    stackCapacity *= 2
                                    stackX = stackX.copyOf(stackCapacity)
                                    stackY = stackY.copyOf(stackCapacity)
                                }
                                stackX[stackSize] = leftX
                                stackY[stackSize] = cy
                                stackSize++
                            }
                        }
                        // Right
                        val rightX = cx + gridStep
                        if (rightX < w) {
                            val nIdx = cy * w + rightX
                            if (maskPixels[nIdx] > 128 && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (stackSize >= stackCapacity) {
                                    stackCapacity *= 2
                                    stackX = stackX.copyOf(stackCapacity)
                                    stackY = stackY.copyOf(stackCapacity)
                                }
                                stackX[stackSize] = rightX
                                stackY[stackSize] = cy
                                stackSize++
                            }
                        }
                        // Up
                        val upY = cy - gridStep
                        if (upY >= 0) {
                            val nIdx = upY * w + cx
                            if (maskPixels[nIdx] > 128 && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (stackSize >= stackCapacity) {
                                    stackCapacity *= 2
                                    stackX = stackX.copyOf(stackCapacity)
                                    stackY = stackY.copyOf(stackCapacity)
                                }
                                stackX[stackSize] = cx
                                stackY[stackSize] = upY
                                stackSize++
                            }
                        }
                        // Down
                        val downY = cy + gridStep
                        if (downY < h) {
                            val nIdx = downY * w + cx
                            if (maskPixels[nIdx] > 128 && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (stackSize >= stackCapacity) {
                                    stackCapacity *= 2
                                    stackX = stackX.copyOf(stackCapacity)
                                    stackY = stackY.copyOf(stackCapacity)
                                }
                                stackX[stackSize] = cx
                                stackY[stackSize] = downY
                                stackSize++
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
        return a.x <= b.xmax && a.xmax >= b.x && a.y <= b.ymax && a.ymax >= b.y
    }

    private fun union(a: SliceWindow, b: SliceWindow): SliceWindow {
        val minX = min(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxX = max(a.xmax, b.xmax)
        val maxY = max(a.ymax, b.ymax)
        return SliceWindow(x = minX, y = minY, width = maxX - minX, height = maxY - minY)
    }
}
