/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * This class exists for easier testing, so we can mock it and return custom data outputs.
 */
internal class OutputFactory {

    fun getBackupDataOutput(outputFileDescriptor: ParcelFileDescriptor): BackupDataOutput {
        return BackupDataOutput(outputFileDescriptor.fileDescriptor)
    }

    fun getOutputStream(outputFileDescriptor: ParcelFileDescriptor): OutputStream {
        return FileOutputStream(outputFileDescriptor.fileDescriptor)
    }

}
