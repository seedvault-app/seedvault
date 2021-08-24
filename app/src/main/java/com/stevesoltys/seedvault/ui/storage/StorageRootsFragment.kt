package com.stevesoltys.seedvault.ui.storage

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.app.Activity.RESULT_FIRST_USER
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_RESTORE
import org.koin.androidx.viewmodel.ext.android.getSharedViewModel

internal class StorageRootsFragment : Fragment(), StorageRootClickedListener {

    companion object {
        @RequiresPermission(MANAGE_DOCUMENTS)
        fun newInstance(isRestore: Boolean): StorageRootsFragment {
            val f = StorageRootsFragment()
            f.arguments = Bundle().apply {
                putBoolean(INTENT_EXTRA_IS_RESTORE, isRestore)
            }
            return f
        }
    }

    private lateinit var viewModel: StorageViewModel
    private lateinit var titleView: TextView
    private lateinit var warningIcon: ImageView
    private lateinit var warningText: TextView
    private lateinit var divider: View
    private lateinit var listView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var skipView: TextView

    private val adapter by lazy { StorageRootAdapter(viewModel.isRestoreOperation, this) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_storage_root, container, false)

        titleView = v.findViewById(R.id.titleView)
        warningIcon = v.findViewById(R.id.warningIcon)
        warningText = v.findViewById(R.id.warningText)
        divider = v.findViewById(R.id.divider)
        listView = v.findViewById(R.id.listView)
        progressBar = v.findViewById(R.id.progressBar)
        skipView = v.findViewById(R.id.skipView)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = if (requireArguments().getBoolean(INTENT_EXTRA_IS_RESTORE)) {
            getSharedViewModel<RestoreStorageViewModel>()
        } else {
            getSharedViewModel<BackupStorageViewModel>()
        }

        if (viewModel.isRestoreOperation) {
            titleView.text = getString(R.string.storage_fragment_restore_title)
            skipView.visibility = VISIBLE
            skipView.setOnClickListener {
                // Equivalent to com.google.android.setupcompat.util.ResultCodes.RESULT_SKIP
                // SetupWizard handles this
                requireActivity().setResult(RESULT_FIRST_USER)
                requireActivity().finishAfterTransition()
            }
        } else {
            warningIcon.visibility = VISIBLE
            if (viewModel.hasStorageSet) {
                warningText.setText(R.string.storage_fragment_warning_delete)
            }
            warningText.visibility = VISIBLE
            divider.visibility = VISIBLE
        }

        listView.adapter = adapter

        viewModel.storageRoots.observe(viewLifecycleOwner, { roots ->
            onRootsLoaded(roots)
        })
    }

    override fun onStart() {
        super.onStart()
        viewModel.loadStorageRoots()
    }

    private fun onRootsLoaded(roots: List<StorageRoot>) {
        progressBar.visibility = INVISIBLE
        adapter.setItems(roots)
    }

    private val openDocumentTree = registerForActivityResult(OpenSeedvaultTree()) { uri ->
        viewModel.onUriPermissionResultReceived(uri)
    }

    override fun onClick(root: StorageRoot) {
        viewModel.onStorageRootChosen(root)
        openDocumentTree.launch(root.uri)
    }

}

internal interface StorageRootClickedListener {
    fun onClick(root: StorageRoot)
}

private class OpenSeedvaultTree : OpenDocumentTree() {
    @SuppressLint("MissingSuperCall") // we are intentionally creating our own intent
    override fun createIntent(context: Context, input: Uri?): Intent {
        return Intent(context, PermissionGrantActivity::class.java).apply {
            check(input != null) { "Uri was null, but is needed." }
            data = input
            val flags = FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
            addFlags(flags)
        }
    }
}
