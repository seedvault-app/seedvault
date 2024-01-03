package com.stevesoltys.seedvault.transport.backup

import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.service.app.backup.InputFactory
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.service.app.backup.full.DEFAULT_QUOTA_FULL_BACKUP
import io.mockk.mockk
import java.io.OutputStream

internal abstract class BackupTest : TransportTest() {

    protected val inputFactory = mockk<InputFactory>()
    protected val data = mockk<ParcelFileDescriptor>()
    protected val outputStream = mockk<OutputStream>()
    protected val encryptedOutputStream = mockk<OutputStream>()

    protected val quota = DEFAULT_QUOTA_FULL_BACKUP

}
