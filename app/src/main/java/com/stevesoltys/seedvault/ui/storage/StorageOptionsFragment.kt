package com.stevesoltys.seedvault.ui.storage

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.annotation.SuppressLint
import android.app.Activity.RESULT_FIRST_USER
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
import com.stevesoltys.seedvault.ui.storage.StorageOption.SafOption
import org.koin.androidx.viewmodel.ext.android.getSharedViewModel

internal class StorageOptionsFragment : Fragment(), StorageOptionClickedListener {

    companion object {
        @RequiresPermission(MANAGE_DOCUMENTS)
        fun newInstance(isRestore: Boolean): StorageOptionsFragment {
            val f = StorageOptionsFragment()
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
    private lateinit var listView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var skipView: TextView

    private val adapter by lazy { StorageOptionAdapter(viewModel.isRestoreOperation, this) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_storage_root, container, false)

        titleView = v.requireViewById(R.id.titleView)
        warningIcon = v.requireViewById(R.id.warningIcon)
        warningText = v.requireViewById(R.id.warningText)
        listView = v.requireViewById(R.id.listView)
        progressBar = v.requireViewById(R.id.progressBar)
        skipView = v.requireViewById(R.id.skipView)

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
        }

        listView.adapter = adapter

        viewModel.storageOptions.observe(viewLifecycleOwner) { roots ->
            onRootsLoaded(roots)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.loadStorageRoots()
    }

    private fun onRootsLoaded(roots: List<StorageOption>) {
        progressBar.visibility = INVISIBLE
        adapter.setItems(roots)
    }

    private val openDocumentTree = registerForActivityResult(OpenSeedvaultTree()) { uri ->
        viewModel.onUriPermissionResultReceived(uri)
    }

    override fun onClick(storageOption: StorageOption) {
        if (storageOption is SafOption) {
            viewModel.onSafOptionChosen(storageOption)
            openDocumentTree.launch(storageOption.uri)
        } else {
            throw IllegalArgumentException("Non-SAF storage not yet supported")
        }
    }

}

internal interface StorageOptionClickedListener {
    fun onClick(storageOption: StorageOption)
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
