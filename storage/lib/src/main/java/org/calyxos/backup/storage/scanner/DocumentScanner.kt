/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import android.provider.MediaStore
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import org.calyxos.backup.storage.api.BackupFile
import org.calyxos.backup.storage.content.DocFile
import org.calyxos.backup.storage.getDocumentPath
import org.calyxos.backup.storage.getVolume

public class DocumentScanner(context: Context) {

    private companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        private const val PROJECTION_ID = 0
        private const val PROJECTION_NAME = 1
        private const val PROJECTION_MIME_TYPE = 2
        private const val PROJECTION_LAST_MODIFIED = 3
        private const val PROJECTION_COLUMN_SIZE = 4
        private const val PROJECTION_COLUMN_FLAGS = 5
    }

    private val contentResolver = context.contentResolver

    public fun scanUri(uri: Uri, maxSize: Long = Long.MAX_VALUE): List<BackupFile> {
        return scanDocumentUri(uri, "", maxSize)
    }

    internal fun scanDocumentUri(
        uri: Uri,
        dir: String = uri.getDocumentPath() ?: "",
        maxSize: Long = Long.MAX_VALUE,
    ): List<DocFile> {
        // http://aosp.opersys.com/xref/android-11.0.0_r8/xref/frameworks/base/core/java/com/android/internal/content/FileSystemProvider.java
        // http://aosp.opersys.com/xref/android-11.0.0_r8/xref/frameworks/base/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java

        val docId = DocumentsContract.getDocumentId(uri)
        val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        val cursor = contentResolver.query(
            queryUri, PROJECTION, null, null, null
        )
        val documentFiles = ArrayList<DocFile>(cursor?.count ?: 0)
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getString(PROJECTION_ID)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                val name = it.getString(PROJECTION_NAME)
                if (it.getStringOrNull(PROJECTION_MIME_TYPE) == MIME_TYPE_DIR) {
                    // don't include directories, but do include their contents
                    val dirPath = "$dir/$name".trimStart('/')
                    documentFiles.addAll(scanDocumentUri(documentUri, dirPath, maxSize))
                    continue
                }
                val flags = it.getInt(PROJECTION_COLUMN_FLAGS)
                val isVirtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                if (isVirtual) continue // don't include virtual files
                val size = it.getLong(PROJECTION_COLUMN_SIZE)
                if (size > maxSize) continue // don't include files larger tha max (for testing)
                val docFile = DocFile(
                    uri = documentUri,
                    dirPath = dir,
                    fileName = name,
                    lastModified = it.getLongOrNull(PROJECTION_LAST_MODIFIED),
                    size = size,
                    volume = documentUri.getVolume() ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
                documentFiles.add(docFile)
            }
        }
        return documentFiles
    }

}
