package com.stevesoltys.seedvault.transport.backup

import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.header.HeaderWriter
import com.stevesoltys.seedvault.header.VersionHeader
import com.stevesoltys.seedvault.transport.TransportTest
import io.mockk.mockk
import java.io.OutputStream

internal abstract class BackupTest : TransportTest() {

    protected val inputFactory = mockk<InputFactory>()
    protected val headerWriter = mockk<HeaderWriter>()
    protected val data = mockk<ParcelFileDescriptor>()
    protected val outputStream = mockk<OutputStream>()

    protected val header = VersionHeader(packageName = packageInfo.packageName)
    protected val quota = 42L

}
