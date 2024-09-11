/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend

import at.bitfire.dav4jvm.exception.HttpException
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.FileHandle
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import java.io.IOException

suspend fun Backend.getAvailableBackupFileHandles(): List<FileHandle> {
    // v1 get all restore set tokens in root folder that have a metadata file
    // v2 get all snapshots in all repository folders
    return ArrayList<FileHandle>().apply {
        list(
            null,
            AppBackupFileType.Snapshot::class,
            LegacyAppBackupFile.Metadata::class,
        ) { fileInfo ->
            add(fileInfo.fileHandle)
        }
    }
}

fun Exception.isOutOfSpace(): Boolean {
    return when (this) {
        is IOException -> message?.contains("No space left on device") == true ||
            (cause as? HttpException)?.code == 507

        is HttpException -> code == 507

        else -> false
    }
}
