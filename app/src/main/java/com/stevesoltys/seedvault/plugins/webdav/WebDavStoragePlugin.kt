/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.util.Log
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.worker.FILE_BACKUP_ICONS
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.webdav.WebDavBackend
import org.calyxos.seedvault.core.backends.webdav.WebDavConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class WebDavStoragePlugin(
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) : WebDavStorage(webDavConfig, root), StoragePlugin<WebDavConfig> {

    private val delegate = WebDavBackend(webDavConfig, root)

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
        return delegate.save(handle).outputStream()
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        val handle = when (name) {
            FILE_BACKUP_METADATA -> LegacyAppBackupFile.Metadata(token)
            FILE_BACKUP_ICONS -> LegacyAppBackupFile.IconsFile(token)
            else -> LegacyAppBackupFile.Blob(token, name)
        }
        return delegate.load(handle).inputStream()
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
        } catch (e: Throwable) { // NoClassDefFound isn't an [Exception], can get thrown by dav4jvm
            Log.e(TAG, "Error getting available backups: ", e)
            null
        }
    }

    override val providerPackageName: String? = null // 100% built-in plugin

}
