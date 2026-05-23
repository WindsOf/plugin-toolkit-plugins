package com.wip.kpsd

object KPsd {
    fun read(bytes: ByteArray): Psd {
        return PsdReader(bytes).readPsd()
    }

    fun write(psd: Psd, compress: Boolean = false, large: Boolean = false): ByteArray {
        val writer = PsdWriter()
        writer.large = large
        writer.writePsd(psd, compress)
        return writer.getWriterBuffer()
    }
}