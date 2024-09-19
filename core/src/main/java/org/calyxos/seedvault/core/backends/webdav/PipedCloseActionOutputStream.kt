/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

internal class PipedCloseActionOutputStream(
    inputStream: PipedInputStream,
) : PipedOutputStream(inputStream) {

    private var onClose: (() -> Unit)? = null

    override fun write(b: Int) {
        try {
            super.write(b)
        } catch (e: Exception) {
            try {
                onClose?.invoke()
            } catch (closeException: Exception) {
                e.addSuppressed(closeException)
            }
            throw e
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        try {
            super.write(b, off, len)
        } catch (e: Exception) {
            try {
                onClose?.invoke()
            } catch (closeException: Exception) {
                e.addSuppressed(closeException)
            }
            throw e
        }
    }

    @Throws(IOException::class)
    override fun close() {
        super.close()
        try {
            onClose?.invoke()
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    fun doOnClose(function: () -> Unit) {
        this.onClose = function
    }
}
