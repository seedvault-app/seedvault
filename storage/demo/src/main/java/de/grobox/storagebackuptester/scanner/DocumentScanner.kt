/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.scanner.DocumentScanner
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

@OptIn(ExperimentalTime::class)
fun scanTree(context: Context, documentScanner: DocumentScanner, treeUri: Uri): String {
    val sb = StringBuilder()
    val timedResult = measureTimedValue {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        scanDocument(context, documentScanner.scanUri(documentUri), sb)
    }
    return appendStats(context, sb, timedResult)
}

@SuppressLint("SimpleDateFormat")
private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

fun scanDocument(
    context: Context,
    documentFiles: List<BackupFile>,
    sb: StringBuilder? = null,
): ScanResult {
    var totalSize = 0L
    val warnings = StringBuilder()
    for (documentFile in documentFiles) {
        totalSize += documentFile.size
        val lastModified = documentFile.lastModified?.let {
            dateFormat.format(Date(it))
        }
        sb?.appendLine("┣ ${documentFile.path}")
        sb?.appendLine("┃   lastModified: $lastModified")
        sb?.appendLine("┃   size: ${Formatter.formatShortFileSize(context, documentFile.size)}")

        if (lastModified == null) {
            warnings.appendLine("WARNING: ${documentFile.path} has no lastModified timestamp.")
        }
        if (documentFile.size == 0L && !documentFile.path.endsWith(".nomedia")) {
            warnings.appendLine("WARNING: ${documentFile.path} has no 0 size. Please check if real.")
        }
    }

    if (sb?.isEmpty() == true) sb.appendLine("Empty folder")
    else if (warnings.isNotEmpty()) sb?.appendLine()?.appendLine(warnings)
    return ScanResult(documentFiles.size.toLong(), totalSize)
}
