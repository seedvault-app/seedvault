/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins

import android.util.Log
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import java.io.OutputStream

suspend fun Backend.getMetadataOutputStream(token: Long): OutputStream {
    return save(LegacyAppBackupFile.Metadata(token))
}

suspend fun Backend.getAvailableBackups(): Sequence<EncryptedMetadata>? {
    return try {
        // get all restore set tokens in root folder that have a metadata file
        val handles = ArrayList<LegacyAppBackupFile.Metadata>()
        list(null, LegacyAppBackupFile.Metadata::class) { fileInfo ->
            val handle = fileInfo.fileHandle as LegacyAppBackupFile.Metadata
            handles.add(handle)
        }
        val handleIterator = handles.iterator()
        return generateSequence {
            if (!handleIterator.hasNext()) return@generateSequence null // end sequence
            val handle = handleIterator.next()
            EncryptedMetadata(handle.token) {
                load(handle)
            }
        }
    } catch (e: Exception) {
        Log.e("SafBackend", "Error getting available backups: ", e)
        null
    }
}
