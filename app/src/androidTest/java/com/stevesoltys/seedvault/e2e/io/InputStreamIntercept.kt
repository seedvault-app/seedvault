package com.stevesoltys.seedvault.e2e.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

class InputStreamIntercept(
    private val inputStream: InputStream,
    private val intercept: ByteArrayOutputStream
) : InputStream() {

    override fun read(): Int {
        val byte = inputStream.read()
        if (byte != -1) {
            intercept.write(byte)
        }
        return byte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = inputStream.read(buffer, offset, length)
        if (bytesRead != -1) {
            intercept.write(buffer, offset, bytesRead)
        }
        return bytesRead
    }
}
