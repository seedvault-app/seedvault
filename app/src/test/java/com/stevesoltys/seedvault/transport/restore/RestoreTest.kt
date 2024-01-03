package com.stevesoltys.seedvault.transport.restore

import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.service.app.restore.OutputFactory
import com.stevesoltys.seedvault.service.header.HeaderDecodeService
import com.stevesoltys.seedvault.service.header.VERSION
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.mockk
import java.io.InputStream

internal abstract class RestoreTest : TransportTest() {

    protected val outputFactory = mockk<OutputFactory>()
    protected val headerDecodeService = mockk<HeaderDecodeService>()
    protected val fileDescriptor = mockk<ParcelFileDescriptor>()

    protected val data = getRandomByteArray()
    protected val inputStream = mockk<InputStream>()
    protected val decryptedInputStream = mockk<InputStream>()

    protected val unsupportedVersion = (VERSION + 1).toByte()

}
