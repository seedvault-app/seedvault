package com.stevesoltys.seedvault.header

import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.getRandomString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@TestInstance(PER_CLASS)
internal class HeaderWriterReaderTest {

    private val writer = HeaderWriterImpl()
    private val reader = HeaderReaderImpl()

    private val packageName = getRandomString(MAX_PACKAGE_LENGTH_SIZE)
    private val key = getRandomString(MAX_KEY_LENGTH_SIZE)
    private val versionHeader = VersionHeader(VERSION, packageName, key)
    private val unsupportedVersionHeader = VersionHeader((VERSION + 1).toByte(), packageName)

    private val segmentLength = getRandomValidSegmentLength()
    private val nonce = getRandomByteArray(IV_SIZE)
    private val segmentHeader = SegmentHeader(segmentLength, nonce)

    @Test
    fun `written version matches read input`() {
        assertEquals(versionHeader.version, readWriteVersion(versionHeader))
    }

    @Test
    fun `reading unsupported version throws exception`() {
        assertThrows(UnsupportedVersionException::class.javaObjectType) {
            readWriteVersion(unsupportedVersionHeader)
        }
    }

    @Test
    fun `VersionHeader output matches read input`() {
        assertEquals(versionHeader, readWrite(versionHeader))
    }

    @Test
    fun `VersionHeader with no key output matches read input`() {
        val versionHeader = VersionHeader(VERSION, packageName, null)
        assertEquals(versionHeader, readWrite(versionHeader))
    }

    @Test
    fun `VersionHeader with empty package name throws`() {
        val versionHeader = VersionHeader(VERSION, "")
        assertThrows(SecurityException::class.java) {
            readWrite(versionHeader)
        }
    }

    @Test
    fun `SegmentHeader constructor needs right IV size`() {
        val nonceTooBig = ByteArray(IV_SIZE + 1).apply { Random.nextBytes(this) }
        assertThrows(IllegalStateException::class.javaObjectType) {
            SegmentHeader(segmentLength, nonceTooBig)
        }
        val nonceTooSmall = ByteArray(IV_SIZE - 1).apply { Random.nextBytes(this) }
        assertThrows(IllegalStateException::class.javaObjectType) {
            SegmentHeader(segmentLength, nonceTooSmall)
        }
    }

    @Test
    fun `SegmentHeader output matches read input`() {
        assertEquals(segmentHeader, readWriteVersion(segmentHeader))
    }

    private fun readWriteVersion(header: VersionHeader): Byte {
        val outputStream = ByteArrayOutputStream()
        writer.writeVersion(outputStream, header)
        val written = outputStream.toByteArray()
        val inputStream = ByteArrayInputStream(written)
        return reader.readVersion(inputStream)
    }

    private fun readWrite(header: VersionHeader): VersionHeader {
        val written = writer.getEncodedVersionHeader(header)
        return reader.getVersionHeader(written)
    }

    private fun readWriteVersion(header: SegmentHeader): SegmentHeader {
        val outputStream = ByteArrayOutputStream()
        writer.writeSegmentHeader(outputStream, header)
        val written = outputStream.toByteArray()
        val inputStream = ByteArrayInputStream(written)
        return reader.readSegmentHeader(inputStream)
    }

    private fun assertEquals(expected: SegmentHeader, actual: SegmentHeader) {
        assertEquals(expected.segmentLength, actual.segmentLength)
        assertArrayEquals(expected.nonce, actual.nonce)
    }

}
