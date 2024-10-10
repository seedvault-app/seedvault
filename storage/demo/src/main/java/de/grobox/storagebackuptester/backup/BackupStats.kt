/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.backup

import android.content.Context
import android.provider.MediaStore
import android.text.format.DateUtils.FORMAT_ABBREV_ALL
import android.text.format.DateUtils.getRelativeTimeSpanString
import android.text.format.Formatter
import android.util.Log
import androidx.lifecycle.MutableLiveData
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.backup.NotificationBackupObserver
import kotlin.time.DurationUnit
import kotlin.time.toDuration

data class BackupProgress(
    val current: Int,
    val total: Int,
    val text: String?,
)

internal class BackupStats(
    private val context: Context,
    private val storageBackup: StorageBackup,
    private val liveData: MutableLiveData<BackupProgress>,
) : NotificationBackupObserver(context) {
    private var filesProcessed: Int = 0
    private var totalFiles: Int = 0
    private var filesUploaded: Int = 0
    private var expectedSize: Long = 0L
    private var size: Long = 0L
    private var savedChunks: Int = 0
    private val errorStrings = ArrayList<String>()

    override suspend fun onBackupStart(
        totalSize: Long,
        numFiles: Int,
        numSmallFiles: Int,
        numLargeFiles: Int,
    ) {
        super.onBackupStart(totalSize, numFiles, numSmallFiles, numLargeFiles)

        totalFiles = numFiles
        expectedSize = totalSize

        val totalSizeStr = Formatter.formatShortFileSize(context, totalSize)
        val text = "Backing up $totalFiles file(s) $totalSizeStr...\n" +
            "  ($numSmallFiles small, $numLargeFiles large)\n"
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, text))
    }

    override suspend fun onFileBackedUp(
        file: BackupFile,
        wasUploaded: Boolean,
        reusedChunks: Int,
        bytesWritten: Long,
        tag: String,
    ) {
        super.onFileBackedUp(file, wasUploaded, reusedChunks, bytesWritten, tag)

        filesProcessed++
        if (!wasUploaded) return

        savedChunks += reusedChunks
        filesUploaded++
        size += bytesWritten

        val sizeStr = Formatter.formatShortFileSize(context, file.size)
        val now = System.currentTimeMillis()
        val modStr = file.lastModified?.let {
            getRelativeTimeSpanString(it, now, 0L, FORMAT_ABBREV_ALL)
        } ?: "NULL"

        val volume =
            if (file.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "" else "v: ${file.volume}"
        val text = "${file.path}\n   s: $sizeStr m: $modStr $volume"
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, text))
    }

    override suspend fun onFileBackupError(file: BackupFile, tag: String) {
        super.onFileBackupError(file, tag)

        filesProcessed++
        errorStrings.add("ERROR $tag: ${file.path}")
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, null))
    }

    override suspend fun onBackupComplete(backupDuration: Long?) {
        super.onBackupComplete(backupDuration)

        val sb = StringBuilder("\n")
        errorStrings.forEach { sb.appendLine(it) }
        sb.appendLine()

        if (savedChunks > 0) sb.appendLine("Chunks re-used: $savedChunks")

        Log.e("TEST", "Total file size: $expectedSize")
        Log.e("TEST", "Actual size processed: $size")

        val sizeStr = Formatter.formatShortFileSize(context, size)
        if (backupDuration != null) {
            val speed = getSpeed(size, backupDuration / 1000)

            val duration = backupDuration.toDuration(DurationUnit.MILLISECONDS)
            val perFile = if (filesUploaded > 0) duration.div(filesUploaded) else duration

            sb.appendLine("New/changed files backed up: $filesUploaded ($sizeStr)")
            if (filesUploaded > 0) sb.append("  ($perFile per file - ${speed}MB/sec)")
        }
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, sb.toString()))
    }

    override suspend fun onPruneStart(snapshotsToDelete: List<Long>) {
        super.onPruneStart(snapshotsToDelete)

        filesProcessed = 0
        totalFiles = snapshotsToDelete.size
        size = 0L
        val r = storageBackup.getSnapshotRetention()

        if (totalFiles > 0) {
            val text = """
                Pruning $totalFiles old backup(s) from storage...
                Retaining snapshots:
                  - ${r.daily} daily
                  - ${r.weekly} weekly
                  - ${r.monthly} monthly
                  - ${r.yearly} yearly
                """.trimIndent()
            liveData.postValue(BackupProgress(filesProcessed, totalFiles, text))
        }
    }

    override suspend fun onPruneSnapshot(snapshot: Long, numChunksToDelete: Int, size: Long) {
        super.onPruneSnapshot(snapshot, numChunksToDelete, size)

        filesProcessed++
        this.size += size
        val now = System.currentTimeMillis()
        val time = getRelativeTimeSpanString(snapshot, now, 0L, FORMAT_ABBREV_ALL)
        val sizeStr = Formatter.formatShortFileSize(context, size)
        val text = "Pruning snapshot from $time\n  deleting $numChunksToDelete chunks ($sizeStr)..."
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, text))
    }

    override suspend fun onPruneError(snapshot: Long?, e: Exception) {
        super.onPruneError(snapshot, e)

        val time = if (snapshot != null) {
            filesProcessed++
            val now = System.currentTimeMillis()
            getRelativeTimeSpanString(snapshot, now, 0L, FORMAT_ABBREV_ALL)
        } else "null"
        val text = "ERROR $snapshot $time\n   e: ${e.message}"
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, text))
    }

    override suspend fun onPruneComplete(pruneDuration: Long) {
        super.onPruneComplete(pruneDuration)

        val sb = StringBuilder("\n")

        val sizeStr = Formatter.formatShortFileSize(context, size)

        val duration = pruneDuration.toDuration(DurationUnit.MILLISECONDS)
        val perSnapshot = if (filesProcessed > 0) duration.div(filesProcessed) else duration

        sb.appendLine("Deleting $filesProcessed old backup snapshot(s) took $duration.")
        if (filesProcessed > 0) sb.append("  (freed $sizeStr - took $perSnapshot per snapshot)")
        liveData.postValue(BackupProgress(filesProcessed, totalFiles, sb.toString()))
    }

}

fun getSpeed(size: Long, duration: Long): Long {
    val mb = size / 1024 / 1024
    return if (duration == 0L) (mb.toDouble() / 0.01).toLong()
    else mb / duration
}
