/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.restore

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.lifecycle.MutableLiveData
import de.grobox.storagebackuptester.backup.getSpeed
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.restore.NotificationRestoreObserver
import kotlin.time.DurationUnit
import kotlin.time.toDuration

data class RestoreProgress(
    val current: Int,
    val total: Int,
    val text: String? = null,
)

class RestoreStats(
    private val context: Context,
    private val liveData: MutableLiveData<RestoreProgress>,
) : NotificationRestoreObserver(context) {

    private var filesProcessed: Int = 0
    private var totalFiles: Int = 0
    private var size: Long = 0L
    private val errorStrings = ArrayList<String>()

    override fun onRestoreStart(numFiles: Int, totalSize: Long) {
        super.onRestoreStart(numFiles, totalSize)
        totalFiles = numFiles

        val totalSizeStr = Formatter.formatShortFileSize(context, totalSize)
        val text = "Restoring $totalFiles file(s) $totalSizeStr...\n"
        liveData.postValue(RestoreProgress(filesProcessed, totalFiles, text))
    }

    override fun onFileDuplicatesRemoved(num: Int) {
        // no-op
    }

    override fun onFileRestored(
        file: BackupFile,
        bytesWritten: Long,
        tag: String,
    ) {
        super.onFileRestored(file, bytesWritten, tag)
        filesProcessed++
        size += bytesWritten

        val sizeStr = Formatter.formatShortFileSize(context, file.size)
        val now = System.currentTimeMillis()
        val modStr = file.lastModified?.let {
            DateUtils.getRelativeTimeSpanString(it, now, 0L, DateUtils.FORMAT_ABBREV_ALL)
        } ?: "NULL"

        val volume = if (file.volume == "") "" else "v: ${file.volume}"
        val text = "${file.path}\n   s: $sizeStr m: $modStr $tag $volume"
        liveData.postValue(RestoreProgress(filesProcessed, totalFiles, text))
    }

    override fun onFileRestoreError(file: BackupFile, e: Exception) {
        super.onFileRestoreError(file, e)
        filesProcessed++
        errorStrings.add("E ${file.path}\n   e: ${e.message}")
        liveData.postValue(RestoreProgress(filesProcessed, totalFiles))
    }

    override fun onRestoreComplete(restoreDuration: Long) {
        super.onRestoreComplete(restoreDuration)
        val sb = StringBuilder("\n")
        errorStrings.forEach { sb.appendLine(it) }
        sb.appendLine()

        val sizeStr = Formatter.formatShortFileSize(context, size)
        val speed = getSpeed(size, restoreDuration / 1000)

        val duration = restoreDuration.toDuration(DurationUnit.MILLISECONDS)
        val perFile = if (filesProcessed > 0) duration.div(filesProcessed) else duration

        sb.appendLine("Files restored: $filesProcessed ($sizeStr)")
        if (filesProcessed > 0) sb.append("  ($perFile per file - ${speed}MB/sec)")
        liveData.postValue(RestoreProgress(filesProcessed, totalFiles, sb.toString()))
    }

}
