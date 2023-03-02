/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.scanner.MediaScanner
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.time.ExperimentalTime
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

data class ScanResult(
    var itemsFound: Long,
    var totalSize: Long,
) {
    operator fun plusAssign(other: ScanResult) {
        itemsFound += other.itemsFound
        totalSize += other.totalSize
    }
}

@OptIn(ExperimentalTime::class)
fun scanUri(context: Context, mediaScanner: MediaScanner, uri: Uri): String {
    val sb = StringBuilder()
    val timedResult = measureTimedValue {
        dump(context, mediaScanner.scanUri(uri), sb)
    }
    return appendStats(context, sb, timedResult)
}

@SuppressLint("SimpleDateFormat")
private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

fun dump(context: Context, mediaFiles: List<BackupFile>, sb: StringBuilder? = null): ScanResult {
    var itemsFound = 0L
    var totalSize = 0L
    for (mediaFile in mediaFiles) {
        itemsFound++
        totalSize += mediaFile.size
        val dateModified = mediaFile.lastModified?.let {
            dateFormat.format(Date(it))
        }
        sb?.appendLine(mediaFile.path)
        sb?.appendLine("  modified: $dateModified")
        sb?.appendLine("  size: ${Formatter.formatShortFileSize(context, mediaFile.size)}")
    }

    if (sb?.isEmpty() == true) sb.appendLine("No files found")
    return ScanResult(itemsFound, totalSize)
}

@OptIn(ExperimentalTime::class)
fun appendStats(
    context: Context,
    sb: StringBuilder,
    timedResult: TimedValue<ScanResult>,
    title: String? = null,
): String {
    val result = timedResult.value
    if (title != null || sb.isNotEmpty()) {
        sb.appendLine(title ?: "")
        sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        sb.appendLine()
    }
    sb.appendLine("Scanning took ${timedResult.duration}")
    sb.appendLine("Files found: ${result.itemsFound}")
    sb.appendLine("Total size: ${Formatter.formatShortFileSize(context, result.totalSize)}")
    val avgSize = if (result.itemsFound > 0) result.totalSize / result.itemsFound else 0
    sb.appendLine("Average size: ${Formatter.formatShortFileSize(context, avgSize)}")
    return sb.toString()
}
