package com.stevesoltys.seedvault.ui.storage

import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity.RESULT_OK
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_RESTORE
import com.stevesoltys.seedvault.ui.REQUEST_CODE_OPEN_DOCUMENT_TREE
import org.koin.androidx.viewmodel.ext.android.getSharedViewModel

internal class StorageRootsFragment : Fragment(), StorageRootClickedListener {

    companion object {
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
    private lateinit var backView: TextView

    private val adapter by lazy { StorageRootAdapter(viewModel.isRestoreOperation, this) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val v: View = inflater.inflate(R.layout.fragment_storage_root, container, false)

        titleView = v.findViewById(R.id.titleView)
        warningIcon = v.findViewById(R.id.warningIcon)
        warningText = v.findViewById(R.id.warningText)
        divider = v.findViewById(R.id.divider)
        listView = v.findViewById(R.id.listView)
        progressBar = v.findViewById(R.id.progressBar)
        backView = v.findViewById(R.id.backView)

        return v
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        viewModel = if (arguments!!.getBoolean(INTENT_EXTRA_IS_RESTORE)) {
            getSharedViewModel<RestoreStorageViewModel>()
        } else {
            getSharedViewModel<BackupStorageViewModel>()
        }

        if (viewModel.isRestoreOperation) {
            titleView.text = getString(R.string.storage_fragment_restore_title)
            backView.visibility = VISIBLE
            backView.setOnClickListener { requireActivity().finishAfterTransition() }
        } else {
            warningIcon.visibility = VISIBLE
            warningText.visibility = VISIBLE
            divider.visibility = VISIBLE
        }

        listView.adapter = adapter

        viewModel.storageRoots.observe(this, Observer { roots -> onRootsLoaded(roots) })
    }

    override fun onStart() {
        super.onStart()
        viewModel.loadStorageRoots()
    }

    private fun onRootsLoaded(roots: List<StorageRoot>) {
        progressBar.visibility = INVISIBLE
        adapter.setItems(roots)
    }

    override fun onClick(root: StorageRoot) {
        viewModel.onStorageRootChosen(root)
        val intent = Intent(requireContext(), PermissionGrantActivity::class.java)
        intent.data = root.uri
        intent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE) {
            viewModel.onUriPermissionGranted(result)
        } else {
            super.onActivityResult(requestCode, resultCode, result)
        }
    }

}

internal interface StorageRootClickedListener {
    fun onClick(root: StorageRoot)
}
