/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
import android.provider.DocumentsContract.Root.COLUMN_ROOT_ID
import android.util.Log
import androidx.core.database.getIntOrNull
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.getStorageContext
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.chunkFolderRegex
import com.stevesoltys.seedvault.plugins.tokenRegex
import com.stevesoltys.seedvault.ui.storage.AUTHORITY_STORAGE
import com.stevesoltys.seedvault.ui.storage.ROOT_ID_DEVICE
import org.calyxos.backup.storage.plugin.PluginConstants.SNAPSHOT_EXT
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private val TAG = DocumentsProviderStoragePlugin::class.java.simpleName

internal class DocumentsProviderStoragePlugin(
    private val appContext: Context,
    private val storage: DocumentsStorage,
) : StoragePlugin<Uri> {

    /**
     * Attention: This context might be from a different user. Use with care.
     */
    private val context: Context get() = appContext.getStorageContext { storage.safStorage.isUsb }

    private val packageManager: PackageManager = appContext.packageManager

    override suspend fun test(): Boolean {
        val dir = storage.rootBackupDir
        return dir != null && dir.exists()
    }

    override suspend fun getFreeSpace(): Long? {
        val rootId = storage.safStorage.rootId ?: return null
        val authority = storage.safStorage.uri.authority
        // using DocumentsContract#buildRootUri(String, String) with rootId directly doesn't work
        val rootUri = DocumentsContract.buildRootsUri(authority)
        val projection = arrayOf(COLUMN_AVAILABLE_BYTES)
        // query directly for our rootId
        val bytesAvailable = context.contentResolver.query(
            rootUri, projection, "$COLUMN_ROOT_ID=?", arrayOf(rootId), null
        )?.use { c ->
            if (!c.moveToNext()) return@use null // no results
            val bytes = c.getIntOrNull(c.getColumnIndex(COLUMN_AVAILABLE_BYTES))
            if (bytes != null && bytes >= 0) return@use bytes.toLong()
            else return@use null
        }
        // if we didn't get anything from SAF, try some known hacks
        return if (bytesAvailable == null && authority == AUTHORITY_STORAGE) {
            if (rootId == ROOT_ID_DEVICE) {
                StatFs(Environment.getDataDirectory().absolutePath).availableBytes
            } else if (storage.safStorage.isUsb) {
                val documentId = storage.safStorage.uri.lastPathSegment ?: return null
                StatFs("/mnt/media_rw/${documentId.trimEnd(':')}").availableBytes
            } else null
        } else bytesAvailable
    }

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        // reset current storage
        storage.reset(token)
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // reset storage without new token, so folders get recreated
        // otherwise stale DocumentFiles will hang around
        storage.reset(null)
    }

    @Throws(IOException::class)
    override suspend fun hasData(token: Long, name: String): Boolean {
        val setDir = storage.getSetDir(token) ?: return false
        return setDir.findFileBlocking(context, name) != null
    }

    @Throws(IOException::class)
    override suspend fun getOutputStream(token: Long, name: String): OutputStream {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.createOrGetFile(context, name)
        return storage.getOutputStream(file)
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.findFileBlocking(context, name) ?: throw FileNotFoundException()
        return storage.getInputStream(file)
    }

    @Throws(IOException::class)
    override suspend fun removeData(token: Long, name: String) {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.findFileBlocking(context, name) ?: return
        if (!file.delete()) throw IOException("Failed to delete $name")
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>? {
        val rootDir = storage.rootBackupDir ?: return null
        val backupSets = getBackups(context, rootDir)
        val iterator = backupSets.iterator()
        return generateSequence {
            if (!iterator.hasNext()) return@generateSequence null // end sequence
            val backupSet = iterator.next()
            EncryptedMetadata(backupSet.token) {
                storage.getInputStream(backupSet.metadataFile)
            }
        }
    }

    override val providerPackageName: String? by lazy {
        val authority = storage.getAuthority() ?: return@lazy null
        val providerInfo = packageManager.resolveContentProvider(authority, 0) ?: return@lazy null
        providerInfo.packageName
    }

}

class BackupSet(val token: Long, val metadataFile: DocumentFile)

internal suspend fun getBackups(context: Context, rootDir: DocumentFile): List<BackupSet> {
    val backupSets = ArrayList<BackupSet>()
    val files = try {
        // block until the DocumentsProvider has results
        rootDir.listFilesBlocking(context)
    } catch (e: IOException) {
        Log.e(TAG, "Error loading backups from storage", e)
        return backupSets
    }
    for (set in files) {
        // retrieve name only once as this causes a DB query
        val name = set.name

        // get current token from set or continue to next file/set
        val token = set.getTokenOrNull(name) ?: continue

        // block until children of set are available
        val metadata = try {
            set.findFileBlocking(context, FILE_BACKUP_METADATA)
        } catch (e: IOException) {
            Log.e(TAG, "Error reading metadata file in backup set folder: $name", e)
            null
        }
        if (metadata == null) {
            Log.w(TAG, "Missing metadata file in backup set folder: $name")
        } else {
            backupSets.add(BackupSet(token, metadata))
        }
    }
    return backupSets
}

private fun DocumentFile.getTokenOrNull(name: String?): Long? {
    val looksLikeToken = name != null && tokenRegex.matches(name)
    // check for isDirectory only if we already have a valid token (causes DB query)
    if (!looksLikeToken || !isDirectory) {
        // only log unexpected output
        if (name != null && isUnexpectedFile(name)) {
            Log.w(TAG, "Found invalid backup set folder: $name")
        }
        return null
    }
    return try {
        name?.toLong()
    } catch (e: NumberFormatException) {
        throw AssertionError(e)
    }
}

private fun isUnexpectedFile(name: String): Boolean {
    return name != FILE_NO_MEDIA &&
        !chunkFolderRegex.matches(name) &&
        !name.endsWith(SNAPSHOT_EXT)
}
