package com.stevesoltys.seedvault.header

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.assertContains
import com.stevesoltys.seedvault.getRandomString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.random.Random

@TestInstance(PER_CLASS)
internal class HeaderReaderTest {

    private val reader = HeaderReaderImpl()

    // Version Tests

    @Test
    fun `valid version is read`() {
        val input = byteArrayOf(VERSION)
        val inputStream = ByteArrayInputStream(input)

        assertEquals(VERSION, reader.readVersion(inputStream, VERSION))
    }

    @Test
    fun `too short version stream throws exception`() {
        val input = ByteArray(0)
        val inputStream = ByteArrayInputStream(input)
        assertThrows(IOException::class.javaObjectType) {
            reader.readVersion(inputStream, VERSION)
        }
    }

    @Test
    fun `unsupported version throws exception`() {
        val input = byteArrayOf((VERSION + 1).toByte())
        val inputStream = ByteArrayInputStream(input)
        assertThrows(UnsupportedVersionException::class.javaObjectType) {
            reader.readVersion(inputStream, VERSION)
        }
    }

    @Test
    fun `negative version throws exception`() {
        val input = byteArrayOf((-1).toByte())
        val inputStream = ByteArrayInputStream(input)
        assertThrows(IOException::class.javaObjectType) {
            reader.readVersion(inputStream, VERSION)
        }
    }

    @Test
    fun `max version byte throws exception`() {
        val input = byteArrayOf(Byte.MAX_VALUE)
        val inputStream = ByteArrayInputStream(input)
        assertThrows(UnsupportedVersionException::class.javaObjectType) {
            reader.readVersion(inputStream, Byte.MAX_VALUE)
        }
    }

    @Test
    fun `unexpected version throws exception`() {
        val input = byteArrayOf(VERSION + 1)
        val inputStream = ByteArrayInputStream(input)
        assertThrows(UnsupportedVersionException::class.javaObjectType) {
            reader.readVersion(inputStream, VERSION)
        }
    }

    // VersionHeader Tests

    @Test
    fun `valid VersionHeader is read`() {
        val input = byteArrayOf(VERSION, 0x00, 0x01, 0x61, 0x00, 0x01, 0x62)

        val versionHeader = VersionHeader(VERSION, "a", "b")
        assertEquals(versionHeader, reader.getVersionHeader(input))
    }

    @Test
    fun `zero package length in VersionHeader throws`() {
        val input = byteArrayOf(VERSION, 0x00, 0x00, 0x00, 0x01, 0x62)

        assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
    }

    @Test
    fun `negative package length in VersionHeader throws`() {
        val input = byteArrayOf(0x00, 0xFF, 0xFF, 0x00, 0x01, 0x62)

        assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
    }

    @Test
    fun `too large package length in VersionHeader throws`() {
        val size = MAX_PACKAGE_LENGTH_SIZE + 1
        val input = ByteBuffer.allocate(3 + size)
            .put(VERSION)
            .putShort(size.toShort())
            .put(ByteArray(size))
            .array()
        val e = assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
        assertContains(e.message, size.toString())
    }

    @Test
    fun `insufficient bytes for package in VersionHeader throws`() {
        val input = byteArrayOf(VERSION, 0x00, 0x50)

        assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
    }

    @Test
    fun `zero key length in VersionHeader gets accepted`() {
        val input = byteArrayOf(VERSION, 0x00, 0x01, 0x61, 0x00, 0x00)

        val versionHeader = VersionHeader(VERSION, "a", null)
        assertEquals(versionHeader, reader.getVersionHeader(input))
    }

    @Test
    fun `negative key length in VersionHeader throws`() {
        val input = byteArrayOf(0x00, 0x00, 0x01, 0x61, 0xFF, 0xFF)

        assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
    }

    @Test
    fun `too large key length in VersionHeader throws`() {
        val size = MAX_KEY_LENGTH_SIZE + 1
        val input = ByteBuffer.allocate(4 + size)
            .put(VERSION)
            .putShort(1.toShort())
            .put("a".toByteArray(Utf8))
            .putShort(size.toShort())
            .array()
        val e = assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
        assertContains(e.message, size.toString())
    }

    @Test
    fun `insufficient bytes for key in VersionHeader throws`() {
        val input = byteArrayOf(0x00, 0x00, 0x01, 0x61, 0x00, 0x50)

        assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
    }

    @Test
    fun `extra bytes in VersionHeader throws`() {
        val input = byteArrayOf(VERSION, 0x00, 0x01, 0x61, 0x00, 0x01, 0x62, 0x00)

        assertThrows(SecurityException::class.javaObjectType) {
            reader.getVersionHeader(input)
        }
    }

    @Test
    fun `max sized VersionHeader gets accepted`() {
        val packageName = getRandomString(MAX_PACKAGE_LENGTH_SIZE)
        val key = getRandomString(MAX_KEY_LENGTH_SIZE)
        val input = ByteBuffer.allocate(MAX_VERSION_HEADER_SIZE)
            .put(VERSION)
            .putShort(MAX_PACKAGE_LENGTH_SIZE.toShort())
            .put(packageName.toByteArray(Utf8))
            .putShort(MAX_KEY_LENGTH_SIZE.toShort())
            .put(key.toByteArray(Utf8))
            .array()
        assertEquals(MAX_VERSION_HEADER_SIZE, input.size)
        val h = reader.getVersionHeader(input)
        assertEquals(VERSION, h.version)
        assertEquals(packageName, h.packageName)
        assertEquals(key, h.key)
    }

    // SegmentHeader Tests

    @Test
    fun `too short SegmentHeader throws exception`() {
        val input = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val inputStream = ByteArrayInputStream(input)
        assertThrows(IOException::class.javaObjectType) {
            reader.readSegmentHeader(inputStream)
        }
    }

    @Test
    fun `segment length of zero is rejected`() {
        val input = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val inputStream = ByteArrayInputStream(input)
        assertThrows(IOException::class.javaObjectType) {
            reader.readSegmentHeader(inputStream)
        }
    }

    @Test
    fun `negative segment length is rejected`() {
        val input = byteArrayOf(
            0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val inputStream = ByteArrayInputStream(input)
        assertThrows(IOException::class.javaObjectType) {
            reader.readSegmentHeader(inputStream)
        }
    }

    @Test
    fun `minimum negative segment length is rejected`() {
        val input = byteArrayOf(
            0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val inputStream = ByteArrayInputStream(input)
        assertThrows(IOException::class.javaObjectType) {
            reader.readSegmentHeader(inputStream)
        }
    }

    @Test
    fun `max segment length is accepted`() {
        val input = byteArrayOf(
            0x7F, 0xFF, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val inputStream = ByteArrayInputStream(input)
        assertEquals(
            MAX_SEGMENT_LENGTH,
            reader.readSegmentHeader(inputStream).segmentLength.toInt()
        )
    }

    @Test
    fun `min segment length of 1 is accepted`() {
        val input = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val inputStream = ByteArrayInputStream(input)
        assertEquals(1, reader.readSegmentHeader(inputStream).segmentLength.toInt())
    }

    @Test
    fun `segment length is always read correctly`() {
        val segmentLength = getRandomValidSegmentLength()
        val input = ByteBuffer.allocate(SEGMENT_HEADER_SIZE)
            .putShort(segmentLength)
            .put(ByteArray(IV_SIZE))
            .array()
        val inputStream = ByteArrayInputStream(input)
        assertEquals(segmentLength, reader.readSegmentHeader(inputStream).segmentLength)
    }

    @Test
    fun `nonce is read in big endian`() {
        val nonce = byteArrayOf(
            0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01
        )
        val input = byteArrayOf(
            0x00, 0x01, 0xff, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01
        )
        val inputStream = ByteArrayInputStream(input)
        assertArrayEquals(nonce, reader.readSegmentHeader(inputStream).nonce)
    }

    @Test
    fun `nonce is always read correctly`() {
        val nonce = ByteArray(IV_SIZE).apply { Random.nextBytes(this) }
        val input = ByteBuffer.allocate(SEGMENT_HEADER_SIZE)
            .putShort(1)
            .put(nonce)
            .array()
        val inputStream = ByteArrayInputStream(input)
        assertArrayEquals(nonce, reader.readSegmentHeader(inputStream).nonce)
    }

    private fun byteArrayOf(vararg elements: Int): ByteArray {
        return elements.map { it.toByte() }.toByteArray()
    }

}

internal fun getRandomValidSegmentLength(): Short {
    return Random.nextInt(1, Short.MAX_VALUE.toInt()).toShort()
}
