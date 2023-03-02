/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.plugin.saf

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.provider.Settings.Secure.ANDROID_ID
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.measure
import org.calyxos.backup.storage.plugin.saf.DocumentFileExt.createDirectoryOrThrow
import org.calyxos.backup.storage.plugin.saf.DocumentFileExt.createFileOrThrow
import org.calyxos.backup.storage.plugin.saf.DocumentFileExt.findFileBlocking
import org.calyxos.backup.storage.plugin.saf.DocumentFileExt.getInputStream
import org.calyxos.backup.storage.plugin.saf.DocumentFileExt.getOutputStream
import org.calyxos.backup.storage.plugin.saf.DocumentFileExt.listFilesBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.ExperimentalTime

private val folderRegex = Regex("^[a-f0-9]{16}\\.sv$")
private val chunkFolderRegex = Regex("[a-f0-9]{2}")
private val chunkRegex = Regex("[a-f0-9]{64}")
private val snapshotRegex = Regex("([0-9]{13})\\.SeedSnap") // good until the year 2286
private const val MIME_TYPE: String = "application/octet-stream"
internal const val CHUNK_FOLDER_COUNT = 256

private const val TAG = "SafStoragePlugin"

/**
 * @param appContext application context provided by the storage module
 */
@Suppress("BlockingMethodInNonBlockingContext")
public abstract class SafStoragePlugin(
    private val appContext: Context,
) : StoragePlugin {
    /**
     * Attention: This context could be unexpected. E.g. the system user's application context,
     * in the case of USB storage, if INTERACT_ACROSS_USERS_FULL permission is granted.
     * Use [appContext], if you need the context of the current app and user
     * and [context] for all file access.
     */
    protected abstract val context: Context
    protected abstract val root: DocumentFile?
    private val cache = SafCache()

    private val folder: DocumentFile?
        get() {
            val root = this.root ?: return null
            if (cache.currentFolder != null) return cache.currentFolder

            @SuppressLint("HardwareIds")
            // This is unique to each combination of app-signing key, user, and device
            // so we don't leak anything by not hashing this and can use it as is.
            // Note: Use [appContext] here to not get the wrong ID for a different user.
            val androidId = Settings.Secure.getString(appContext.contentResolver, ANDROID_ID)
            // the folder name is our user ID
            val folderName = "$androidId.sv"
            cache.currentFolder = try {
                root.findFile(folderName) ?: root.createDirectoryOrThrow(folderName)
            } catch (e: IOException) {
                Log.e(TAG, "Error creating storage folder $folderName")
                null
            }
            return cache.currentFolder
        }

    private fun timestampToSnapshot(timestamp: Long): String {
        return "$timestamp.SeedSnap"
    }

    @Throws(IOException::class)
    override suspend fun getAvailableChunkIds(): List<String> {
        val folder = folder ?: return emptyList()
        val chunkIds = ArrayList<String>()
        populateChunkFolders(folder, cache.backupChunkFolders) { file, name ->
            if (chunkFolderRegex.matches(name)) {
                chunkIds.addAll(getChunksFromFolder(file))
            }
        }
        Log.i(TAG, "Got ${chunkIds.size} available chunks")
        return chunkIds
    }

    /**
     * Goes through all files in the given [folder] and performs the optional [fileOp] on them.
     * Afterwards, it creates missing chunk folders, as needed.
     * Chunk folders will get cached in the given [chunkFolders] for faster access.
     */
    @Throws(IOException::class)
    @OptIn(ExperimentalTime::class)
    private suspend fun populateChunkFolders(
        folder: DocumentFile,
        chunkFolders: HashMap<String, DocumentFile>,
        fileOp: ((DocumentFile, String) -> Unit)? = null,
    ) {
        val expectedChunkFolders = (0x00..0xff).map {
            Integer.toHexString(it).padStart(2, '0')
        }.toHashSet()
        val duration = measure {
            for (file in folder.listFilesBlocking(context)) {
                val name = file.name ?: continue
                if (chunkFolderRegex.matches(name)) {
                    chunkFolders[name] = file
                    expectedChunkFolders.remove(name)
                }
                fileOp?.invoke(file, name)
            }
        }
        Log.i(TAG, "Retrieving chunk folders took $duration")
        createMissingChunkFolders(folder, chunkFolders, expectedChunkFolders)
    }

    @Throws(IOException::class)
    private fun getChunksFromFolder(chunkFolder: DocumentFile): List<String> {
        val chunkFiles = try {
            chunkFolder.listFiles()
        } catch (e: UnsupportedOperationException) {
            // can happen if this wasn't a directory after all
            throw IOException(e)
        }
        return chunkFiles.mapNotNull { chunkFile ->
            val name = chunkFile.name ?: return@mapNotNull null
            if (chunkRegex.matches(name)) name else null
        }
    }

    @Throws(IOException::class)
    @OptIn(ExperimentalTime::class)
    private fun createMissingChunkFolders(
        root: DocumentFile,
        chunkFolders: HashMap<String, DocumentFile>,
        expectedChunkFolders: Set<String>,
    ) {
        val s = expectedChunkFolders.size
        val duration = measure {
            for ((i, chunkFolderName) in expectedChunkFolders.withIndex()) {
                val file = root.createDirectoryOrThrow(chunkFolderName)
                chunkFolders[chunkFolderName] = file
                Log.d(TAG, "Created missing folder $chunkFolderName (${i + 1}/$s)")
            }
            if (chunkFolders.size != 256) {
                throw IOException("Only have ${chunkFolders.size} chunk folders.")
            }
        }
        if (s > 0) Log.i(TAG, "Creating $s missing chunk folders took $duration")
    }

    @Throws(IOException::class)
    override fun getChunkOutputStream(chunkId: String): OutputStream {
        val chunkFolderName = chunkId.substring(0, 2)
        val chunkFolder =
            cache.backupChunkFolders[chunkFolderName] ?: error("No folder for chunk $chunkId")
        // TODO should we check if it exists first?
        val chunkFile = chunkFolder.createFileOrThrow(chunkId, MIME_TYPE)
        return chunkFile.getOutputStream(context.contentResolver)
    }

    @Throws(IOException::class)
    override fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        val folder = folder ?: throw IOException()
        val name = timestampToSnapshot(timestamp)
        // TODO should we check if it exists first?
        val snapshotFile = folder.createFileOrThrow(name, MIME_TYPE)
        return snapshotFile.getOutputStream(context.contentResolver)
    }

    /************************* Restore *******************************/

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotsForRestore(): List<StoredSnapshot> {
        val snapshots = ArrayList<StoredSnapshot>()

        root?.listFilesBlocking(context)?.forEach { folder ->
            val folderName = folder.name ?: ""
            if (!folderRegex.matches(folderName)) return@forEach

            Log.i(TAG, "Checking $folderName for snapshots...")
            for (file in folder.listFilesBlocking(context)) {
                val name = file.name ?: continue
                val match = snapshotRegex.matchEntire(name)
                if (match != null) {
                    val timestamp = match.groupValues[1].toLong()
                    val storedSnapshot = StoredSnapshot(folderName, timestamp)
                    snapshots.add(storedSnapshot)
                    cache.snapshotFiles[storedSnapshot] = file
                }
            }
        }
        Log.i(TAG, "Got ${snapshots.size} snapshots while populating chunk folders")
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotInputStream(storedSnapshot: StoredSnapshot): InputStream {
        val timestamp = storedSnapshot.timestamp
        val snapshotFile = cache.snapshotFiles.getOrElse(storedSnapshot) {
            getFolder(storedSnapshot).findFileBlocking(context, timestampToSnapshot(timestamp))
        } ?: throw IOException("Could not get file for snapshot $timestamp")
        return snapshotFile.getInputStream(context.contentResolver)
    }

    @Throws(IOException::class)
    override suspend fun getChunkInputStream(
        snapshot: StoredSnapshot,
        chunkId: String,
    ): InputStream {
        if (cache.restoreChunkFolders.size < CHUNK_FOLDER_COUNT) {
            populateChunkFolders(getFolder(snapshot), cache.restoreChunkFolders)
        }
        val chunkFolderName = chunkId.substring(0, 2)
        val chunkFolder = cache.restoreChunkFolders[chunkFolderName]
            ?: throw IOException("No folder for chunk $chunkId")
        val chunkFile = chunkFolder.findFileBlocking(context, chunkId)
            ?: throw IOException("No chunk $chunkId")
        return chunkFile.getInputStream(context.contentResolver)
    }

    @Throws(IOException::class)
    private suspend fun getFolder(storedSnapshot: StoredSnapshot): DocumentFile {
        // not cached, because used in several places only once and
        // [getBackupSnapshotInputStream] uses snapshot files cache and
        // [getChunkInputStream] uses restore chunk folders cache
        return root?.findFileBlocking(context, storedSnapshot.userId)
            ?: throw IOException("Could not find snapshot $storedSnapshot")
    }

    /************************* Pruning *******************************/

    @Throws(IOException::class)
    override suspend fun getCurrentBackupSnapshots(): List<StoredSnapshot> {
        val folder = folder ?: return emptyList()
        val folderName = folder.name ?: error("Folder suddenly has no more name")
        val snapshots = ArrayList<StoredSnapshot>()

        populateChunkFolders(folder, cache.backupChunkFolders) { file, name ->
            val match = snapshotRegex.matchEntire(name)
            if (match != null) {
                val timestamp = match.groupValues[1].toLong()
                val storedSnapshot = StoredSnapshot(folderName, timestamp)
                snapshots.add(storedSnapshot)
                cache.snapshotFiles[storedSnapshot] = file
            }
        }
        Log.i(TAG, "Got ${snapshots.size} snapshots while populating chunk folders")
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun deleteBackupSnapshot(storedSnapshot: StoredSnapshot) {
        val timestamp = storedSnapshot.timestamp
        Log.d(TAG, "Deleting snapshot $timestamp")
        val snapshotFile = cache.snapshotFiles.getOrElse(storedSnapshot) {
            getFolder(storedSnapshot).findFileBlocking(context, timestampToSnapshot(timestamp))
        } ?: throw IOException("Could not get file for snapshot $timestamp")
        if (!snapshotFile.delete()) throw IOException("Could not delete snapshot $timestamp")
        cache.snapshotFiles.remove(storedSnapshot)
    }

    @Throws(IOException::class)
    override suspend fun deleteChunks(chunkIds: List<String>) {
        if (cache.backupChunkFolders.size < CHUNK_FOLDER_COUNT) {
            val folder = folder ?: throw IOException("Could not get current folder in root")
            populateChunkFolders(folder, cache.backupChunkFolders)
        }
        for (chunkId in chunkIds) {
            Log.d(TAG, "Deleting chunk $chunkId")
            val chunkFolderName = chunkId.substring(0, 2)
            val chunkFolder = cache.backupChunkFolders[chunkFolderName]
                ?: throw IOException("No folder for chunk $chunkId")
            val chunkFile = chunkFolder.findFileBlocking(context, chunkId)
            if (chunkFile == null) {
                Log.w(TAG, "Could not find $chunkId")
            } else {
                if (!chunkFile.delete()) throw IOException("Could not delete chunk $chunkId")
            }
        }
    }

}
