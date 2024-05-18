/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.io

import android.app.backup.BackupDataOutput
import java.io.FileDescriptor

class BackupDataOutputIntercept(
    fileDescriptor: FileDescriptor,
    private val callback: (String, ByteArray) -> Unit,
) : BackupDataOutput(fileDescriptor) {

    private var currentKey: String? = null

    override fun writeEntityHeader(key: String, dataSize: Int): Int {
        currentKey = key
        return super.writeEntityHeader(key, dataSize)
    }

    override fun writeEntityData(data: ByteArray, size: Int): Int {
        callback(currentKey!!, data.copyOf())

        return super.writeEntityData(data, size)
    }
}
