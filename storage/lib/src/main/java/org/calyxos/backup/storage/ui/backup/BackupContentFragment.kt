package org.calyxos.backup.storage.ui.backup

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.calyxos.backup.storage.R
import org.calyxos.backup.storage.api.BackupContentType
import org.calyxos.backup.storage.api.EXTERNAL_STORAGE_PROVIDER_AUTHORITY

internal interface ContentClickListener {
    fun onContentClicked(view: View, item: BackupContentItem)
    fun onMediaContentEnabled(item: BackupContentItem, enabled: Boolean): Boolean
    fun onFolderOverflowClicked(view: View, item: BackupContentItem)
}

public class OpenTree : OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).apply {
            addFlags(
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION or
                    FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }
}

public abstract class BackupContentFragment : Fragment(), ContentClickListener {

    protected abstract val viewModel: BackupContentViewModel

    private val addRequest = registerForActivityResult(OpenTree()) { uri ->
        onTreeUriReceived(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_backup_content, container, false)
        val list: RecyclerView = v.findViewById(R.id.list)

        val adapter = BackupContentAdapter(this)
        list.adapter = adapter
        viewModel.content.observe(viewLifecycleOwner) {
            adapter.setItems(it)
        }
        v.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            addRequest.launch(DocumentsContract.buildRootsUri(EXTERNAL_STORAGE_PROVIDER_AUTHORITY))
        }
        return v
    }

    override fun onContentClicked(view: View, item: BackupContentItem): Unit = when {
        item.contentType == BackupContentType.Custom -> {
            onFolderOverflowClicked(view, item)
        }
        item.enabled -> {
            viewModel.removeUri(item.uri)
        }
        else -> {
            viewModel.addUri(item.uri)
        }
    }

    override fun onMediaContentEnabled(item: BackupContentItem, enabled: Boolean): Boolean {
        if (enabled) {
            viewModel.addUri(item.uri)
        } else {
            viewModel.removeUri(item.uri)
        }
        return enabled
    }

    override fun onFolderOverflowClicked(view: View, item: BackupContentItem) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.item_custom, popup.menu)
        popup.setOnMenuItemClickListener {
            if (it.itemId == R.id.delete) {
                viewModel.removeUri(item.uri)
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun onTreeUriReceived(uri: Uri?) {
        if (uri?.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addUri(uri)
        } else {
            Toast.makeText(requireContext(), "Backup not supported for location", LENGTH_LONG)
                .show()
        }
    }

}
