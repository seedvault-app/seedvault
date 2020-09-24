package com.stevesoltys.seedvault.header

import com.stevesoltys.seedvault.Utf8
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

interface HeaderWriter {
    @Throws(IOException::class)
    fun writeVersion(outputStream: OutputStream, header: VersionHeader)

    fun getEncodedVersionHeader(header: VersionHeader): ByteArray

    @Throws(IOException::class)
    fun writeSegmentHeader(outputStream: OutputStream, header: SegmentHeader)
}

internal class HeaderWriterImpl : HeaderWriter {

    @Throws(IOException::class)
    override fun writeVersion(outputStream: OutputStream, header: VersionHeader) {
        val headerBytes = ByteArray(1)
        headerBytes[0] = header.version
        outputStream.write(headerBytes)
    }

    override fun getEncodedVersionHeader(header: VersionHeader): ByteArray {
        val packageBytes = header.packageName.toByteArray(Utf8)
        val keyBytes = header.key?.toByteArray(Utf8)
        val size = 1 + 2 + packageBytes.size + 2 + (keyBytes?.size ?: 0)
        return ByteBuffer.allocate(size).apply {
            put(header.version)
            putShort(packageBytes.size.toShort())
            put(packageBytes)
            if (keyBytes == null) {
                putShort(0.toShort())
            } else {
                putShort(keyBytes.size.toShort())
                put(keyBytes)
            }
        }.array()
    }

    override fun writeSegmentHeader(outputStream: OutputStream, header: SegmentHeader) {
        val buffer = ByteBuffer.allocate(SEGMENT_HEADER_SIZE)
            .putShort(header.segmentLength)
            .put(header.nonce)
        outputStream.write(buffer.array())
    }

}
