package org.calyxos.backup.storage.plugin.saf

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.calyxos.backup.storage.api.StoragePlugin
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

private val chunkFolderRegex = Regex("[a-f0-9]{2}")
private val chunkRegex = Regex("[a-f0-9]{64}")
private val snapshotRegex = Regex("([0-9]{13})\\.SeedSnap") // good until the year 2286
private const val CHUNK_FOLDER_COUNT = 256
private const val MIME_TYPE: String = "application/octet-stream"

private const val TAG = "SafStoragePlugin"

@Suppress("BlockingMethodInNonBlockingContext")
public abstract class SafStoragePlugin(
    private val context: Context,
) : StoragePlugin {

    protected abstract val root: DocumentFile?

    private val contentResolver = context.contentResolver

    private val chunkFolders = HashMap<String, DocumentFile>(CHUNK_FOLDER_COUNT)
    private val snapshotFiles = HashMap<Long, DocumentFile>()

    private fun timestampToSnapshot(timestamp: Long): String {
        return "${timestamp}.SeedSnap"
    }

    @Throws(IOException::class)
    override suspend fun getAvailableChunkIds(): List<String> {
        val root = root ?: return emptyList()
        val chunkIds = ArrayList<String>()
        populateChunkFolders(root) { file, name ->
            if (chunkFolderRegex.matches(name)) {
                chunkIds.addAll(getChunksFromFolder(file))
            }
        }
        Log.e(TAG, "Got ${chunkIds.size} available chunks")
        return chunkIds
    }

    @Throws(IOException::class)
    private suspend fun populateChunkFolders(
        root: DocumentFile,
        fileOp: ((DocumentFile, String) -> Unit)? = null
    ) {
        val expectedChunkFolders = (0x00..0xff).map {
            Integer.toHexString(it).padStart(2, '0')
        }.toHashSet()
        val duration = measure {
            for (file in root.listFilesBlocking(context)) {
                val name = file.name ?: continue
                if (chunkFolderRegex.matches(name)) {
                    chunkFolders[name] = file
                    expectedChunkFolders.remove(name)
                }
                fileOp?.invoke(file, name)
            }
        }
        Log.e(TAG, "Retrieving chunk folders took $duration")
        createMissingChunkFolders(root, expectedChunkFolders)
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
    private fun createMissingChunkFolders(root: DocumentFile, expectedChunkFolders: Set<String>) {
        val s = expectedChunkFolders.size
        val duration = measure {
            for ((i, chunkFolderName) in expectedChunkFolders.withIndex()) {
                val file = root.createDirectoryOrThrow(chunkFolderName)
                chunkFolders[chunkFolderName] = file
                Log.d(TAG, "Created missing folder $chunkFolderName (${i + 1}/$s)")
            }
            if (chunkFolders.size != 256) throw IOException("Only have ${chunkFolders.size} chunk folders.")
        }
        if (s > 0) Log.e(TAG, "Creating $s missing chunk folders took $duration")
    }

    @Throws(IOException::class)
    override fun getChunkOutputStream(chunkId: String): OutputStream {
        val chunkFolderName = chunkId.substring(0, 2)
        val chunkFolder = chunkFolders[chunkFolderName] ?: error("No folder for chunk $chunkId")
        // TODO should we check if it exists first?
        val chunkFile = chunkFolder.createFileOrThrow(chunkId, MIME_TYPE)
        return chunkFile.getOutputStream(context.contentResolver)
    }

    @Throws(IOException::class)
    override fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        val root = root ?: throw IOException()
        val name = timestampToSnapshot(timestamp)
        // TODO should we check if it exists first?
        val snapshotFile = root.createFileOrThrow(name, MIME_TYPE)
        return snapshotFile.getOutputStream(contentResolver)
    }

    /************************* Restore *******************************/

    @Throws(IOException::class)
    override suspend fun getAvailableBackupSnapshots(): List<Long> {
        val root = root ?: return emptyList()
        val snapshots = ArrayList<Long>()

        populateChunkFolders(root) { file, name ->
            val match = snapshotRegex.matchEntire(name)
            if (match != null) {
                val timestamp = match.groupValues[1].toLong()
                snapshots.add(timestamp)
                snapshotFiles[timestamp] = file
            }
        }
        Log.e(TAG, "Got ${snapshots.size} snapshots while populating chunk folders")
        return snapshots
    }

    @Throws(IOException::class)
    override suspend fun getBackupSnapshotInputStream(timestamp: Long): InputStream {
        val snapshotFile = snapshotFiles.getOrElse(timestamp) {
            root?.findFileBlocking(context, timestampToSnapshot(timestamp))
        } ?: throw IOException("Could not get file for snapshot $timestamp")
        return snapshotFile.getInputStream(contentResolver)
    }

    @Throws(IOException::class)
    override suspend fun getChunkInputStream(chunkId: String): InputStream {
        if (chunkFolders.size < CHUNK_FOLDER_COUNT) {
            val root = root ?: throw IOException("Could not get root")
            populateChunkFolders(root)
        }
        val chunkFolderName = chunkId.substring(0, 2)
        val chunkFolder = chunkFolders[chunkFolderName]
            ?: throw IOException("No folder for chunk $chunkId")
        val chunkFile = chunkFolder.findFileBlocking(context, chunkId)
            ?: throw IOException("No chunk $chunkId")
        return chunkFile.getInputStream(context.contentResolver)
    }

    @Throws(IOException::class)
    override suspend fun deleteBackupSnapshot(timestamp: Long) {
        Log.d(TAG, "Deleting snapshot $timestamp")
        val snapshotFile = snapshotFiles.getOrElse(timestamp) {
            root?.findFileBlocking(context, timestampToSnapshot(timestamp))
        } ?: throw IOException("Could not get file for snapshot $timestamp")
        if (!snapshotFile.delete()) throw IOException("Could not delete snapshot $timestamp")
    }

    override suspend fun deleteChunks(chunkIds: List<String>) {
        if (chunkFolders.size < CHUNK_FOLDER_COUNT) {
            val root = root ?: throw IOException("Could not get root")
            populateChunkFolders(root)
        }
        for (chunkId in chunkIds) {
            Log.d(TAG, "Deleting chunk $chunkId")
            val chunkFolderName = chunkId.substring(0, 2)
            val chunkFolder = chunkFolders[chunkFolderName]
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
