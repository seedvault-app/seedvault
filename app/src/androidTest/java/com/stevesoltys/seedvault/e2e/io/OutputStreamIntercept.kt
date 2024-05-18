/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.io

import java.io.ByteArrayOutputStream
import java.io.OutputStream

class OutputStreamIntercept(
    private val outputStream: OutputStream,
    private val intercept: ByteArrayOutputStream
) : OutputStream() {

    override fun write(byte: Int) {
        intercept.write(byte)
        outputStream.write(byte)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        intercept.write(buffer, offset, length)
        outputStream.write(buffer, offset, length)
    }
}
