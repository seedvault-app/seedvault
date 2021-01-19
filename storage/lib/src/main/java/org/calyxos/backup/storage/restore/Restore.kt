package org.calyxos.backup.storage.restore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.api.SnapshotResult
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.backup.Backup
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.crypto.StreamCrypto
import org.calyxos.backup.storage.measure
import org.calyxos.backup.storage.plugin.SnapshotRetriever
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import kotlin.time.ExperimentalTime

private const val TAG = "Restore"

@Suppress("BlockingMethodInNonBlockingContext")
internal class Restore(
    context: Context,
    private val storagePlugin: StoragePlugin,
    private val snapshotRetriever: SnapshotRetriever,
    fileRestore: FileRestore,
    streamCrypto: StreamCrypto = StreamCrypto,
) {

    private val streamKey = try {
        streamCrypto.deriveStreamKey(storagePlugin.getMasterKey())
    } catch (e: GeneralSecurityException) {
        throw AssertionError(e)
    }

    private val zipChunkRestore =
        ZipChunkRestore(storagePlugin, fileRestore, streamCrypto, streamKey)
    private val singleChunkRestore =
        SingleChunkRestore(storagePlugin, fileRestore, streamCrypto, streamKey)
    private val multiChunkRestore =
        MultiChunkRestore(context, storagePlugin, fileRestore, streamCrypto, streamKey)

    fun getBackupSnapshots(): Flow<SnapshotResult> = flow {
        val numSnapshots: Int
        val time = measure {
            val list = try {
                storagePlugin.getAvailableBackupSnapshots().sortedDescending().map {
                    SnapshotItem(it, null)
                }.toMutableList()
            } catch (e: Exception) {
                Log.e("TAG", "Error retrieving snapshots", e)
                emit(SnapshotResult.Error(e))
                return@flow
            }
            // return list copies, so this works with ListAdapter and DiffUtils
            emit(SnapshotResult.Success(ArrayList(list)))
            numSnapshots = list.size
            val iterator = list.listIterator()
            while (iterator.hasNext()) {
                val oldItem = iterator.next()
                val item = try {
                    oldItem.copy(snapshot = snapshotRetriever.getSnapshot(streamKey, oldItem.time))
                } catch (e: Exception) {
                    Log.e("TAG", "Error retrieving snapshot ${oldItem.time}", e)
                    continue
                }
                iterator.set(item)
                emit(SnapshotResult.Success(ArrayList(list)))
            }
        }
        Log.e(TAG, "Decrypting and parsing $numSnapshots snapshots took $time")
    }

    @Throws(IOException::class)
    suspend fun restoreBackupSnapshot(timestamp: Long, observer: RestoreObserver?) {
        val snapshot = snapshotRetriever.getSnapshot(streamKey, timestamp)
        restoreBackupSnapshot(snapshot, observer)
    }

    @OptIn(ExperimentalTime::class)
    @Throws(IOException::class)
    suspend fun restoreBackupSnapshot(snapshot: BackupSnapshot, observer: RestoreObserver?) {
        val filesTotal = snapshot.mediaFilesList.size + snapshot.documentFilesList.size
        val totalSize =
            snapshot.mediaFilesList.sumOf { it.size } + snapshot.documentFilesList.sumOf { it.size }
        observer?.onRestoreStart(filesTotal, totalSize)

        val split = FileSplitter.splitSnapshot(snapshot)
        var restoredFiles = 0
        val smallFilesDuration = measure {
            restoredFiles += zipChunkRestore.restore(split.zipChunks, observer)
        }
        Log.e(TAG, "Restoring ${split.zipChunks.size} zip chunks took $smallFilesDuration.")
        val singleChunkDuration = measure {
            restoredFiles += singleChunkRestore.restore(split.singleChunks, observer)
        }
        Log.e(TAG, "Restoring ${split.singleChunks.size} single chunks took $singleChunkDuration.")
        val multiChunkDuration = measure {
            restoredFiles += multiChunkRestore.restore(
                split.multiChunkMap,
                split.multiChunkFiles,
                observer
            )
        }
        Log.e(TAG, "Restoring ${split.multiChunkFiles.size} multi chunks took $multiChunkDuration.")

        val totalDuration = smallFilesDuration + singleChunkDuration + multiChunkDuration
        observer?.onRestoreComplete(totalDuration.toLongMilliseconds())
        Log.e(TAG, "Restored $restoredFiles/$filesTotal files.")
    }

}

@Throws(IOException::class)
internal fun InputStream.readVersion() {
    val version = read()
    if (version == -1) throw IOException()
    if (version > Backup.VERSION) {
        // TODO maybe throw a different exception here and tell the user?
        throw IOException()
    }
}
