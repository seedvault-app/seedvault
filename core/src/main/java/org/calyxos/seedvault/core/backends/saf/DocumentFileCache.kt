/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.saf

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.FileHandle
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.TopLevelFolder
import java.util.concurrent.ConcurrentHashMap

internal class DocumentFileCache(
    private val context: Context,
    private val baseFile: DocumentFile,
    private val root: String,
) {

    private val cache = ConcurrentHashMap<String, DocumentFile>()

    internal suspend fun getRootFile(): DocumentFile {
        return cache.getOrPut(root) {
            baseFile.getOrCreateDirectory(context, root)
        }
    }

    internal suspend fun getOrCreateFile(fh: FileHandle): DocumentFile = when (fh) {
        is TopLevelFolder -> cache.getOrPut("$root/${fh.relativePath}") {
            getRootFile().getOrCreateDirectory(context, fh.name)
        }

        is AppBackupFileType.Blob -> {
            val subFolderName = fh.name.substring(0, 2)
            cache.getOrPut("$root/${fh.topLevelFolder.name}/$subFolderName") {
                getOrCreateFile(fh.topLevelFolder).getOrCreateDirectory(context, subFolderName)
            }.getOrCreateFile(context, fh.name)
        }

        is AppBackupFileType.Snapshot -> {
            getOrCreateFile(fh.topLevelFolder).getOrCreateFile(context, fh.name)
        }

        is FileBackupFileType.Blob -> {
            val subFolderName = fh.name.substring(0, 2)
            cache.getOrPut("$root/${fh.topLevelFolder.name}/$subFolderName") {
                getOrCreateFile(fh.topLevelFolder).getOrCreateDirectory(context, subFolderName)
            }.getOrCreateFile(context, fh.name)
        }

        is FileBackupFileType.Snapshot -> {
            getOrCreateFile(fh.topLevelFolder).getOrCreateFile(context, fh.name)
        }

        is LegacyAppBackupFile -> cache.getOrPut("$root/${fh.relativePath}") {
            getOrCreateFile(fh.topLevelFolder).getOrCreateFile(context, fh.name)
        }
    }

    internal suspend fun getFile(fh: FileHandle): DocumentFile? = when (fh) {
        is TopLevelFolder -> cache.getOrElse("$root/${fh.relativePath}") {
            getRootFile().findFileBlocking(context, fh.name)
        }

        is AppBackupFileType.Blob -> {
            val subFolderName = fh.name.substring(0, 2)
            cache.getOrElse("$root/${fh.topLevelFolder.name}/$subFolderName") {
                getFile(fh.topLevelFolder)?.findFileBlocking(context, subFolderName)
            }?.findFileBlocking(context, fh.name)
        }

        is AppBackupFileType.Snapshot -> {
            getFile(fh.topLevelFolder)?.findFileBlocking(context, fh.name)
        }

        is FileBackupFileType.Blob -> {
            val subFolderName = fh.name.substring(0, 2)
            cache.getOrElse("$root/${fh.topLevelFolder.name}/$subFolderName") {
                getFile(fh.topLevelFolder)?.findFileBlocking(context, subFolderName)
            }?.findFileBlocking(context, fh.name)
        }

        is FileBackupFileType.Snapshot -> {
            getFile(fh.topLevelFolder)?.findFileBlocking(context, fh.name)
        }

        is LegacyAppBackupFile -> cache.getOrElse("$root/${fh.relativePath}") {
            getFile(fh.topLevelFolder)?.findFileBlocking(context, fh.name)
        }
    }

    internal fun removeFromCache(fh: FileHandle) {
        cache.remove("$root/${fh.relativePath}")
    }

    internal fun clearAll() {
        cache.clear()
    }
}
