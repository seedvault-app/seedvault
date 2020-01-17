package com.stevesoltys.seedvault.transport.restore

import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.transport.TransportTest
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
