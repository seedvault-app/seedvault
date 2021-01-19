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
