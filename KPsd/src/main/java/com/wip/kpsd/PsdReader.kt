package com.wip.kpsd

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class PsdReader(val bytes: ByteArray) {
    var offset = 0
    var large = false

    private val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    fun readUint8(): Int {
        val v = bytes[offset].toInt() and 0xff
        offset++
        return v
    }

    fun peekUint8(): Int {
        return bytes[offset].toInt() and 0xff
    }

    fun readInt16(): Int {
        val v = byteBuffer.getShort(offset).toInt()
        offset += 2
        return v
    }

    fun readUint16(): Int {
        val v = byteBuffer.getShort(offset).toInt() and 0xffff
        offset += 2
        return v
    }

    fun readUint16LE(): Int {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val v = byteBuffer.getShort(offset).toInt() and 0xffff
        offset += 2
        byteBuffer.order(ByteOrder.BIG_ENDIAN)
        return v
    }

    fun readInt32(): Int {
        val v = byteBuffer.getInt(offset)
        offset += 4
        return v
    }

    fun readInt32LE(): Int {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val v = byteBuffer.getInt(offset)
        offset += 4
        byteBuffer.order(ByteOrder.BIG_ENDIAN)
        return v
    }

    fun readUint32(): Long {
        val v = byteBuffer.getInt(offset).toLong() and 0xffffffffL
        offset += 4
        return v
    }

    fun readFloat32(): Float {
        val v = byteBuffer.getFloat(offset)
        offset += 4
        return v
    }

    fun readFloat64(): Double {
        val v = byteBuffer.getDouble(offset)
        offset += 8
        return v
    }

    fun readBytes(length: Int): ByteArray {
        val result = ByteArray(length)
        System.arraycopy(bytes, offset, result, 0, length)
        offset += length
        return result
    }

    fun readSignature(): String {
        return readAsciiString(4)
    }

    fun readPascalString(padTo: Int): String {
        var length = readUint8()
        val text = if (length > 0) readAsciiString(length) else ""
        length++ // including size byte
        while (length % padTo != 0) {
            offset++
            length++
        }
        return text
    }

    fun readUnicodeString(): String {
        val length = readInt32()
        return readUnicodeStringWithLength(length)
    }

    fun readUnicodeStringWithLength(length: Int): String {
        val sb = java.lang.StringBuilder()
        for (i in 0 until length) {
            val charCode = readUint16()
            if (charCode != 0 || i < length - 1) { // avoid trailing null
                sb.append(charCode.toChar())
            }
        }
        return sb.toString()
    }

    fun readAsciiString(length: Int): String {
        val str = String(bytes, offset, length, StandardCharsets.US_ASCII)
        offset += length
        return str
    }

    fun skipBytes(count: Int) {
        offset += count
    }

    fun checkSignature(a: String, b: String? = null) {
        val offsetBefore = offset
        val signature = readSignature()
        if (signature != a && signature != b) {
            throw IllegalStateException("Invalid signature: '$signature' at 0x${offsetBefore.toString(16)}")
        }
    }

    fun <T> readSection(round: Int, eightBytes: Boolean = false, func: (left: () -> Int) -> T): T? {
        var length = readUint32()
        if (eightBytes) {
            if (length != 0L) throw IllegalStateException("Sizes larger than 4GB are not supported")
            length = readUint32()
        }
        if (length <= 0L) return null
        val start = offset
        val end = start + length.toInt()
        if (end > bytes.size) throw IllegalStateException("Section exceeds file size")
        val result = func { end - offset }
        if (offset != end) {
            offset = end
        }
        var totalLen = length.toInt()
        while (totalLen % round != 0) {
            totalLen++
        }
        offset = start + totalLen
        return result
    }

    data class ChannelInfo(val id: ChannelID, val length: Int)

    fun readPsd(): Psd {
        checkSignature("8BPS")
        val version = readUint16()
        if (version != 1 && version != 2) throw IllegalStateException("Invalid PSD file version: $version")

        skipBytes(6) // Reserved
        val channels = readUint16()
        val height = readInt32()
        val width = readInt32()
        val bitsPerChannel = readUint16()
        val colorMode = ColorMode.fromInt(readUint16())

        val psd = Psd(
            width = width,
            height = height,
            channels = channels,
            bitsPerChannel = bitsPerChannel,
            colorMode = colorMode
        )

        large = version == 2

        // Color Mode Data
        readSection(1) { left ->
            if (colorMode == ColorMode.Indexed) {
                val len = left()
                if (len == 768) {
                    val palette = mutableListOf<Rgb>()
                    val r = readBytes(256)
                    val g = readBytes(256)
                    val b = readBytes(256)
                    for (i in 0 until 256) {
                        palette.add(Rgb(r[i].toInt() and 0xff, g[i].toInt() and 0xff, b[i].toInt() and 0xff))
                    }
                    psd.palette = palette
                } else {
                    skipBytes(len)
                }
            } else {
                skipBytes(left())
            }
        }

        // Image Resources
        readSection(1) { left ->
            val resources = ImageResources()
            while (left() > 0) {
                val sigOffset = offset
                val sig = readSignature()
                if (sig != "8BIM" && sig != "MeSa" && sig != "AgHg" && sig != "PHUT" && sig != "DCSR") {
                    offset = sigOffset + 1
                    continue
                }
                val id = readUint16()
                readPascalString(2) // Name

                readSection(2) { innerLeft ->
                    if (id == 1026) { // Group info
                        val count = innerLeft() / 2
                        val groups = IntArray(count) { readUint16() }
                        resources.layersGroup = groups
                    } else if (id == 1037) { // Group enabled status
                        val count = innerLeft()
                        val enabled = IntArray(count) { readUint8() }
                        resources.layerGroupsEnabledId = enabled
                    } else {
                        skipBytes(innerLeft())
                    }
                }
            }
            psd.imageResources = resources
        }

        // Layer and Mask Info
        readSection(1, eightBytes = large) { left ->
            readSection(2, eightBytes = large) { innerLeft ->
                readLayerInfo(psd)
                skipBytes(innerLeft())
            }

            if (left() > 0) {
                val globalMask = readGlobalLayerMaskInfo()
                if (globalMask != null) psd.globalLayerMaskInfo = globalMask
            }

            while (left() > 0) {
                while (left() > 0 && peekUint8() == 0) {
                    skipBytes(1)
                }
                if (left() >= 12) {
                    readAdditionalLayerInfo(psd, psd)
                } else {
                    skipBytes(left())
                }
            }
        }

        // Read composite image data
        readCompositeImageData(psd)

        return psd
    }

    private fun readLayerInfo(psd: Psd) {
        var layerCount = readInt16()
        if (layerCount < 0) {
            layerCount = -layerCount
        }

        val layers = mutableListOf<Layer>()
        val layerChannels = mutableListOf<List<ChannelInfo>>()

        for (i in 0 until layerCount) {
            val record = readLayerRecord(psd)
            layers.add(record.first)
            layerChannels.add(record.second)
        }

        for (i in 0 until layerCount) {
            readLayerChannelImageData(psd, layers[i], layerChannels[i])
        }

        // Reconstruct Hierarchy
        psd.children = mutableListOf()
        val stack = mutableListOf<Any>(psd)

        for (i in layers.size - 1 downTo 0) {
            val l = layers[i]
            val type = l.sectionDivider?.type ?: SectionDividerType.Other

            if (type == SectionDividerType.OpenFolder || type == SectionDividerType.ClosedFolder) {
                l.opened = type == SectionDividerType.OpenFolder
                l.children = mutableListOf()

                val top = stack.last()
                if (top is Psd) top.children.add(0, l)
                else if (top is Layer) top.children!!.add(0, l)

                stack.add(l)
            } else if (type == SectionDividerType.BoundingSectionDivider) {
                if (stack.size > 1) {
                    stack.removeAt(stack.size - 1)
                }
            } else {
                val top = stack.last()
                if (top is Psd) top.children.add(0, l)
                else if (top is Layer) top.children!!.add(0, l)
            }
        }
    }

    private fun readLayerRecord(psd: Psd): Pair<Layer, List<ChannelInfo>> {
        val layer = Layer()
        layer.top = readInt32()
        layer.left = readInt32()
        layer.bottom = readInt32()
        layer.right = readInt32()

        val channelCount = readUint16()
        val channels = mutableListOf<ChannelInfo>()

        for (i in 0 until channelCount) {
            val id = ChannelID.fromInt(readInt16())
            var length = readUint32()
            if (large) {
                if (length != 0L) throw IllegalStateException("Sizes larger than 4GB are not supported")
                length = readUint32()
            }
            channels.add(ChannelInfo(id, length.toInt()))
        }

        checkSignature("8BIM")
        val blendSig = readSignature()
        layer.blendMode = blendSig // mapping can be done later if needed or kept as raw signature
        layer.opacity = readUint8() / 255.0f
        layer.clipping = readUint8() == 1

        val flags = readUint8()
        layer.transparencyProtected = (flags and 0x01) != 0
        layer.hidden = (flags and 0x02) != 0
        layer.effectsOpen = (flags and 0x20) != 0

        skipBytes(1) // padding

        readSection(1) { left ->
            readLayerMaskData(layer)
            readLayerBlendingRanges(layer)
            layer.name = readPascalString(1)

            // align with next signature
            while (left() > 4 && peekUint8() != 0x38) { // signature starts with '8' (0x38)
                offset++
            }

            while (left() >= 12) {
                readAdditionalLayerInfo(psd, layer)
            }

            skipBytes(left())
        }

        return Pair(layer, channels)
    }

    private fun readLayerMaskData(layer: Layer) {
        readSection(1) { left ->
            if (left() > 0) {
                val mask = LayerMaskData()
                layer.mask = mask
                mask.top = readInt32()
                mask.left = readInt32()
                mask.bottom = readInt32()
                mask.right = readInt32()
                mask.defaultColor = readUint8()

                val flags = readUint8()
                mask.positionRelativeToLayer = (flags and 1) != 0
                mask.disabled = (flags and 2) != 0
                mask.fromVectorData = (flags and 8) != 0

                if (left() >= 18) {
                    val realMask = LayerMaskData()
                    layer.realMask = realMask
                    val realFlags = readUint8()
                    realMask.positionRelativeToLayer = (realFlags and 1) != 0
                    realMask.disabled = (realFlags and 2) != 0
                    realMask.fromVectorData = (realFlags and 8) != 0
                    realMask.defaultColor = readUint8()
                    realMask.top = readInt32()
                    realMask.left = readInt32()
                    realMask.bottom = readInt32()
                    realMask.right = readInt32()
                }
                skipBytes(left())
            }
        }
    }

    private fun readLayerBlendingRanges(layer: Layer) {
        readSection(1) { left ->
            if (left() > 0) {
                val compSource = readBytes(4)
                val compDest = readBytes(4)
                val ranges = mutableListOf<BlendingRange>()
                while (left() > 0) {
                    ranges.add(BlendingRange(readBytes(4), readBytes(4)))
                }
                layer.blendingRanges = BlendingRanges(compSource, compDest, ranges)
            }
        }
    }

    private fun readAdditionalLayerInfo(psd: Psd, target: Any) {
        val sig = readSignature()
        if (sig != "8BIM" && sig != "8B64") {
            throw IllegalStateException("Invalid signature: '$sig' in additional layer info")
        }
        val key = readSignature()
        val u64 = sig == "8B64" || (large && key in listOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD", "cinf"))

        readSection(2, eightBytes = u64) { left ->
            if (key == "TySh" && target is Layer) {
                val version = readInt16()
                if (version != 1) throw IllegalStateException("Invalid TySh version: $version")

                val transform = DoubleArray(6) { readFloat64() }
                val textVersion = readInt16()
                if (textVersion != 50) throw IllegalStateException("Invalid TySh text version")

                val textDesc = PsdDescriptor.readVersionAndDescriptor(this)
                val warpVersion = readInt16()
                if (warpVersion != 1) throw IllegalStateException("Invalid TySh warp version")

                val warpDesc = PsdDescriptor.readVersionAndDescriptor(this)

                val leftVal = readFloat32()
                val topVal = readFloat32()
                val rightVal = readFloat32()
                val bottomVal = readFloat32()

                val textVal = (textDesc.properties["Txt "] as? TextValue)?.value ?: ""
                val rawEngineData = textDesc.properties["EngineData"] as? RawDataValue

                val textLayout = if (rawEngineData != null) {
                    val parsed = EngineData.parseEngineData(rawEngineData.data)
                    if (parsed != null) {
                        val engineDict = parsed["EngineDict"] as? Map<String, Any?> ?: emptyMap()
                        val resourceDict = parsed["ResourceDict"] as? Map<String, Any?> ?: emptyMap()
                        TextLayer.decodeEngineData(engineDict, resourceDict)
                    } else {
                        LayerTextData(text = textVal)
                    }
                } else {
                    LayerTextData(text = textVal)
                }

                textLayout.transform = transform
                textLayout.left = leftVal
                textLayout.top = topVal
                textLayout.right = rightVal
                textLayout.bottom = bottomVal
                target.text = textLayout
            } else if (key == "luni" && target is Layer) {
                val charCount = readInt32()
                target.name = readUnicodeStringWithLength(charCount)
            } else if (key == "lyid" && target is Layer) {
                target.id = readUint32().toInt()
            } else if (key == "lsct" && target is Layer) {
                val type = SectionDividerType.fromInt(readInt32())
                var dividerKey: String? = null
                var subType: Int? = null
                if (left() >= 8) {
                    checkSignature("8BIM")
                    dividerKey = readSignature()
                }
                if (left() >= 4) {
                    subType = readInt32()
                }
                target.sectionDivider = SectionDivider(type, dividerKey, subType)
            } else {
                skipBytes(left())
            }
        }
    }

    private fun readLayerChannelImageData(psd: Psd, layer: Layer, channels: List<ChannelInfo>) {
        val width = layer.right - layer.left
        val height = layer.bottom - layer.top
        if (width <= 0 || height <= 0) {
            // Empty layer data, skip read
            for (c in channels) {
                skipBytes(c.length)
            }
            return
        }

        val step = 4
        val pixelData = PixelData(width, height, ByteArray(width * height * step))

        for (c in channels) {
            val start = offset
            val compression = Compression.fromInt(readUint16())
            val len = c.length - 2
            val data = if (len > 0) readBytes(len) else ByteArray(0)
            offset = start + c.length

            val offsetInPixelData = PsdHelpers.offsetForChannel(c.id, false)
            if (compression == Compression.RawData) {
                PsdHelpers.copyChannelToPixelData(pixelData, data, offsetInPixelData, step)
            } else if (compression == Compression.RleCompressed) {
                val rleReader = PsdReader(data)
                val lengthsCount = height
                val lengths = IntArray(lengthsCount) {
                    if (rleReader.large) rleReader.readInt32() else rleReader.readUint16()
                }
                PsdHelpers.readDataRLE(lengths, data, rleReader.offset, pixelData, width, height, step, intArrayOf(offsetInPixelData))
            } else if (compression == Compression.ZipWithoutPrediction) {
                PsdHelpers.readDataZip(data, pixelData, width, height, 8, step, offsetInPixelData, false)
            } else if (compression == Compression.ZipWithPrediction) {
                PsdHelpers.readDataZip(data, pixelData, width, height, 8, step, offsetInPixelData, true)
            }
        }

        // Apply fallback alpha
        var hasAlpha = false
        for (c in channels) {
            if (c.id == ChannelID.Transparency) hasAlpha = true
        }
        if (!hasAlpha) {
            // Fill alpha channel with 255
            for (i in 3 until pixelData.data.size step 4) {
                pixelData.data[i] = 255.toByte()
            }
        }

        layer.imageData = pixelData
    }

    private fun readGlobalLayerMaskInfo(): GlobalLayerMaskInfo? {
        return readSection(1) { left ->
            if (left() >= 13) {
                val overlayColorSpace = readUint16()
                val colorSpace1 = readUint16()
                val colorSpace2 = readUint16()
                val colorSpace3 = readUint16()
                val colorSpace4 = readUint16()
                val opacity = readUint16() / 65535.0f
                val kind = readUint8()
                skipBytes(left())
                GlobalLayerMaskInfo(overlayColorSpace, colorSpace1, colorSpace2, colorSpace3, colorSpace4, opacity, kind)
            } else {
                skipBytes(left())
                null
            }
        }
    }

    private fun readCompositeImageData(psd: Psd) {
        val compression = Compression.fromInt(readUint16())
        val width = psd.width
        val height = psd.height
        val step = 4
        val pixelData = PixelData(width, height, ByteArray(width * height * step))

        val channelIds = if (psd.colorMode == ColorMode.Grayscale) {
            if (psd.channels != null && psd.channels!! > 1) intArrayOf(0, 3) else intArrayOf(0)
        } else {
            if (psd.channels != null && psd.channels!! > 3) intArrayOf(0, 1, 2, 3) else intArrayOf(0, 1, 2)
        }

        if (compression == Compression.RawData) {
            for (c in channelIds) {
                val size = width * height
                val data = readBytes(size)
                PsdHelpers.copyChannelToPixelData(pixelData, data, c, step)
            }
        } else if (compression == Compression.RleCompressed) {
            // RLE lengths are written at the start of section for all channels
            // In composite, lengths are height * channelCount
            val lengths = IntArray(channelIds.size * height) {
                if (large) readInt32() else readUint16()
            }
            for (cIndex in channelIds.indices) {
                val offsetInPixelData = channelIds[cIndex]
                val startLenIdx = cIndex * height
                val channelLengths = lengths.sliceArray(startLenIdx until startLenIdx + height)
                val totalLength = channelLengths.sum()
                val data = readBytes(totalLength)
                PsdHelpers.readDataRLE(channelLengths, data, 0, pixelData, width, height, step, intArrayOf(offsetInPixelData))
            }
        }

        if (psd.colorMode == ColorMode.Grayscale) {
            val size = width * height
            for (i in 0 until size) {
                val r = pixelData.data[i * step]
                pixelData.data[i * step + 1] = r
                pixelData.data[i * step + 2] = r
            }
        }

        // Fill Alpha with 255 if not loaded
        if (3 !in channelIds) {
            for (i in 3 until pixelData.data.size step 4) {
                pixelData.data[i] = 255.toByte()
            }
        }

        psd.imageData = pixelData
    }
}
