package com.wip.common.models

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance, dependency-free Kotlin implementation of the multi-stage structural
 * inpainting pipeline for ZITS (CVPR 2022) and ZITS++ (TPAMI 2023).
 */
object ZitsInpaintingPipeline {

    /**
     * Computes Masked Positional Encoding (MPE) distance bins and directional indicator maps.
     *
     * @param maskBinary Binary hole mask (255 = hole, 0 = background).
     * @param strSize Fixed downsampled resolution for wavefront dilation (default: 256).
     * @param posNum Number of discrete distance bins (default: 128).
     * @return Pair of:
     *   - relPos: LongArray of shape (1, H, W) with values in 0 until posNum.
     *   - direct: LongArray of shape (1, H, W, 4) with binary directional flags.
     */
    fun computeMpe(
        maskBinary: BufferedImage,
        strSize: Int = 256,
        posNum: Int = 128
    ): Pair<LongArray, LongArray> {
        val origW = maskBinary.width
        val origH = maskBinary.height

        // 1. Resize mask to standard strSize x strSize
        val mask256 = IntArray(strSize * strSize)
        val origRaster = maskBinary.raster
        val origPixels = IntArray(origW * origH)
        origRaster.getSamples(0, 0, origW, origH, 0, origPixels)

        val xRatio = origW.toFloat() / strSize.toFloat()
        val yRatio = origH.toFloat() / strSize.toFloat()

        for (y in 0 until strSize) {
            val srcY = (y * yRatio).toInt().coerceIn(0, origH - 1)
            for (x in 0 until strSize) {
                val srcX = (x * xRatio).toInt().coerceIn(0, origW - 1)
                mask256[y * strSize + x] = if (origPixels[srcY * origW + srcX] > 127) 1 else 0
            }
        }

        // 2. valid array: 1.0 at background, 0.0 at hole
        var valid = FloatArray(strSize * strSize) { idx ->
            if (mask256[idx] > 0) 0.0f else 1.0f
        }

        val pos = IntArray(strSize * strSize)
        val direct = IntArray(strSize * strSize * 4)

        var remainingHole = mask256.count { it > 0 }
        var iteration = 0

        // 3. Iterative wavefront dilation loop
        while (remainingHole > 0 && iteration < strSize) {
            iteration++
            val validNext = FloatArray(strSize * strSize)
            var newCoveredCount = 0

            for (y in 0 until strSize) {
                val yStart = max(0, y - 1)
                val yEnd = min(strSize - 1, y + 1)

                for (x in 0 until strSize) {
                    val idx = y * strSize + x
                    if (valid[idx] > 0.5f) {
                        validNext[idx] = 1.0f
                        continue
                    }

                    val xStart = max(0, x - 1)
                    val xEnd = min(strSize - 1, x + 1)

                    var hasValidNeighbor = false
                    for (ny in yStart..yEnd) {
                        for (nx in xStart..xEnd) {
                            if (valid[ny * strSize + nx] > 0.5f) {
                                hasValidNeighbor = true
                                break
                            }
                        }
                        if (hasValidNeighbor) break
                    }

                    if (hasValidNeighbor) {
                        validNext[idx] = 1.0f
                        pos[idx] = iteration
                        newCoveredCount++

                        // Check 4 directional filter quadrants:
                        // d1: top-left (y-1..y, x-1..x)
                        var d1 = false
                        for (ny in max(0, y - 1)..y) {
                            for (nx in max(0, x - 1)..x) {
                                if (valid[ny * strSize + nx] > 0.5f) { d1 = true; break }
                            }
                        }
                        if (d1) direct[idx * 4 + 0] = 1

                        // d2: bottom-left (y..y+1, x-1..x)
                        var d2 = false
                        for (ny in y..min(strSize - 1, y + 1)) {
                            for (nx in max(0, x - 1)..x) {
                                if (valid[ny * strSize + nx] > 0.5f) { d2 = true; break }
                            }
                        }
                        if (d2) direct[idx * 4 + 1] = 1

                        // d3: top-right (y-1..y, x..x+1)
                        var d3 = false
                        for (ny in max(0, y - 1)..y) {
                            for (nx in x..min(strSize - 1, x + 1)) {
                                if (valid[ny * strSize + nx] > 0.5f) { d3 = true; break }
                            }
                        }
                        if (d3) direct[idx * 4 + 2] = 1

                        // d4: bottom-right (y..y+1, x..x+1)
                        var d4 = false
                        for (ny in y..min(strSize - 1, y + 1)) {
                            for (nx in x..min(strSize - 1, x + 1)) {
                                if (valid[ny * strSize + nx] > 0.5f) { d4 = true; break }
                            }
                        }
                        if (d4) direct[idx * 4 + 3] = 1
                    }
                }
            }

            if (newCoveredCount == 0) break
            remainingHole -= newCoveredCount
            valid = validNext
        }

        // 4. Normalize to discrete distance bins: clip(floor(pos / (strSize / 2) * posNum), 0, posNum - 1)
        val factor = posNum.toFloat() / (strSize.toFloat() / 2.0f)
        val relPos256 = IntArray(strSize * strSize)
        for (i in 0 until strSize * strSize) {
            val v = (pos[i].toFloat() * factor).toInt().coerceIn(0, posNum - 1)
            relPos256[i] = v
        }

        // 5. Upsample rel_pos and direct to full resolution (origW x origH)
        val relPosFull = LongArray(origW * origH)
        val directFull = LongArray(origW * origH * 4)

        for (y in 0 until origH) {
            val sY = ((y.toFloat() / origH.toFloat()) * strSize).toInt().coerceIn(0, strSize - 1)
            for (x in 0 until origW) {
                val destIdx = y * origW + x
                if (origPixels[destIdx] <= 127) {
                    // Valid background context: rel_pos = 0, direct = 0
                    relPosFull[destIdx] = 0L
                    directFull[destIdx * 4 + 0] = 0L
                    directFull[destIdx * 4 + 1] = 0L
                    directFull[destIdx * 4 + 2] = 0L
                    directFull[destIdx * 4 + 3] = 0L
                } else {
                    val sX = ((x.toFloat() / origW.toFloat()) * strSize).toInt().coerceIn(0, strSize - 1)
                    val sIdx = sY * strSize + sX
                    relPosFull[destIdx] = relPos256[sIdx].toLong()
                    directFull[destIdx * 4 + 0] = direct[sIdx * 4 + 0].toLong()
                    directFull[destIdx * 4 + 1] = direct[sIdx * 4 + 1].toLong()
                    directFull[destIdx * 4 + 2] = direct[sIdx * 4 + 2].toLong()
                    directFull[destIdx * 4 + 3] = direct[sIdx * 4 + 3].toLong()
                }
            }
        }

        return Pair(relPosFull, directFull)
    }

    /**
     * Structure Upsampler (SSU): Iteratively scales structural maps by 2x using structure_upsample.onnx
     * and applies the mathematical activation: activated = 1 / (1 + exp(-(str_out + 2.0) * 2.0)).
     */
    fun runSsuUpsample(
        ssuSession: OnnxInferenceSession,
        strInput: FloatArray,
        inW: Int,
        inH: Int,
        targetW: Int,
        targetH: Int
    ): FloatArray {
        var currW = inW
        var currH = inH
        var currData = strInput

        while (currW * 2 <= max(targetW, targetH) || currH * 2 <= max(targetW, targetH)) {
            val env = ssuSession.environment
            val inputName = ssuSession.session.inputNames.iterator().next()
            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(currData),
                longArrayOf(1, 1, currH.toLong(), currW.toLong())
            )

            val outH = currH * 2
            val outW = currW * 2
            val activated = FloatArray(outH * outW)

            var res = ssuSession.run(mapOf(inputName to inputTensor))
            try {
                val outTensor = res.get(0) as OnnxTensor
                val buf = outTensor.floatBuffer
                for (i in 0 until outH * outW) {
                    val raw = buf.get(i)
                    activated[i] = (1.0 / (1.0 + exp(-(raw + 2.0) * 2.0))).toFloat()
                }
            } finally {
                res.close()
                inputTensor.close()
            }

            currW = outW
            currH = outH
            currData = activated
        }

        // Bilinear resize to final target dimensions
        if (currW == targetW && currH == targetH) {
            return currData
        }

        val resized = FloatArray(targetW * targetH)
        val xRatio = currW.toFloat() / targetW.toFloat()
        val yRatio = currH.toFloat() / targetH.toFloat()

        for (y in 0 until targetH) {
            val sY = (y * yRatio).toInt().coerceIn(0, currH - 1)
            for (x in 0 until targetW) {
                val sX = (x * xRatio).toInt().coerceIn(0, currW - 1)
                resized[y * targetW + x] = currData[sY * currW + sX]
            }
        }
        return resized
    }

    /**
     * Executes non-autoregressive EdgeLine TSR session for ZITS++.
     * Inputs: image [-1.0, 1.0], line [0.0, 1.0], masks {0.0, 1.0} at 256x256.
     * Outputs: edge_pred (256x256), line_pred (256x256).
     */
    fun runTsr(
        tsrSession: OnnxInferenceSession,
        img256: BufferedImage,
        mask256: BufferedImage,
        suppressLines: Boolean = true
    ): Pair<FloatArray, FloatArray> {
        val env = tsrSession.environment
        val imgBuf = FloatBuffer.allocate(3 * 256 * 256)
        val maskBuf = FloatBuffer.allocate(1 * 256 * 256)
        val lineBuf = FloatBuffer.allocate(1 * 256 * 256)

        val maskRaster = mask256.raster

        // Planar NCHW image normalized to [-1.0, 1.0]
        val rChan = FloatArray(256 * 256)
        val gChan = FloatArray(256 * 256)
        val bChan = FloatArray(256 * 256)

        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val rgb = img256.getRGB(x, y)
                val idx = y * 256 + x
                rChan[idx] = (((rgb shr 16) and 0xFF) / 127.5f) - 1.0f
                gChan[idx] = (((rgb shr 8) and 0xFF) / 127.5f) - 1.0f
                bChan[idx] = ((rgb and 0xFF) / 127.5f) - 1.0f

                val mVal = maskRaster.getSample(x, y, 0)
                maskBuf.put((if (mVal > 127) 1.0f else 0.0f))
                lineBuf.put(0.0f) // Clean wireframe proposal fallback
            }
        }
        imgBuf.put(rChan)
        imgBuf.put(gChan)
        imgBuf.put(bChan)

        imgBuf.flip()
        maskBuf.flip()
        lineBuf.flip()

        val imgTensor = OnnxTensor.createTensor(env, imgBuf, longArrayOf(1, 3, 256, 256))
        val maskTensor = OnnxTensor.createTensor(env, maskBuf, longArrayOf(1, 1, 256, 256))
        val lineTensor = OnnxTensor.createTensor(env, lineBuf, longArrayOf(1, 1, 256, 256))

        val edgePred = FloatArray(256 * 256)
        val linePred = FloatArray(256 * 256)

        val inputs = mapOf(
            "image" to imgTensor,
            "line" to lineTensor,
            "masks" to maskTensor
        )

        var res = tsrSession.run(inputs)
        try {
            val edgeTensor = (res.get("edge_pred").orElse(null) ?: res.get(0)) as? OnnxTensor
            val lineOutTensor = (res.get("line_pred").orElse(null) ?: res.get(1)) as? OnnxTensor

            edgeTensor?.floatBuffer?.get(edgePred)
            lineOutTensor?.floatBuffer?.get(linePred)
        } finally {
            res.close()
            imgTensor.close()
            maskTensor.close()
            lineTensor.close()
        }

        return Pair(edgePred, linePred)
    }

    /**
     * Applies Canny edge detection / gradient contour extraction for ZITS (v1).
     */
    fun extractCannyEdges(
        img: BufferedImage,
        sigma: Double = 3.0
    ): FloatArray {
        val w = img.width
        val h = img.height
        val gray = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                gray[y * w + x] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }
        }

        // Lightweight Sobel magnitude
        val edges = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = (gray[(y - 1) * w + (x + 1)] + 2 * gray[y * w + (x + 1)] + gray[(y + 1) * w + (x + 1)]) -
                        (gray[(y - 1) * w + (x - 1)] + 2 * gray[y * w + (x - 1)] + gray[(y + 1) * w + (x - 1)])
                val gy = (gray[(y + 1) * w + (x - 1)] + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)]) -
                        (gray[(y - 1) * w + (x - 1)] + 2 * gray[(y - 1) * w + x] + gray[(y - 1) * w + (x + 1)])
                val mag = kotlin.math.sqrt(gx * gx + gy * gy)
                edges[y * w + x] = if (mag > (0.15f * (sigma / 3.0f))) 1.0f else 0.0f
            }
        }
        return edges
    }

    /**
     * Applies Edge-NMS thresholding for ZITS++.
     */
    fun applyEdgeNms(
        edgeMap: FloatArray,
        binaryThreshold: Int = 50
    ): FloatArray {
        val th = binaryThreshold.toFloat() / 255.0f
        val result = FloatArray(edgeMap.size)
        for (i in edgeMap.indices) {
            result[i] = if (edgeMap[i] >= th) edgeMap[i] else 0.0f
        }
        return result
    }

    /**
     * Synthesizes texture using generator.onnx (conditioned on image, mask, edge, line, rel_pos, direct).
     */
    fun runGenerator(
        generatorSession: OnnxInferenceSession,
        img: BufferedImage,
        mask: BufferedImage,
        edgeMap: FloatArray,
        lineMap: FloatArray,
        relPos: LongArray,
        direct: LongArray,
        isZitspp: Boolean = true
    ): BufferedImage {
        val env = generatorSession.environment
        val w = img.width
        val h = img.height

        val imgBuf = FloatBuffer.allocate(3 * h * w)
        val maskBuf = FloatBuffer.allocate(1 * h * w)
        val edgeBuf = FloatBuffer.wrap(edgeMap)
        val lineBuf = FloatBuffer.wrap(lineMap)
        val relPosBuf = LongBuffer.wrap(relPos)
        val directBuf = LongBuffer.wrap(direct)

        val maskRaster = mask.raster
        val rChan = FloatArray(h * w)
        val gChan = FloatArray(h * w)
        val bChan = FloatArray(h * w)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = img.getRGB(x, y)
                val idx = y * w + x

                if (isZitspp) {
                    rChan[idx] = (((rgb shr 16) and 0xFF) / 127.5f) - 1.0f
                    gChan[idx] = (((rgb shr 8) and 0xFF) / 127.5f) - 1.0f
                    bChan[idx] = ((rgb and 0xFF) / 127.5f) - 1.0f
                } else {
                    rChan[idx] = ((rgb shr 16) and 0xFF) / 255.0f
                    gChan[idx] = ((rgb shr 8) and 0xFF) / 255.0f
                    bChan[idx] = (rgb and 0xFF) / 255.0f
                }

                val mVal = maskRaster.getSample(x, y, 0)
                maskBuf.put(if (mVal > 127) 1.0f else 0.0f)
            }
        }
        imgBuf.put(rChan)
        imgBuf.put(gChan)
        imgBuf.put(bChan)

        imgBuf.flip()
        maskBuf.flip()

        val imgTensor = OnnxTensor.createTensor(env, imgBuf, longArrayOf(1, 3, h.toLong(), w.toLong()))
        val maskTensor = OnnxTensor.createTensor(env, maskBuf, longArrayOf(1, 1, h.toLong(), w.toLong()))
        val edgeTensor = OnnxTensor.createTensor(env, edgeBuf, longArrayOf(1, 1, h.toLong(), w.toLong()))
        val lineTensor = OnnxTensor.createTensor(env, lineBuf, longArrayOf(1, 1, h.toLong(), w.toLong()))
        val relPosTensor = OnnxTensor.createTensor(env, relPosBuf, longArrayOf(1, h.toLong(), w.toLong()))
        val directTensor = OnnxTensor.createTensor(env, directBuf, longArrayOf(1, h.toLong(), w.toLong(), 4))

        val inputs = mapOf(
            "image" to imgTensor,
            "mask" to maskTensor,
            "edge" to edgeTensor,
            "line" to lineTensor,
            "rel_pos" to relPosTensor,
            "direct" to directTensor
        )

        var res = generatorSession.run(inputs)
        try {
            val outTensor = res.get(0) as OnnxTensor
            val outBuf = outTensor.floatBuffer

            val outImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val channelSize = h * w

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val rawR = outBuf.get(idx)
                    val rawG = outBuf.get(channelSize + idx)
                    val rawB = outBuf.get(2 * channelSize + idx)

                    val r: Int
                    val g: Int
                    val b: Int

                    if (isZitspp) {
                        r = ((rawR + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                        g = ((rawG + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                        b = ((rawB + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                    } else {
                        r = (rawR * 255.0f).toInt().coerceIn(0, 255)
                        g = (rawG * 255.0f).toInt().coerceIn(0, 255)
                        b = (rawB * 255.0f).toInt().coerceIn(0, 255)
                    }

                    outImg.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }
            return outImg
        } finally {
            res.close()
            imgTensor.close()
            maskTensor.close()
            edgeTensor.close()
            lineTensor.close()
            relPosTensor.close()
            directTensor.close()
        }
    }
}
