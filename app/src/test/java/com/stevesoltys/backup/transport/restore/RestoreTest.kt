package com.stevesoltys.backup.transport.restore

import android.os.ParcelFileDescriptor
import com.stevesoltys.backup.getRandomByteArray
import com.stevesoltys.backup.transport.TransportTest
import com.stevesoltys.backup.header.HeaderReader
import com.stevesoltys.backup.header.VERSION
import io.mockk.mockk
import java.io.InputStream
import kotlin.random.Random

internal abstract class RestoreTest : TransportTest() {

    protected val outputFactory = mockk<OutputFactory>()
    protected val headerReader = mockk<HeaderReader>()
    protected val fileDescriptor = mockk<ParcelFileDescriptor>()

    protected val token = Random.nextLong()
    protected val data = getRandomByteArray()
    protected val inputStream = mockk<InputStream>()

    protected val unsupportedVersion = (VERSION + 1).toByte()

}
