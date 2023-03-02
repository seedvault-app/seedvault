/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.settings

import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import de.grobox.storagebackuptester.App
import de.grobox.storagebackuptester.scanner.MediaScanFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.api.EXTERNAL_STORAGE_PROVIDER_AUTHORITY
import org.calyxos.backup.storage.api.mediaUris
import org.calyxos.backup.storage.scanner.DocumentScanner
import org.calyxos.backup.storage.scanner.MediaScanner

class InfoFragment : MediaScanFragment() {

    companion object {
        fun newInstance(name: String) = InfoFragment().apply {
            arguments = Bundle().apply {
                putString("name", name)
            }
        }
    }

    private val storageBackup by lazy { (requireActivity().application as App).storageBackup }
    private val mediaScanner by lazy { MediaScanner(requireContext()) }
    private val documentScanner by lazy { DocumentScanner(requireContext()) }

    override suspend fun getText(): String {
        val sb = StringBuilder()
        val context = requireContext()

        val volumeNames = MediaStore.getExternalVolumeNames(context)
        sb.appendLine("Storage Volumes:")
        for (volumeName in volumeNames) {
            val version = try {
                MediaStore.getVersion(context, volumeName)
            } catch (e: IllegalArgumentException) {
                e.toString()
            }
            val gen = try {
                MediaStore.getGeneration(context, volumeName)
            } catch (e: IllegalArgumentException) {
                e.toString()
            }
            sb.appendLine("  $volumeName")
            sb.appendLine("    version: $version")
            sb.appendLine("    generation:  $gen")
        }
        sb.appendLine()
        sb.appendLine("Media files smaller than 100 KB: ${mediaFilesSmallerThan(100 * 1024)}")
        sb.appendLine("Media files smaller than 500 KB: ${mediaFilesSmallerThan(500 * 1024)}")
        sb.appendLine("Media files smaller than 1 MB: ${mediaFilesSmallerThan(1024 * 1024)}")
        sb.appendLine()
        sb.appendLine("Storage files smaller than 100 KB: ${docFilesSmallerThan(100 * 1024)}")
        sb.appendLine("Storage files smaller than 500 KB: ${docFilesSmallerThan(500 * 1024)}")
        sb.appendLine("Storage files smaller than 1 MB: ${docFilesSmallerThan(1024 * 1024)}")
        return sb.toString()
    }

    private suspend fun mediaFilesSmallerThan(size: Long): Int = withContext(Dispatchers.IO) {
        var count = 0
        mediaUris.forEach {
            count += mediaScanner.scanUri(it, size).size
        }
        count
    }

    private suspend fun docFilesSmallerThan(size: Long): Int = withContext(Dispatchers.IO) {
        var count = 0
        storageBackup.uris.forEach { uri ->
            if (uri.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                count += documentScanner.scanUri(documentUri, size).size
            }
        }
        count
    }

}
