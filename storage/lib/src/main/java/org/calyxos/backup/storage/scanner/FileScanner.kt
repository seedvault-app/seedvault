/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.scanner

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import org.calyxos.backup.storage.api.EXTERNAL_STORAGE_PROVIDER_AUTHORITY
import org.calyxos.backup.storage.backup.Backup.Companion.SMALL_FILE_SIZE_MAX
import org.calyxos.backup.storage.content.ContentFile
import org.calyxos.backup.storage.content.DocFile
import org.calyxos.backup.storage.content.MediaFile
import org.calyxos.backup.storage.db.UriStore
import org.calyxos.backup.storage.measure
import kotlin.time.ExperimentalTime

internal class FileScannerResult(
    val smallFiles: List<ContentFile>,
    val files: List<ContentFile>,
)

internal class FileScanner(
    private val uriStore: UriStore,
    private val mediaScanner: MediaScanner,
    private val documentScanner: DocumentScanner,
    private val smallFileSizeMax: Int = SMALL_FILE_SIZE_MAX,
) {

    companion object {
        private const val TAG = "FileScanner"
        private const val FILES_SMALL = "small"
        private const val FILES_LARGE = "large"
    }

    @OptIn(ExperimentalTime::class)
    fun getFiles(): FileScannerResult {
        // scan both APIs
        val mediaFiles = ArrayList<ContentFile>()
        val docFiles = ArrayList<ContentFile>()
        val scanning = measure {
            uriStore.getStoredUris().forEach { (uri) ->
                when (uri.authority) {
                    MediaStore.AUTHORITY -> {
                        mediaFiles.addAll(getMediaFiles(uri))
                    }
                    EXTERNAL_STORAGE_PROVIDER_AUTHORITY -> {
                        docFiles.addAll(getDocumentFiles(uri))
                    }
                    else -> throw AssertionError("unsupported authority")
                }
            }
        }
        Log.e(TAG, "Scanning took $scanning")

        // remove duplicates from document files (as MediaStore has generation counter)
        val duplicateRemoving = measure {
            val mediaSet = HashSet<String>(mediaFiles.size)
            mediaFiles.forEach { mediaSet.add(it.id) }
            docFiles.removeIf {
                mediaSet.contains(it.id)
            }
        }
        Log.e(TAG, "Removing duplicates took $duplicateRemoving")

        // prep for zip chunks
        val smallFiles: List<ContentFile>
        val largeFiles: List<ContentFile>
        val sizeSorting = measure {
            val filesMap = (mediaFiles + docFiles).groupBy { file ->
                if (file.size > smallFileSizeMax) FILES_LARGE else FILES_SMALL
            }
            val scannerComparator = compareBy<ContentFile> {
                it.lastModified ?: Long.MAX_VALUE
            }
            smallFiles = filesMap[FILES_SMALL]?.sortedWith(scannerComparator) ?: emptyList()
            largeFiles = filesMap[FILES_LARGE]?.sortedWith(scannerComparator) ?: emptyList()
        }
        Log.e(TAG, "Grouping per size took $sizeSorting")

        return FileScannerResult(smallFiles, largeFiles)
    }

    private fun getMediaFiles(uri: Uri): List<MediaFile> {
        return mediaScanner.scanMediaUri(uri)
    }

    private fun getDocumentFiles(uri: Uri): List<DocFile> {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        return documentScanner.scanDocumentUri(documentUri)
    }

}
