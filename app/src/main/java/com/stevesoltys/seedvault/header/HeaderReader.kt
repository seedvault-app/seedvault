/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.header

import com.stevesoltys.seedvault.Utf8
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

internal interface HeaderReader {
    @Throws(IOException::class, UnsupportedVersionException::class)
    fun readVersion(inputStream: InputStream, expectedVersion: Byte): Byte

    @Suppress("Deprecation")
    @Deprecated("For restoring v0 backups only")
    @Throws(SecurityException::class)
    fun getVersionHeader(byteArray: ByteArray): VersionHeader

    @Suppress("Deprecation")
    @Deprecated("For restoring v0 backups only")
    @Throws(EOFException::class, IOException::class)
    fun readSegmentHeader(inputStream: InputStream): SegmentHeader
}

internal class HeaderReaderImpl : HeaderReader {

    @Throws(IOException::class, UnsupportedVersionException::class, GeneralSecurityException::class)
    override fun readVersion(inputStream: InputStream, expectedVersion: Byte): Byte {
        val version = inputStream.read().toByte()
        if (version < 0) throw IOException()
        if (version > VERSION) throw UnsupportedVersionException(version)
        if (expectedVersion != version) throw GeneralSecurityException(
            "Expected version ${expectedVersion.toInt()}, but got ${version.toInt()}"
        )
        return version
    }

    @Suppress("OverridingDeprecatedMember", "Deprecation")
    override fun getVersionHeader(byteArray: ByteArray): VersionHeader {
        val buffer = ByteBuffer.wrap(byteArray)
        val version = buffer.get()

        val packageLength = buffer.short.toInt()
        if (packageLength <= 0) throw SecurityException("Invalid package length: $packageLength")
        if (packageLength > MAX_PACKAGE_LENGTH_SIZE) throw SecurityException(
            "Too large package length: $packageLength"
        )
        if (packageLength > buffer.remaining()) throw SecurityException(
            "Not enough bytes for package name"
        )
        val packageName = ByteArray(packageLength)
            .apply { buffer.get(this) }
            .toString(Utf8)

        val keyLength = buffer.short.toInt()
        if (keyLength < 0) throw SecurityException("Invalid key length: $keyLength")
        if (keyLength > MAX_KEY_LENGTH_SIZE) throw SecurityException(
            "Too large key length: $keyLength"
        )
        if (keyLength > buffer.remaining()) throw SecurityException("Not enough bytes for key")
        val key = if (keyLength == 0) null else ByteArray(keyLength)
            .apply { buffer.get(this) }
            .toString(Utf8)

        if (buffer.remaining() != 0) throw SecurityException("Found extra bytes in header")

        return VersionHeader(version, packageName, key)
    }

    @Throws(EOFException::class, IOException::class)
    @Suppress("OverridingDeprecatedMember", "Deprecation")
    override fun readSegmentHeader(inputStream: InputStream): SegmentHeader {
        val buffer = ByteArray(SEGMENT_HEADER_SIZE)
        val bytesRead = inputStream.read(buffer)
        if (bytesRead == -1) throw EOFException()
        if (bytesRead != SEGMENT_HEADER_SIZE) {
            throw IOException("Read $bytesRead bytes, but expected $SEGMENT_HEADER_SIZE")
        }

        val segmentLength = ByteBuffer.wrap(buffer, 0, SEGMENT_LENGTH_SIZE).short
        if (segmentLength <= 0) throw IOException()
        val nonce = buffer.copyOfRange(SEGMENT_LENGTH_SIZE, buffer.size)

        return SegmentHeader(segmentLength, nonce)
    }

}

class UnsupportedVersionException(val version: Byte) : IOException()
