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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Configuration options for neural and deterministic inpainting pipelines.
 *
 * @param featherRadius Feathering radius in pixels for blending boundary alpha contours.
 * @param blendingMode Boundary blending technique (FEATHER, POISSON, MODIFIED_POISSON, LAPLACIAN_PYRAMID, NONE).
 * @param cropMargin Context expansion margin in pixels when cropping patches around mask clusters.
 * @param iterations Autoregressive sampling steps (e.g. TSR transformer sampling iterations).
 * @param addV Additive color offset correction.
 * @param mulV Multiplicative contrast scaling.
 * @param sigma256 Gaussian kernel sigma for edge detection in ZITS.
 * @param maskTh Threshold for wireframe / line proposal acceptance in ZITS.
 * @param objRemoval In ZITS/ZITS++, if true, suppress line tokens in the hole to clean objects instead of hallucinating lines.
 * @param binaryThreshold Threshold for Edge-NMS binarization in ZITS++.
 * @param padMod Multiple to snap patch dimensions to (e.g. 16 or 32).
 */
data class InpaintingOptions(
    val featherRadius: Int = 0,
    val blendingMode: BlendingMode = BlendingMode.FEATHER,
    val cropMargin: Int = 32,
    val iterations: Int = 5,
    val addV: Double = 0.0,
    val mulV: Double = 1.0,
    val sigma256: Double = 1.5,
    val maskTh: Double = 0.85,
    val objRemoval: Boolean = false,
    val binaryThreshold: Int = 50,
    val padMod: Int = 16
)

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
     * inpainted via neural inference (single-pass, MIGAN, ZITS/ZITS++, or diffusion pipeline), and seamlessly composited back.
     */
    fun inpaintWithOnnx(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        session: OnnxInferenceSession,
        spec: ModelSpec,
        roiPaddingPx: Int = 24,
        options: InpaintingOptions = InpaintingOptions(),
        multiSessions: Map<String, OnnxInferenceSession> = emptyMap()
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

        for (region in maskRegions) {
            val rx = region.x
            val ry = region.y
            val rw = region.width
            val rh = region.height

            val patchImg = outputImage.getSubimage(rx, ry, rw, rh)
            val patchMask = mask.getSubimage(rx, ry, rw, rh)

            try {
                val cleanedPatch = if (isDiffusion) {
                    inpaintDiffusionPatch(
                        session = session,
                        patchImg = patchImg,
                        patchMask = patchMask,
                        spec = spec
                    )
                } else {
                    inpaintPatchWithOnnx(
                        session = session,
                        spec = spec,
                        patchImg = patchImg,
                        patchMask = patchMask,
                        options = options,
                        multiSessions = multiSessions
                    )
                }

                val finalPatch = blendPatch(
                    cleanedPatch = cleanedPatch,
                    originalPatch = patchImg,
                    maskPatch = patchMask,
                    options = options
                )

                val gPatch = outputImage.createGraphics()
                gPatch.drawImage(finalPatch, rx, ry, null)
                gPatch.dispose()
            } catch (e: Exception) {
                val cleanedPatch = inpaintPatchPureKotlin(patchImg, patchMask)
                val finalPatch = blendPatch(
                    cleanedPatch = cleanedPatch,
                    originalPatch = patchImg,
                    maskPatch = patchMask,
                    options = options
                )
                val gPatch = outputImage.createGraphics()
                gPatch.drawImage(finalPatch, rx, ry, null)
                gPatch.dispose()
            }
        }

        return outputImage
    }

    /**
     * Runs ONNX inference on an individual image patch based on the architecture specification.
     */
    fun inpaintPatchWithOnnx(
        session: OnnxInferenceSession,
        spec: ModelSpec,
        patchImg: BufferedImage,
        patchMask: BufferedImage,
        options: InpaintingOptions = InpaintingOptions(),
        multiSessions: Map<String, OnnxInferenceSession> = emptyMap()
    ): BufferedImage {
        val modelType = spec.modelTypeRaw.lowercase()
        val isMigan = modelType == "migan" || modelType == "migan_traced" || spec.concatOrder.equals("image_mask", ignoreCase = true)
        val isZitspp = modelType == "zitspp" || modelType == "zits++" || modelType == "zits_plusplus"
        val isZits = modelType == "zits" || modelType == "zits-inpaint-0717"

        return when {
            isMigan -> {
                val tensor = ImageTensorUtils.createMigan4ChannelTensor(session.environment, patchImg, patchMask, 512, 512)
                var results: OrtSession.Result? = null
                try {
                    val inputName = session.session.inputNames.iterator().next()
                    results = session.run(mapOf(inputName to tensor))
                    val outTensor = results.get(0) as OnnxTensor
                    val rawCleaned = ImageTensorUtils.miganTensorToBufferedImage(outTensor)
                    ImageTensorUtils.resizeImage(rawCleaned, patchImg.width, patchImg.height)
                } finally {
                    results?.close()
                    tensor.close()
                }
            }

            isZitspp -> {
                val padW = ImageTensorUtils.snapToMultiple(patchImg.width, 16).coerceAtLeast(256)
                val padH = ImageTensorUtils.snapToMultiple(patchImg.height, 16).coerceAtLeast(256)
                val paddedImg = ImageTensorUtils.resizeImage(patchImg, padW, padH)
                val paddedMask = ImageTensorUtils.resizeImage(patchMask, padW, padH)

                val (relPos, direct) = ZitsInpaintingPipeline.computeMpe(paddedMask, 256, 128)

                val tsrSess = multiSessions["tsr"]
                val (edge256, line256) = if (tsrSess != null) {
                    val img256 = ImageTensorUtils.resizeImage(paddedImg, 256, 256)
                    val mask256 = ImageTensorUtils.resizeImage(paddedMask, 256, 256)
                    ZitsInpaintingPipeline.runTsr(tsrSess, img256, mask256, suppressLines = options.objRemoval)
                } else {
                    Pair(FloatArray(256 * 256), FloatArray(256 * 256))
                }

                val filteredEdges = ZitsInpaintingPipeline.applyEdgeNms(edge256, options.binaryThreshold)

                val ssuSess = multiSessions["structure_upsample"]
                val edgeFull = if (ssuSess != null) {
                    ZitsInpaintingPipeline.runSsuUpsample(ssuSess, filteredEdges, 256, 256, padW, padH)
                } else {
                    FloatArray(padW * padH)
                }
                val lineFull = if (ssuSess != null) {
                    ZitsInpaintingPipeline.runSsuUpsample(ssuSess, line256, 256, 256, padW, padH)
                } else {
                    FloatArray(padW * padH)
                }

                val genSess = multiSessions["generator"] ?: session
                val rawOut = ZitsInpaintingPipeline.runGenerator(
                    genSess,
                    paddedImg,
                    paddedMask,
                    edgeFull,
                    lineFull,
                    relPos,
                    direct,
                    isZitspp = true
                )
                ImageTensorUtils.resizeImage(rawOut, patchImg.width, patchImg.height)
            }

            isZits -> {
                val padW = ImageTensorUtils.snapToMultiple(patchImg.width, 16).coerceAtLeast(256)
                val padH = ImageTensorUtils.snapToMultiple(patchImg.height, 16).coerceAtLeast(256)
                val paddedImg = ImageTensorUtils.resizeImage(patchImg, padW, padH)
                val paddedMask = ImageTensorUtils.resizeImage(patchMask, padW, padH)

                val (relPos, direct) = ZitsInpaintingPipeline.computeMpe(paddedMask, 256, 128)
                val edgesFull = ZitsInpaintingPipeline.extractCannyEdges(paddedImg, options.sigma256)
                val linesFull = FloatArray(padW * padH)

                val genSess = multiSessions["generator"] ?: session
                val rawOut = ZitsInpaintingPipeline.runGenerator(
                    genSess,
                    paddedImg,
                    paddedMask,
                    edgesFull,
                    linesFull,
                    relPos,
                    direct,
                    isZitspp = false
                )
                ImageTensorUtils.resizeImage(rawOut, patchImg.width, patchImg.height)
            }

            else -> {
                // Standard LaMa / Manga single-pass FFC
                val targetW = if (spec.effectiveWidth > 0) spec.effectiveWidth else 512
                val targetH = if (spec.effectiveHeight > 0) spec.effectiveHeight else 512
                val padMod = if (options.padMod in listOf(8, 16, 32)) options.padMod else 16
                val inW = if (spec.dynamicShape) ImageTensorUtils.snapToMultiple(patchImg.width, padMod).coerceAtLeast(64) else targetW
                val inH = if (spec.dynamicShape) ImageTensorUtils.snapToMultiple(patchImg.height, padMod).coerceAtLeast(64) else targetH

                val imgInputName = if (spec.inputNames.isNotEmpty()) spec.inputNames[0] else "image"
                val maskInputName = if (spec.inputNames.size > 1) spec.inputNames[1] else "mask"

                val imgTensor = ImageTensorUtils.createInpaintingImageTensor(session.environment, patchImg, inW, inH, spec.normMode)
                val maskTensor = ImageTensorUtils.createInpaintingMaskTensor(session.environment, patchMask, inW, inH, spec.maskMode)

                var results: OrtSession.Result? = null
                try {
                    results = session.session.run(mapOf(imgInputName to imgTensor, maskInputName to maskTensor))
                    val outputTensor = results.get(0) as? OnnxTensor
                        ?: results.firstOrNull { it.value is OnnxTensor }?.value as? OnnxTensor

                    if (outputTensor != null) {
                        val rawCleaned = ImageTensorUtils.tensorToBufferedImage(outputTensor, spec.normMode)
                        ImageTensorUtils.resizeImage(rawCleaned, patchImg.width, patchImg.height)
                    } else {
                        inpaintPatchPureKotlin(patchImg, patchMask)
                    }
                } finally {
                    results?.close()
                    imgTensor.close()
                    maskTensor.close()
                }
            }
        }
    }

    /**
     * Unified router dispatching to the configured [BlendingMode] algorithm.
     */
    fun blendPatch(
        cleanedPatch: BufferedImage,
        originalPatch: BufferedImage,
        maskPatch: BufferedImage,
        options: InpaintingOptions
    ): BufferedImage {
        return when (options.blendingMode) {
            BlendingMode.NONE -> applyDirectPaste(cleanedPatch, originalPatch, maskPatch)
            BlendingMode.FEATHER -> applyAlphaFeather(
                cleanedImg = cleanedPatch,
                origImg = originalPatch,
                mask = maskPatch,
                featherRadiusPx = if (options.featherRadius > 0) options.featherRadius else 2
            )
            BlendingMode.POISSON -> applyPoissonBlending(
                cleanedImg = cleanedPatch,
                origImg = originalPatch,
                mask = maskPatch
            )
            BlendingMode.MODIFIED_POISSON -> applyModifiedPoissonBlending(
                cleanedImg = cleanedPatch,
                origImg = originalPatch,
                mask = maskPatch,
                decayRadius = if (options.featherRadius > 0) options.featherRadius * 4 else 16
            )
            BlendingMode.LAPLACIAN_PYRAMID -> applyLaplacianPyramidBlending(
                cleanedImg = cleanedPatch,
                origImg = originalPatch,
                mask = maskPatch
            )
        }
    }

    /**
     * Direct hard paste of inpainted pixels inside the mask (> 128) onto the original background.
     */
    fun applyDirectPaste(
        cleanedImg: BufferedImage,
        origImg: BufferedImage,
        mask: BufferedImage
    ): BufferedImage {
        val w = cleanedImg.width
        val h = cleanedImg.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(origImg, 0, 0, null)
        g.dispose()

        val maskRaster = mask.raster
        val maskPixels = IntArray(w * h)
        maskRaster.getSamples(0, 0, w, h, 0, maskPixels)

        val cPixels = IntArray(w * h)
        cleanedImg.getRGB(0, 0, w, h, cPixels, 0, w)

        val outPixels = IntArray(w * h)
        out.getRGB(0, 0, w, h, outPixels, 0, w)

        for (i in 0 until w * h) {
            if (maskPixels[i] > 128) {
                outPixels[i] = cPixels[i]
            }
        }
        out.setRGB(0, 0, w, h, outPixels, 0, w)
        return out
    }

    /**
     * Soft alpha feathering blending along the mask contour boundaries.
     */
    fun applyAlphaFeather(
        cleanedImg: BufferedImage,
        origImg: BufferedImage,
        mask: BufferedImage,
        featherRadiusPx: Int = 2
    ): BufferedImage {
        if (featherRadiusPx <= 0) return applyDirectPaste(cleanedImg, origImg, mask)
        val w = cleanedImg.width
        val h = cleanedImg.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(origImg, 0, 0, null)
        g.dispose()

        val maskRaster = mask.raster
        val maskPixels = IntArray(w * h)
        maskRaster.getSamples(0, 0, w, h, 0, maskPixels)

        val cPixels = IntArray(w * h)
        cleanedImg.getRGB(0, 0, w, h, cPixels, 0, w)
        val oPixels = IntArray(w * h)
        origImg.getRGB(0, 0, w, h, oPixels, 0, w)
        val outPixels = IntArray(w * h)
        out.getRGB(0, 0, w, h, outPixels, 0, w)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val m = maskPixels[idx]
                if (m > 127) {
                    var minDist = Double.MAX_VALUE
                    val r = featherRadiusPx
                    for (dy in -r..r) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -r..r) {
                            val nx = x + dx
                            if (nx !in 0 until w) continue
                            if (maskPixels[ny * w + nx] <= 127) {
                                val d = sqrt((dx * dx + dy * dy).toDouble())
                                if (d < minDist) minDist = d
                            }
                        }
                    }
                    if (minDist <= featherRadiusPx) {
                        val alpha = (minDist / featherRadiusPx.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
                        val cRgb = cPixels[idx]
                        val oRgb = oPixels[idx]
                        val cr = (cRgb shr 16) and 0xFF
                        val cg = (cRgb shr 8) and 0xFF
                        val cb = cRgb and 0xFF
                        val or = (oRgb shr 16) and 0xFF
                        val og = (oRgb shr 8) and 0xFF
                        val ob = oRgb and 0xFF
                        val blendR = ((1.0f - alpha) * or + alpha * cr).toInt().coerceIn(0, 255)
                        val blendG = ((1.0f - alpha) * og + alpha * cg).toInt().coerceIn(0, 255)
                        val blendB = ((1.0f - alpha) * ob + alpha * cb).toInt().coerceIn(0, 255)
                        outPixels[idx] = (blendR shl 16) or (blendG shl 8) or blendB
                    } else {
                        outPixels[idx] = cPixels[idx]
                    }
                }
            }
        }
        out.setRGB(0, 0, w, h, outPixels, 0, w)
        return out
    }

    /**
     * Solves Poisson equation (\Delta d = 0) on the masked hole with Dirichlet boundary conditions
     * derived from the surrounding target image pixels.
     * Uses Gauss-Seidel Successive Over-Relaxation (SOR) with boundary residual initialization.
     */
    fun applyPoissonBlending(
        cleanedImg: BufferedImage,
        origImg: BufferedImage,
        mask: BufferedImage,
        iterations: Int = 40,
        omega: Float = 1.7f
    ): BufferedImage {
        val w = cleanedImg.width
        val h = cleanedImg.height
        val total = w * h

        val maskRaster = mask.raster
        val maskPixels = IntArray(total)
        maskRaster.getSamples(0, 0, w, h, 0, maskPixels)

        val isHole = BooleanArray(total) { maskPixels[it] > 128 }
        var holeCount = 0
        for (i in 0 until total) {
            if (isHole[i]) holeCount++
        }
        if (holeCount == 0) return origImg
        if (holeCount == total) return cleanedImg

        val cPixels = IntArray(total)
        cleanedImg.getRGB(0, 0, w, h, cPixels, 0, w)

        val oPixels = IntArray(total)
        origImg.getRGB(0, 0, w, h, oPixels, 0, w)

        val dR = FloatArray(total)
        val dG = FloatArray(total)
        val dB = FloatArray(total)

        var boundarySumR = 0.0
        var boundarySumG = 0.0
        var boundarySumB = 0.0
        var boundaryCount = 0

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                if (!isHole[idx]) {
                    val cr = (cPixels[idx] shr 16) and 0xFF
                    val cg = (cPixels[idx] shr 8) and 0xFF
                    val cb = cPixels[idx] and 0xFF
                    val or = (oPixels[idx] shr 16) and 0xFF
                    val og = (oPixels[idx] shr 8) and 0xFF
                    val ob = oPixels[idx] and 0xFF

                    val diffR = (or - cr).toFloat()
                    val diffG = (og - cg).toFloat()
                    val diffB = (ob - cb).toFloat()

                    dR[idx] = diffR
                    dG[idx] = diffG
                    dB[idx] = diffB

                    val isBoundary = (x > 0 && isHole[idx - 1]) ||
                            (x < w - 1 && isHole[idx + 1]) ||
                            (y > 0 && isHole[idx - w]) ||
                            (y < h - 1 && isHole[idx + w])

                    if (isBoundary) {
                        boundarySumR += diffR
                        boundarySumG += diffG
                        boundarySumB += diffB
                        boundaryCount++
                    }
                }
            }
        }

        val initR = if (boundaryCount > 0) (boundarySumR / boundaryCount).toFloat() else 0f
        val initG = if (boundaryCount > 0) (boundarySumG / boundaryCount).toFloat() else 0f
        val initB = if (boundaryCount > 0) (boundarySumB / boundaryCount).toFloat() else 0f

        for (i in 0 until total) {
            if (isHole[i]) {
                dR[i] = initR
                dG[i] = initG
                dB[i] = initB
            }
        }

        val actualIters = iterations.coerceIn(10, 100)
        for (iter in 0 until actualIters) {
            for (y in 0 until h) {
                val yOff = y * w
                for (x in 0 until w) {
                    val idx = yOff + x
                    if (isHole[idx]) {
                        val leftIdx = if (x > 0) idx - 1 else idx
                        val rightIdx = if (x < w - 1) idx + 1 else idx
                        val upIdx = if (y > 0) idx - w else idx
                        val downIdx = if (y < h - 1) idx + w else idx

                        val targetR = 0.25f * (dR[leftIdx] + dR[rightIdx] + dR[upIdx] + dR[downIdx])
                        val targetG = 0.25f * (dG[leftIdx] + dG[rightIdx] + dG[upIdx] + dG[downIdx])
                        val targetB = 0.25f * (dB[leftIdx] + dB[rightIdx] + dB[upIdx] + dB[downIdx])

                        dR[idx] += omega * (targetR - dR[idx])
                        dG[idx] += omega * (targetG - dG[idx])
                        dB[idx] += omega * (targetB - dB[idx])
                    }
                }
            }
        }

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val outPixels = IntArray(total)

        for (i in 0 until total) {
            if (isHole[i]) {
                val cr = (cPixels[i] shr 16) and 0xFF
                val cg = (cPixels[i] shr 8) and 0xFF
                val cb = cPixels[i] and 0xFF

                val fr = (cr + dR[i]).roundToInt().coerceIn(0, 255)
                val fg = (cg + dG[i]).roundToInt().coerceIn(0, 255)
                val fb = (cb + dB[i]).roundToInt().coerceIn(0, 255)

                outPixels[i] = (fr shl 16) or (fg shl 8) or fb
            } else {
                outPixels[i] = oPixels[i]
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w)
        return out
    }

    /**
     * Modified Poisson solver with soft alpha matting / distance-based boundary attenuation.
     * Restricts Poisson offset adaptation to the boundary zone, preventing color bleed into the interior.
     */
    fun applyModifiedPoissonBlending(
        cleanedImg: BufferedImage,
        origImg: BufferedImage,
        mask: BufferedImage,
        decayRadius: Int = 16,
        iterations: Int = 40,
        omega: Float = 1.7f
    ): BufferedImage {
        val w = cleanedImg.width
        val h = cleanedImg.height
        val total = w * h

        val maskRaster = mask.raster
        val maskPixels = IntArray(total)
        maskRaster.getSamples(0, 0, w, h, 0, maskPixels)

        val isHole = BooleanArray(total) { maskPixels[it] > 128 }
        var holeCount = 0
        for (i in 0 until total) {
            if (isHole[i]) holeCount++
        }
        if (holeCount == 0) return origImg
        if (holeCount == total) return cleanedImg

        val cPixels = IntArray(total)
        cleanedImg.getRGB(0, 0, w, h, cPixels, 0, w)

        val oPixels = IntArray(total)
        origImg.getRGB(0, 0, w, h, oPixels, 0, w)

        val dR = FloatArray(total)
        val dG = FloatArray(total)
        val dB = FloatArray(total)

        var boundarySumR = 0.0
        var boundarySumG = 0.0
        var boundarySumB = 0.0
        var boundaryCount = 0

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                if (!isHole[idx]) {
                    val cr = (cPixels[idx] shr 16) and 0xFF
                    val cg = (cPixels[idx] shr 8) and 0xFF
                    val cb = cPixels[idx] and 0xFF
                    val or = (oPixels[idx] shr 16) and 0xFF
                    val og = (oPixels[idx] shr 8) and 0xFF
                    val ob = oPixels[idx] and 0xFF

                    val diffR = (or - cr).toFloat()
                    val diffG = (og - cg).toFloat()
                    val diffB = (ob - cb).toFloat()

                    dR[idx] = diffR
                    dG[idx] = diffG
                    dB[idx] = diffB

                    val isBoundary = (x > 0 && isHole[idx - 1]) ||
                            (x < w - 1 && isHole[idx + 1]) ||
                            (y > 0 && isHole[idx - w]) ||
                            (y < h - 1 && isHole[idx + w])

                    if (isBoundary) {
                        boundarySumR += diffR
                        boundarySumG += diffG
                        boundarySumB += diffB
                        boundaryCount++
                    }
                }
            }
        }

        val initR = if (boundaryCount > 0) (boundarySumR / boundaryCount).toFloat() else 0f
        val initG = if (boundaryCount > 0) (boundarySumG / boundaryCount).toFloat() else 0f
        val initB = if (boundaryCount > 0) (boundarySumB / boundaryCount).toFloat() else 0f

        for (i in 0 until total) {
            if (isHole[i]) {
                dR[i] = initR
                dG[i] = initG
                dB[i] = initB
            }
        }

        val actualIters = iterations.coerceIn(10, 100)
        for (iter in 0 until actualIters) {
            for (y in 0 until h) {
                val yOff = y * w
                for (x in 0 until w) {
                    val idx = yOff + x
                    if (isHole[idx]) {
                        val leftIdx = if (x > 0) idx - 1 else idx
                        val rightIdx = if (x < w - 1) idx + 1 else idx
                        val upIdx = if (y > 0) idx - w else idx
                        val downIdx = if (y < h - 1) idx + w else idx

                        val targetR = 0.25f * (dR[leftIdx] + dR[rightIdx] + dR[upIdx] + dR[downIdx])
                        val targetG = 0.25f * (dG[leftIdx] + dG[rightIdx] + dG[upIdx] + dG[downIdx])
                        val targetB = 0.25f * (dB[leftIdx] + dB[rightIdx] + dB[upIdx] + dB[downIdx])

                        dR[idx] += omega * (targetR - dR[idx])
                        dG[idx] += omega * (targetG - dG[idx])
                        dB[idx] += omega * (targetB - dB[idx])
                    }
                }
            }
        }

        val radius = max(2, decayRadius)
        val dist = FloatArray(total) { if (isHole[it]) Float.MAX_VALUE else 0f }

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                if (isHole[idx]) {
                    var d = dist[idx]
                    if (x > 0) d = min(d, dist[idx - 1] + 1f)
                    if (y > 0) d = min(d, dist[idx - w] + 1f)
                    if (x > 0 && y > 0) d = min(d, dist[idx - w - 1] + 1.414f)
                    if (x < w - 1 && y > 0) d = min(d, dist[idx - w + 1] + 1.414f)
                    dist[idx] = d
                }
            }
        }
        for (y in h - 1 downTo 0) {
            val yOff = y * w
            for (x in w - 1 downTo 0) {
                val idx = yOff + x
                if (isHole[idx]) {
                    var d = dist[idx]
                    if (x < w - 1) d = min(d, dist[idx + 1] + 1f)
                    if (y < h - 1) d = min(d, dist[idx + w] + 1f)
                    if (x < w - 1 && y < h - 1) d = min(d, dist[idx + w + 1] + 1.414f)
                    if (x > 0 && y < h - 1) d = min(d, dist[idx + w - 1] + 1.414f)
                    dist[idx] = d
                }
            }
        }

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val outPixels = IntArray(total)

        for (i in 0 until total) {
            if (isHole[i]) {
                val d = dist[i]
                val alpha = if (d >= radius) {
                    0.0f
                } else {
                    val t = d / radius.toFloat()
                    (0.5f * (1.0f + cos(Math.PI.toFloat() * t))).coerceIn(0.0f, 1.0f)
                }

                val cr = (cPixels[i] shr 16) and 0xFF
                val cg = (cPixels[i] shr 8) and 0xFF
                val cb = cPixels[i] and 0xFF

                val fr = (cr + alpha * dR[i]).roundToInt().coerceIn(0, 255)
                val fg = (cg + alpha * dG[i]).roundToInt().coerceIn(0, 255)
                val fb = (cb + alpha * dB[i]).roundToInt().coerceIn(0, 255)

                outPixels[i] = (fr shl 16) or (fg shl 8) or fb
            } else {
                outPixels[i] = oPixels[i]
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w)
        return out
    }

    /**
     * Laplacian pyramid multi-band frequency blending (Burt & Adelson 1983).
     * Decomposes patch and target into frequency octaves, blending low spatial frequencies
     * smoothly and high frequencies crisply.
     */
    fun applyLaplacianPyramidBlending(
        cleanedImg: BufferedImage,
        origImg: BufferedImage,
        mask: BufferedImage,
        levels: Int = 4
    ): BufferedImage {
        val w = cleanedImg.width
        val h = cleanedImg.height

        val actualLevels = min(levels, max(1, (ln(min(w, h).toDouble()) / ln(2.0)).toInt() - 2)).coerceIn(1, 6)
        if (actualLevels <= 1 || min(w, h) < 16) {
            return applyAlphaFeather(cleanedImg, origImg, mask, 2)
        }

        val total = w * h
        val cPixels = IntArray(total)
        cleanedImg.getRGB(0, 0, w, h, cPixels, 0, w)

        val oPixels = IntArray(total)
        origImg.getRGB(0, 0, w, h, oPixels, 0, w)

        val maskRaster = mask.raster
        val mPixels = IntArray(total)
        maskRaster.getSamples(0, 0, w, h, 0, mPixels)

        val srcR = FloatArray(total) { ((cPixels[it] shr 16) and 0xFF).toFloat() }
        val srcG = FloatArray(total) { ((cPixels[it] shr 8) and 0xFF).toFloat() }
        val srcB = FloatArray(total) { (cPixels[it] and 0xFF).toFloat() }

        val tgtR = FloatArray(total) { ((oPixels[it] shr 16) and 0xFF).toFloat() }
        val tgtG = FloatArray(total) { ((oPixels[it] shr 8) and 0xFF).toFloat() }
        val tgtB = FloatArray(total) { (oPixels[it] and 0xFF).toFloat() }

        val maskFloat = FloatArray(total) { if (mPixels[it] > 128) 1.0f else 0.0f }

        val outR = blendChannelLaplacian(srcR, tgtR, maskFloat, w, h, actualLevels)
        val outG = blendChannelLaplacian(srcG, tgtG, maskFloat, w, h, actualLevels)
        val outB = blendChannelLaplacian(srcB, tgtB, maskFloat, w, h, actualLevels)

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val outPixels = IntArray(total)
        for (i in 0 until total) {
            val r = outR[i].roundToInt().coerceIn(0, 255)
            val g = outG[i].roundToInt().coerceIn(0, 255)
            val b = outB[i].roundToInt().coerceIn(0, 255)
            outPixels[i] = (r shl 16) or (g shl 8) or b
        }
        out.setRGB(0, 0, w, h, outPixels, 0, w)
        return out
    }

    private val pyrKernel = floatArrayOf(0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f)
    private val upKernel = floatArrayOf(0.125f, 0.5f, 0.75f, 0.5f, 0.125f)

    private fun pyrDown(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val temp = FloatArray(srcW * srcH)
        for (y in 0 until srcH) {
            val yOff = y * srcW
            for (x in 0 until srcW) {
                var sum = 0f
                for (k in -2..2) {
                    val nx = (x + k).coerceIn(0, srcW - 1)
                    sum += src[yOff + nx] * pyrKernel[k + 2]
                }
                temp[yOff + x] = sum
            }
        }
        val dst = FloatArray(dstW * dstH)
        for (dy in 0 until dstH) {
            val sy = dy * 2
            val dyOff = dy * dstW
            for (dx in 0 until dstW) {
                val sx = dx * 2
                var sum = 0f
                for (k in -2..2) {
                    val ny = (sy + k).coerceIn(0, srcH - 1)
                    sum += temp[ny * srcW + sx] * pyrKernel[k + 2]
                }
                dst[dyOff + dx] = sum
            }
        }
        return dst
    }

    private fun pyrUp(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val upsampled = FloatArray(dstW * dstH)
        for (sy in 0 until srcH) {
            val dy = sy * 2
            if (dy >= dstH) continue
            val syOff = sy * srcW
            val dyOff = dy * dstW
            for (sx in 0 until srcW) {
                val dx = sx * 2
                if (dx >= dstW) continue
                upsampled[dyOff + dx] = src[syOff + sx]
            }
        }

        val temp = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val yOff = y * dstW
            for (x in 0 until dstW) {
                var sum = 0f
                for (k in -2..2) {
                    val nx = (x + k).coerceIn(0, dstW - 1)
                    sum += upsampled[yOff + nx] * upKernel[k + 2]
                }
                temp[yOff + x] = sum
            }
        }

        val dst = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val yOff = y * dstW
            for (x in 0 until dstW) {
                var sum = 0f
                for (k in -2..2) {
                    val ny = (y + k).coerceIn(0, dstH - 1)
                    sum += temp[ny * dstW + x] * upKernel[k + 2]
                }
                dst[yOff + x] = sum * 2.0f
            }
        }
        return dst
    }

    private fun blendChannelLaplacian(
        src: FloatArray,
        tgt: FloatArray,
        mask: FloatArray,
        w: Int,
        h: Int,
        levels: Int
    ): FloatArray {
        val gSrc = mutableListOf<FloatArray>()
        val gTgt = mutableListOf<FloatArray>()
        val gMask = mutableListOf<FloatArray>()
        val widths = mutableListOf<Int>()
        val heights = mutableListOf<Int>()

        gSrc.add(src)
        gTgt.add(tgt)
        gMask.add(mask)
        widths.add(w)
        heights.add(h)

        var curW = w
        var curH = h
        for (l in 1 until levels) {
            val nextW = max(1, (curW + 1) / 2)
            val nextH = max(1, (curH + 1) / 2)
            widths.add(nextW)
            heights.add(nextH)

            gSrc.add(pyrDown(gSrc[l - 1], curW, curH, nextW, nextH))
            gTgt.add(pyrDown(gTgt[l - 1], curW, curH, nextW, nextH))
            gMask.add(pyrDown(gMask[l - 1], curW, curH, nextW, nextH))

            curW = nextW
            curH = nextH
        }

        val lSrc = mutableListOf<FloatArray>()
        val lTgt = mutableListOf<FloatArray>()
        for (l in 0 until levels - 1) {
            val upSrc = pyrUp(gSrc[l + 1], widths[l + 1], heights[l + 1], widths[l], heights[l])
            val upTgt = pyrUp(gTgt[l + 1], widths[l + 1], heights[l + 1], widths[l], heights[l])

            val lapSrc = FloatArray(widths[l] * heights[l]) { i -> gSrc[l][i] - upSrc[i] }
            val lapTgt = FloatArray(widths[l] * heights[l]) { i -> gTgt[l][i] - upTgt[i] }
            lSrc.add(lapSrc)
            lTgt.add(lapTgt)
        }
        lSrc.add(gSrc[levels - 1])
        lTgt.add(gTgt[levels - 1])

        val lBlend = mutableListOf<FloatArray>()
        for (l in 0 until levels) {
            val size = widths[l] * heights[l]
            val m = gMask[l]
            val s = lSrc[l]
            val t = lTgt[l]
            val blended = FloatArray(size) { i ->
                val alpha = m[i].coerceIn(0.0f, 1.0f)
                alpha * s[i] + (1.0f - alpha) * t[i]
            }
            lBlend.add(blended)
        }

        var current = lBlend[levels - 1]
        for (l in levels - 2 downTo 0) {
            val up = pyrUp(current, widths[l + 1], heights[l + 1], widths[l], heights[l])
            val size = widths[l] * heights[l]
            val reconstructed = FloatArray(size) { i -> lBlend[l][i] + up[i] }
            current = reconstructed
        }

        return current
    }

    /**
     * High-level inpainting pipeline: Performs ROI patch-based inpainting on a [BufferedImage] using a binary mask.
     * Extracts only the bounding regions containing mask pixels (+ padding context), runs pure Kotlin inpainting,
     * and blends the patches back onto the output image according to [options].
     */
    fun inpaintImage(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        roiPaddingPx: Int = 24,
        options: InpaintingOptions = InpaintingOptions()
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
            val finalPatch = blendPatch(cleanedPatch, patchImg, patchMask, options)

            val gPatch = outputImage.createGraphics()
            gPatch.drawImage(finalPatch, rx, ry, null)
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
        featherRadiusPx: Int = 2,
        options: InpaintingOptions = InpaintingOptions(),
        multiSessions: Map<String, OnnxInferenceSession> = emptyMap()
    ): BufferedImage {
        val width = sourceImage.width
        val height = sourceImage.height

        val fullyCleaned = if (session != null && spec != null) {
            inpaintWithOnnx(
                sourceImage = sourceImage,
                mask = mask,
                session = session,
                spec = spec,
                roiPaddingPx = roiPaddingPx,
                options = options,
                multiSessions = multiSessions
            )
        } else {
            inpaintImage(
                sourceImage = sourceImage,
                mask = mask,
                roiPaddingPx = roiPaddingPx,
                options = options
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

    /**
     * Diagnostic result of background homogeneity analysis around a masked text region.
     */
    data class RegionHomogeneity(
        val isHomogeneous: Boolean,
        val isGradient: Boolean,
        val meanR: Int,
        val meanG: Int,
        val meanB: Int,
        val stdDev: Double,
        val aR: Double = 0.0,
        val bR: Double = 0.0,
        val cR: Double = 0.0,
        val aG: Double = 0.0,
        val bG: Double = 0.0,
        val cG: Double = 0.0,
        val aB: Double = 0.0,
        val bB: Double = 0.0,
        val cB: Double = 0.0
    )

    /**
     * Analyzes boundary pixels surrounding a masked region to determine if the background is a solid flat color
     * or a smooth linear gradient balloon, allowing zero-latency deterministic reconstruction.
     */
    fun analyzeRegionHomogeneity(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        region: SliceWindow,
        borderThickness: Int = 4
    ): RegionHomogeneity {
        val rx = region.x
        val ry = region.y
        val rw = region.width
        val rh = region.height

        val srcW = sourceImage.width
        val srcH = sourceImage.height

        val imgPixels = IntArray(rw * rh)
        sourceImage.getRGB(rx, ry, rw, rh, imgPixels, 0, rw)

        val maskRaster = mask.raster
        val maskPixels = IntArray(rw * rh)
        maskRaster.getSamples(rx, ry, rw, rh, 0, maskPixels)

        val isMasked = BooleanArray(rw * rh) { i -> maskPixels[i] > 128 }

        // Collect boundary pixels (unmasked pixels within borderThickness of any masked pixel)
        var count = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0

        val borderIndices = IntArray(rw * rh)
        for (y in 0 until rh) {
            val yOff = y * rw
            for (x in 0 until rw) {
                val idx = yOff + x
                if (!isMasked[idx]) {
                    // Check if close to mask
                    var nearMask = false
                    val yMin = max(0, y - borderThickness)
                    val yMax = min(rh - 1, y + borderThickness)
                    val xMin = max(0, x - borderThickness)
                    val xMax = min(rw - 1, x + borderThickness)

                    for (ny in yMin..yMax) {
                        val nyOff = ny * rw
                        for (nx in xMin..xMax) {
                            if (isMasked[nyOff + nx]) {
                                nearMask = true
                                break
                            }
                        }
                        if (nearMask) break
                    }

                    if (nearMask) {
                        borderIndices[count++] = idx
                        val rgb = imgPixels[idx]
                        sumR += ((rgb shr 16) and 0xFF)
                        sumG += ((rgb shr 8) and 0xFF)
                        sumB += (rgb and 0xFF)
                    }
                }
            }
        }

        if (count < 8) {
            return RegionHomogeneity(
                isHomogeneous = false,
                isGradient = false,
                meanR = 255,
                meanG = 255,
                meanB = 255,
                stdDev = 999.0
            )
        }

        val meanR = (sumR / count).toInt().coerceIn(0, 255)
        val meanG = (sumG / count).toInt().coerceIn(0, 255)
        val meanB = (sumB / count).toInt().coerceIn(0, 255)

        var varSum = 0.0
        for (i in 0 until count) {
            val idx = borderIndices[i]
            val rgb = imgPixels[idx]
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF

            val dR = r - meanR
            val dG = g - meanG
            val dB = b - meanB
            varSum += (dR * dR + dG * dG + dB * dB) / 3.0
        }

        val stdDev = sqrt(varSum / count)

        // If variance is extremely low, it is a pure flat solid color (e.g. #FFFFFF balloon)
        if (stdDev <= 4.0) {
            return RegionHomogeneity(
                isHomogeneous = true,
                isGradient = false,
                meanR = meanR,
                meanG = meanG,
                meanB = meanB,
                stdDev = stdDev
            )
        }

        // Fit 2D linear gradient plane R(u, v) = a*u + b*v + c
        var sumU = 0.0
        var sumV = 0.0
        var sumUU = 0.0
        var sumVV = 0.0
        var sumUV = 0.0
        var sumUR = 0.0
        var sumVR = 0.0
        var sumUG = 0.0
        var sumVG = 0.0
        var sumUB = 0.0
        var sumVB = 0.0

        for (i in 0 until count) {
            val idx = borderIndices[i]
            val u = (idx % rw).toDouble()
            val v = (idx / rw).toDouble()
            val rgb = imgPixels[idx]
            val r = ((rgb shr 16) and 0xFF).toDouble()
            val g = ((rgb shr 8) and 0xFF).toDouble()
            val b = (rgb and 0xFF).toDouble()

            sumU += u
            sumV += v
            sumUU += u * u
            sumVV += v * v
            sumUV += u * v
            sumUR += u * r
            sumVR += v * r
            sumUG += u * g
            sumVG += v * g
            sumUB += u * b
            sumVB += v * b
        }

        // 3x3 normal equation solve for [a, b, c]
        val n = count.toDouble()
        val det = sumUU * (sumVV * n - sumV * sumV) - sumUV * (sumUV * n - sumV * sumU) + sumU * (sumUV * sumV - sumVV * sumU)

        if (abs(det) > 1e-4) {
            fun solvePlane(sumUc: Double, sumVc: Double, sumC: Double): Triple<Double, Double, Double> {
                val detA = sumUc * (sumVV * n - sumV * sumV) - sumUV * (sumVc * n - sumV * sumC) + sumU * (sumVc * sumV - sumVV * sumC)
                val detB = sumUU * (sumVc * n - sumV * sumC) - sumUc * (sumUV * n - sumV * sumU) + sumU * (sumUV * sumC - sumVc * sumU)
                val detC = sumUU * (sumVV * sumC - sumVc * sumV) - sumUV * (sumUV * sumC - sumVc * sumU) + sumUc * (sumUV * sumV - sumVV * sumU)
                return Triple(detA / det, detB / det, detC / det)
            }

            val (aR, bR, cR) = solvePlane(sumUR, sumVR, sumR)
            val (aG, bG, cG) = solvePlane(sumUG, sumVG, sumG)
            val (aB, bB, cB) = solvePlane(sumUB, sumVB, sumB)

            var residualSum = 0.0
            for (i in 0 until count) {
                val idx = borderIndices[i]
                val u = (idx % rw).toDouble()
                val v = (idx / rw).toDouble()
                val rgb = imgPixels[idx]
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                val predR = aR * u + bR * v + cR
                val predG = aG * u + bG * v + cG
                val predB = aB * u + bB * v + cB

                val dR = r - predR
                val dG = g - predG
                val dB = b - predB
                residualSum += (dR * dR + dG * dG + dB * dB) / 3.0
            }

            val residualStdDev = sqrt(residualSum / count)
            val slopeMag = sqrt(aR * aR + bR * bR + aG * aG + bG * bG + aB * aB + bB * bB)

            if (residualStdDev <= 5.5 && slopeMag > 0.02) {
                return RegionHomogeneity(
                    isHomogeneous = false,
                    isGradient = true,
                    meanR = meanR,
                    meanG = meanG,
                    meanB = meanB,
                    stdDev = stdDev,
                    aR = aR, bR = bR, cR = cR,
                    aG = aG, bG = bG, cG = cG,
                    aB = aB, bB = bB, cB = cB
                )
            }
        }

        return RegionHomogeneity(
            isHomogeneous = false,
            isGradient = false,
            meanR = meanR,
            meanG = meanG,
            meanB = meanB,
            stdDev = stdDev
        )
    }

    /**
     * Instantly fills the masked pixels in [region] with solid color (meanR, meanG, meanB).
     */
    fun fillSolidHomogeneousRegion(
        targetImage: BufferedImage,
        mask: BufferedImage,
        region: SliceWindow,
        meanR: Int,
        meanG: Int,
        meanB: Int
    ) {
        val rx = region.x
        val ry = region.y
        val rw = region.width
        val rh = region.height

        val maskRaster = mask.raster
        val maskPixels = IntArray(rw * rh)
        maskRaster.getSamples(rx, ry, rw, rh, 0, maskPixels)

        val targetPixels = IntArray(rw * rh)
        targetImage.getRGB(rx, ry, rw, rh, targetPixels, 0, rw)

        val solidRgb = (meanR shl 16) or (meanG shl 8) or meanB
        for (i in 0 until rw * rh) {
            if (maskPixels[i] > 128) {
                targetPixels[i] = solidRgb
            }
        }
        targetImage.setRGB(rx, ry, rw, rh, targetPixels, 0, rw)
    }

    /**
     * Instantly fills the masked pixels in [region] with a 2D fitted biharmonic linear gradient.
     */
    fun fillBiharmonicGradientRegion(
        targetImage: BufferedImage,
        mask: BufferedImage,
        region: SliceWindow,
        h: RegionHomogeneity
    ) {
        val rx = region.x
        val ry = region.y
        val rw = region.width
        val rh = region.height

        val maskRaster = mask.raster
        val maskPixels = IntArray(rw * rh)
        maskRaster.getSamples(rx, ry, rw, rh, 0, maskPixels)

        val targetPixels = IntArray(rw * rh)
        targetImage.getRGB(rx, ry, rw, rh, targetPixels, 0, rw)

        for (y in 0 until rh) {
            val yOff = y * rw
            val v = y.toDouble()
            for (x in 0 until rw) {
                val idx = yOff + x
                if (maskPixels[idx] > 128) {
                    val u = x.toDouble()
                    val r = (h.aR * u + h.bR * v + h.cR).toInt().coerceIn(0, 255)
                    val g = (h.aG * u + h.bG * v + h.cG).toInt().coerceIn(0, 255)
                    val b = (h.aB * u + h.bB * v + h.cB).toInt().coerceIn(0, 255)
                    targetPixels[idx] = (r shl 16) or (g shl 8) or b
                }
            }
        }
        targetImage.setRGB(rx, ry, rw, rh, targetPixels, 0, rw)
    }

    /**
     * Executes the Production Hybrid Cleaning Pipeline:
     * 1. Analyzes background homogeneity around each mask cluster.
     * 2. Cleans solid/gradient balloons in <1ms via deterministic mathematical fill (0 noise, perfect edges).
     * 3. Routes complex textured/artwork regions to neural inpainting with adaptive context window expansion
     *    and multiple-of-32 dimension snapping.
     * 4. Seamlessly blends only the masked pixel areas back onto the canvas.
     */
    fun inpaintProductionHybrid(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        session: OnnxInferenceSession? = null,
        spec: ModelSpec? = null,
        adaptivePadding: Boolean = true,
        deterministicFill: Boolean = true,
        minContextSize: Int = 256,
        roiPaddingPx: Int = 16,
        options: InpaintingOptions = InpaintingOptions(),
        multiSessions: Map<String, OnnxInferenceSession> = emptyMap()
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

        val isDiffusion = spec?.pipelineType?.equals("diffusion_pipeline", ignoreCase = true) == true ||
            spec?.inputNames?.contains("timestep_embed") == true ||
            spec?.effectiveType?.equals("ldm", ignoreCase = true) == true ||
            spec?.effectiveType?.equals("diffusion", ignoreCase = true) == true

        for (region in maskRegions) {
            if (deterministicFill) {
                val homogeneity = analyzeRegionHomogeneity(sourceImage, mask, region)
                if (homogeneity.isHomogeneous) {
                    fillSolidHomogeneousRegion(outputImage, mask, region, homogeneity.meanR, homogeneity.meanG, homogeneity.meanB)
                    continue
                } else if (homogeneity.isGradient) {
                    fillBiharmonicGradientRegion(outputImage, mask, region, homogeneity)
                    continue
                }
            }

            // Complex redraw / Textured background: Neural Inpainting with Adaptive Context Expansion
            val targetContextW = if (adaptivePadding) maxOf(minContextSize, (region.width * 2.5).toInt()) else region.width
            val targetContextH = if (adaptivePadding) maxOf(minContextSize, (region.height * 2.5).toInt()) else region.height

            val snappedW = ImageTensorUtils.snapToMultiple(minOf(width, targetContextW), 32)
            val snappedH = ImageTensorUtils.snapToMultiple(minOf(height, targetContextH), 32)

            val centerX = region.x + region.width / 2
            val centerY = region.y + region.height / 2

            val cropX = (centerX - snappedW / 2).coerceIn(0, max(0, width - snappedW))
            val cropY = (centerY - snappedH / 2).coerceIn(0, max(0, height - snappedH))
            val cropW = minOf(snappedW, width - cropX)
            val cropH = minOf(snappedH, height - cropY)

            val contextImg = outputImage.getSubimage(cropX, cropY, cropW, cropH)
            val contextMask = mask.getSubimage(cropX, cropY, cropW, cropH)

            val cleanedPatch = if (session != null && spec != null) {
                try {
                    if (isDiffusion) {
                        inpaintDiffusionPatch(session, contextImg, contextMask, spec)
                    } else {
                        inpaintPatchWithOnnx(
                            session = session,
                            spec = spec,
                            patchImg = contextImg,
                            patchMask = contextMask,
                            options = options,
                            multiSessions = multiSessions
                        )
                    }
                } catch (e: Exception) {
                    inpaintPatchPureKotlin(contextImg, contextMask)
                }
            } else {
                inpaintPatchPureKotlin(contextImg, contextMask)
            }

            val finalPatch = blendPatch(
                cleanedPatch = cleanedPatch,
                originalPatch = contextImg,
                maskPatch = contextMask,
                options = options
            )

            val gPatch = outputImage.createGraphics()
            gPatch.drawImage(finalPatch, cropX, cropY, null)
            gPatch.dispose()
        }

        return outputImage
    }

    /**
     * Executes the Production Hybrid Cleaning Pipeline and returns an alpha-transparent canvas (TYPE_INT_ARGB)
     * containing only the reconstructed patch layers.
     */
    fun inpaintProductionHybridIsolated(
        sourceImage: BufferedImage,
        mask: BufferedImage,
        session: OnnxInferenceSession? = null,
        spec: ModelSpec? = null,
        adaptivePadding: Boolean = true,
        deterministicFill: Boolean = true,
        minContextSize: Int = 256,
        roiPaddingPx: Int = 16,
        featherRadiusPx: Int = 2,
        options: InpaintingOptions = InpaintingOptions(),
        multiSessions: Map<String, OnnxInferenceSession> = emptyMap()
    ): BufferedImage {
        val fullyCleaned = inpaintProductionHybrid(
            sourceImage = sourceImage,
            mask = mask,
            session = session,
            spec = spec,
            adaptivePadding = adaptivePadding,
            deterministicFill = deterministicFill,
            minContextSize = minContextSize,
            roiPaddingPx = roiPaddingPx,
            options = options,
            multiSessions = multiSessions
        )

        val width = sourceImage.width
        val height = sourceImage.height
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
}
