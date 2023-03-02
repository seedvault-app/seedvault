/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.scanner

import android.net.Uri
import android.os.Bundle

class DocumentScanFragment : MediaScanFragment() {

    companion object {
        fun newInstance(name: String, uri: Uri) = DocumentScanFragment().apply {
            arguments = Bundle().apply {
                putString("name", name)
                putString("uri", uri.toString())
            }
        }
    }

    override suspend fun getText(): String {
        return viewModel.scanDocumentUri(getUri())
    }

}
