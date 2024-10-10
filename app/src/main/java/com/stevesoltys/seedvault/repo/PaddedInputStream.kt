/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import org.calyxos.seedvault.core.toHexString
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

internal class PaddedInputStream(inputStream: InputStream) : FilterInputStream(inputStream) {

    private val size: Int
    private var bytesRead: Int = 0

    init {
        val sizeBytes = ByteArray(4)
        val bytesRead = inputStream.read(sizeBytes)
        if (bytesRead != 4) {
            throw IOException("Could not read padding size: ${sizeBytes.toHexString()}")
        }
        size = ByteBuffer.wrap(sizeBytes).getInt()
    }

    override fun read(): Int {
        if (bytesRead >= size) return -1
        return getReadBytes(super.read())
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= size) return -1
        if (bytesRead + len >= size) {
            return getReadBytes(super.read(b, off, size - bytesRead))
        }
        return getReadBytes(super.read(b, off, len))
    }

    override fun available(): Int {
        return size - bytesRead
    }

    private fun getReadBytes(read: Int): Int {
        if (read == -1) return -1
        bytesRead += read
        if (bytesRead > size) return -1
        return read
    }
}
