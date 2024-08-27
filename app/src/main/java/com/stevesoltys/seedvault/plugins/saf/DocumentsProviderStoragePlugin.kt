/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.worker.FILE_BACKUP_ICONS
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.calyxos.seedvault.core.backends.saf.SafConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private val TAG = DocumentsProviderStoragePlugin::class.java.simpleName

internal class DocumentsProviderStoragePlugin(
    appContext: Context,
    safStorage: SafStorage,
    root: String = DIRECTORY_ROOT,
) : StoragePlugin<Uri> {

    private val safConfig = SafConfig(
        config = safStorage.config,
        name = safStorage.name,
        isUsb = safStorage.isUsb,
        requiresNetwork = safStorage.requiresNetwork,
        rootId = safStorage.rootId,
    )
    private val delegate: SafBackend = SafBackend(appContext, safConfig, root)

    override suspend fun test(): Boolean {
        return delegate.test()
    }

    override suspend fun getFreeSpace(): Long? {
        return delegate.getFreeSpace()
    }

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        // no-op
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // no-op
    }

    @Throws(IOException::class)
    override suspend fun getOutputStream(token: Long, name: String): OutputStream {
        val handle = when (name) {
            FILE_BACKUP_METADATA -> LegacyAppBackupFile.Metadata(token)
            FILE_BACKUP_ICONS -> LegacyAppBackupFile.IconsFile(token)
            else -> LegacyAppBackupFile.Blob(token, name)
        }
        return delegate.save(handle)
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        val handle = when (name) {
            FILE_BACKUP_METADATA -> LegacyAppBackupFile.Metadata(token)
            FILE_BACKUP_ICONS -> LegacyAppBackupFile.IconsFile(token)
            else -> LegacyAppBackupFile.Blob(token, name)
        }
        return delegate.load(handle)
    }

    @Throws(IOException::class)
    override suspend fun removeData(token: Long, name: String) {
        val handle = when (name) {
            FILE_BACKUP_METADATA -> LegacyAppBackupFile.Metadata(token)
            FILE_BACKUP_ICONS -> LegacyAppBackupFile.IconsFile(token)
            else -> LegacyAppBackupFile.Blob(token, name)
        }
        delegate.remove(handle)
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>? {
        return try {
            // get all restore set tokens in root folder that have a metadata file
            val tokens = ArrayList<Long>()
            delegate.list(null, LegacyAppBackupFile.Metadata::class) { fileInfo ->
                val handle = fileInfo.fileHandle as LegacyAppBackupFile.Metadata
                tokens.add(handle.token)
            }
            val tokenIterator = tokens.iterator()
            return generateSequence {
                if (!tokenIterator.hasNext()) return@generateSequence null // end sequence
                val token = tokenIterator.next()
                EncryptedMetadata(token) {
                    getInputStream(token, FILE_BACKUP_METADATA)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available backups: ", e)
            null
        }
    }

    suspend fun removeAll() = delegate.removeAll()

    override val providerPackageName: String? get() = delegate.providerPackageName

}
