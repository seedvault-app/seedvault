package com.stevesoltys.seedvault.service.app.backup

import android.app.backup.BackupDataInput
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.InputStream

/**
 * This class exists for easier testing, so we can mock it and return custom data inputs.
 */
internal class InputFactory {

    fun getBackupDataInput(inputFileDescriptor: ParcelFileDescriptor): BackupDataInput {
        return BackupDataInput(inputFileDescriptor.fileDescriptor)
    }

    fun getInputStream(inputFileDescriptor: ParcelFileDescriptor): InputStream {
        return FileInputStream(inputFileDescriptor.fileDescriptor)
    }

}
