package com.wip.common.models

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer

/**
 * Pure Kotlin/Java image preprocessing utilities for ONNX Runtime tensor construction.
 */
object ImageTensorUtils {

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
     * Converts a [BufferedImage] into a planar FloatBuffer in NCHW format [1, 3, targetHeight, targetWidth]
     * with RGB values normalized to [0.0f, 1.0f].
     */
    fun imageToFloatBuffer(source: BufferedImage, targetWidth: Int, targetHeight: Int): FloatBuffer {
        val resized = resizeImage(source, targetWidth, targetHeight)
        val buffer = FloatBuffer.allocate(1 * 3 * targetHeight * targetWidth)

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
}
