package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Pure Kotlin/Java image preprocessing utilities for ONNX Runtime tensor construction.
 */
object ImageTensorUtils {

    /**
     * Allocates a native direct [FloatBuffer] off-heap for zero-copy ONNX tensor transfer.
     */
    fun allocateDirectFloatBuffer(capacity: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(capacity * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /**
     * Resizes a [BufferedImage] to the specified target dimensions.
     */
    fun resizeImage(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source
        }
        val resized = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = resized.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        g2d.dispose()
        return resized
    }

    /**
     * Converts a [BufferedImage] into a planar direct FloatBuffer in NCHW format [1, 3, targetHeight, targetWidth]
     * with RGB values normalized to [0.0f, 1.0f].
     */
    fun imageToFloatBuffer(source: BufferedImage, targetWidth: Int, targetHeight: Int): FloatBuffer {
        val resized = resizeImage(source, targetWidth, targetHeight)
        val buffer = allocateDirectFloatBuffer(1 * 3 * targetHeight * targetWidth)

        val pixels = IntArray(targetWidth * targetHeight)
        resized.getRGB(0, 0, targetWidth, targetHeight, pixels, 0, targetWidth)

        val channelSize = targetWidth * targetHeight
        val rOffset = 0
        val gOffset = channelSize
        val bOffset = 2 * channelSize

        for (i in 0 until channelSize) {
            val rgb = pixels[i]
            val r = ((rgb shr 16) and 0xFF) / 255.0f
            val g = ((rgb shr 8) and 0xFF) / 255.0f
            val b = (rgb and 0xFF) / 255.0f

            buffer.put(rOffset + i, r)
            buffer.put(gOffset + i, g)
            buffer.put(bOffset + i, b)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Creates an [OnnxTensor] from a [BufferedImage] for the given [OrtEnvironment].
     */
    fun createTensor(
        env: OrtEnvironment,
        image: BufferedImage,
        targetWidth: Int,
        targetHeight: Int
    ): OnnxTensor {
        val floatBuffer = imageToFloatBuffer(image, targetWidth, targetHeight)
        val shape = longArrayOf(1L, 3L, targetHeight.toLong(), targetWidth.toLong())
        return OnnxTensor.createTensor(env, floatBuffer, shape)
    }

    /**
     * Creates an image [OnnxTensor] for inpainting models respecting the specified normalization mode.
     */
    fun createInpaintingImageTensor(
        env: OrtEnvironment,
        image: BufferedImage,
        targetWidth: Int,
        targetHeight: Int,
        normMode: String = "zero_to_one"
    ): OnnxTensor {
        val resized = resizeImage(image, targetWidth, targetHeight)
        val buffer = allocateDirectFloatBuffer(1 * 3 * targetHeight * targetWidth)
        val pixels = IntArray(targetWidth * targetHeight)
        resized.getRGB(0, 0, targetWidth, targetHeight, pixels, 0, targetWidth)

        val channelSize = targetWidth * targetHeight
        val rOffset = 0
        val gOffset = channelSize
        val bOffset = 2 * channelSize

        val isNegOne = normMode.equals("neg_one_to_one", ignoreCase = true)

        for (i in 0 until channelSize) {
            val rgb = pixels[i]
            val rByte = ((rgb shr 16) and 0xFF)
            val gByte = ((rgb shr 8) and 0xFF)
            val bByte = (rgb and 0xFF)

            val r = if (isNegOne) (rByte / 127.5f) - 1.0f else rByte / 255.0f
            val g = if (isNegOne) (gByte / 127.5f) - 1.0f else gByte / 255.0f
            val b = if (isNegOne) (bByte / 127.5f) - 1.0f else bByte / 255.0f

            buffer.put(rOffset + i, r)
            buffer.put(gOffset + i, g)
            buffer.put(bOffset + i, b)
        }

        buffer.rewind()
        val shape = longArrayOf(1L, 3L, targetHeight.toLong(), targetWidth.toLong())
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    /**
     * Creates a single-channel mask [OnnxTensor] [1, 1, targetHeight, targetWidth] for inpainting models.
     */
    fun createInpaintingMaskTensor(
        env: OrtEnvironment,
        mask: BufferedImage,
        targetWidth: Int,
        targetHeight: Int,
        maskMode: String = "zero_to_one"
    ): OnnxTensor {
        val resized = resizeImage(mask, targetWidth, targetHeight)
        val buffer = allocateDirectFloatBuffer(1 * 1 * targetHeight * targetWidth)
        val pixels = IntArray(targetWidth * targetHeight)
        val raster = resized.raster
        raster.getSamples(0, 0, targetWidth, targetHeight, 0, pixels)

        val isInverted = maskMode.equals("inverted", ignoreCase = true)

        for (i in 0 until targetWidth * targetHeight) {
            val isMasked = pixels[i] > 128
            val value = if (isMasked) {
                if (isInverted) 0.0f else 1.0f
            } else {
                if (isInverted) 1.0f else 0.0f
            }
            buffer.put(i, value)
        }

        buffer.rewind()
        val shape = longArrayOf(1L, 1L, targetHeight.toLong(), targetWidth.toLong())
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    /**
     * Snaps a dimension to the nearest higher or equal multiple of [factor].
     */
    fun snapToMultiple(value: Int, factor: Int = 32): Int {
        val snapped = ((value + factor - 1) / factor) * factor
        return maxOf(factor, snapped)
    }

    /**
     * Decodes an ONNX inpainting output tensor [1, 3, H, W] into a [BufferedImage] (TYPE_INT_RGB)
     * using explicit architecture-safe normalization mapping.
     */
    fun tensorToBufferedImage(tensor: OnnxTensor, normMode: String = "zero_to_one"): BufferedImage {
        val shape = tensor.info.shape
        val buffer = tensor.floatBuffer

        val h: Int
        val w: Int
        val c: Int

        if (shape.size == 4) {
            // [1, 3, H, W]
            c = shape[1].toInt()
            h = shape[2].toInt()
            w = shape[3].toInt()
        } else if (shape.size == 3) {
            // [3, H, W]
            c = shape[0].toInt()
            h = shape[1].toInt()
            w = shape[2].toInt()
        } else {
            throw IllegalArgumentException("Unsupported output tensor shape: ${shape.contentToString()}")
        }

        val channelSize = h * w
        val rOffset = 0
        val gOffset = if (c >= 3) channelSize else 0
        val bOffset = if (c >= 3) 2 * channelSize else 0

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val pixels = IntArray(w * h)

        val isNegOne = normMode.equals("neg_one_to_one", ignoreCase = true)

        for (i in 0 until channelSize) {
            val rawR = buffer.get(rOffset + i)
            val rawG = buffer.get(gOffset + i)
            val rawB = buffer.get(bOffset + i)

            val r = when {
                isNegOne -> ((rawR + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                rawR > 1.5f -> rawR.toInt().coerceIn(0, 255) // Already in 0..255 space
                else -> (rawR * 255.0f).toInt().coerceIn(0, 255) // Standard 0.0..1.0 float space
            }
            val g = when {
                isNegOne -> ((rawG + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                rawG > 1.5f -> rawG.toInt().coerceIn(0, 255)
                else -> (rawG * 255.0f).toInt().coerceIn(0, 255)
            }
            val b = when {
                isNegOne -> ((rawB + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                rawB > 1.5f -> rawB.toInt().coerceIn(0, 255)
                else -> (rawB * 255.0f).toInt().coerceIn(0, 255)
            }

            pixels[i] = (r shl 16) or (g shl 8) or b
        }

        img.setRGB(0, 0, w, h, pixels, 0, w)
        return img
    }
}
