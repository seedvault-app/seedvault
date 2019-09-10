package com.stevesoltys.backup.transport.backup

import android.os.ParcelFileDescriptor
import com.stevesoltys.backup.transport.TransportTest
import com.stevesoltys.backup.header.HeaderWriter
import com.stevesoltys.backup.header.VersionHeader
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
