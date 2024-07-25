/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.saf

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.FileHandle
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.TopLevelFolder

internal class DocumentFileCache(
    private val context: Context,
    private val baseFile: DocumentFile,
    private val root: String,
) {

    private val cache = mutableMapOf<String, DocumentFile>()

    internal suspend fun getRootFile(): DocumentFile {
        return cache.getOrPut(root) {
            baseFile.getOrCreateDirectory(context, root)
        }
    }

    internal suspend fun getFile(fh: FileHandle): DocumentFile = when (fh) {
        is TopLevelFolder -> cache.getOrPut("$root/${fh.relativePath}") {
            getRootFile().getOrCreateDirectory(context, fh.name)
        }

        is LegacyAppBackupFile -> cache.getOrPut("$root/${fh.relativePath}") {
            getFile(fh.topLevelFolder).getOrCreateFile(context, fh.name)
        }

        is FileBackupFileType.Blob -> {
            val subFolderName = fh.name.substring(0, 2)
            cache.getOrPut("$root/${fh.topLevelFolder.name}/$subFolderName") {
                getFile(fh.topLevelFolder).getOrCreateDirectory(context, subFolderName)
            }.getOrCreateFile(context, fh.name)
        }

        is FileBackupFileType.Snapshot -> {
            getFile(fh.topLevelFolder).getOrCreateFile(context, fh.name)
        }
    }
}
