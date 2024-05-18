/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.io

import android.app.backup.BackupDataInput
import java.io.FileDescriptor

class BackupDataInputIntercept(
    fileDescriptor: FileDescriptor,
    private val callback: (String, ByteArray) -> Unit,
) : BackupDataInput(fileDescriptor) {

    var currentKey: String? = null

    override fun getKey(): String? {
        currentKey = super.getKey()
        return currentKey
    }

    override fun readEntityData(data: ByteArray, offset: Int, size: Int): Int {
        val result = super.readEntityData(data, offset, size)

        callback(currentKey!!, data.copyOf(result))
        return result
    }
}
