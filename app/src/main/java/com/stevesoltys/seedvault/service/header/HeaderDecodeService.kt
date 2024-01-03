package com.stevesoltys.seedvault.service.header

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal interface HeaderDecodeService {
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

class UnsupportedVersionException(val version: Byte) : IOException()
