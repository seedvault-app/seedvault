package com.stevesoltys.seedvault.ui.storage

import android.Manifest.permission.MANAGE_DOCUMENTS
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.annotation.CallSuper
import androidx.appcompat.app.AlertDialog
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.BackupActivity
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_RESTORE
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_SETUP_WIZARD
import org.koin.androidx.viewmodel.ext.android.getViewModel

private val TAG = StorageActivity::class.java.name

class StorageActivity : BackupActivity() {

    private lateinit var viewModel: StorageViewModel

    /**
     * The official way to get a SAF [Uri] which we only use if we don't have the
     * [MANAGE_DOCUMENTS] permission (via [canUseStorageRootsFragment]).
     */
    private val openDocumentTree = registerForActivityResult(OpenPersistableDocumentTree()) { uri ->
        if (uri != null) {
            Log.e(TAG, "OpenDocumentTree: $uri")
            val authority = uri.authority ?: throw AssertionError("No authority in $uri")
            val storageRoot = StorageRootResolver.getStorageRoots(this, authority).getOrNull(0)
            if (storageRoot == null) {
                viewModel.onUriPermissionResultReceived(null)
            } else {
                viewModel.onSafOptionChosen(storageRoot)
                viewModel.onUriPermissionResultReceived(uri)
            }
        }
    }

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        viewModel = if (isRestore()) {
            getViewModel<RestoreStorageViewModel>()
        } else {
            getViewModel<BackupStorageViewModel>()
        }
        viewModel.isSetupWizard = isSetupWizard()

        viewModel.locationSet.observeEvent(this, {
            showFragment(StorageCheckFragment.newInstance(getCheckFragmentTitle()), true)
        })

        viewModel.locationChecked.observeEvent(this, { result ->
            val errorMsg = result.errorMsg
            if (errorMsg == null) {
                setResult(RESULT_OK)
                finishAfterTransition()
            } else {
                onInvalidLocation(errorMsg)
            }
        })

        if (savedInstanceState == null) {
            if (canUseStorageRootsFragment()) {
                showFragment(StorageOptionsFragment.newInstance(isRestore()))
            } else {
                openDocumentTree.launch(null)
            }
        }
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            Log.d(TAG, "Blocking back button.")
        } else {
            super.onBackPressed()
        }
    }

    private fun onInvalidLocation(errorMsg: String) {
        if (viewModel.isRestoreOperation) {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_invalid_location_title))
                .setMessage(errorMsg)
                .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            if (canUseStorageRootsFragment()) {
                // We have permission to use StorageRootsFragment,
                // so pop the back stack to show it again
                supportFragmentManager.popBackStack()
                dialog.setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
            } else {
                // We don't have permission to use StorageRootsFragment,
                // so give option to choose again or cancel.
                dialog.setPositiveButton(android.R.string.ok) { _, _ ->
                    openDocumentTree.launch(null)
                }
                dialog.setNegativeButton(android.R.string.cancel) { _, _ ->
                    finishAfterTransition()
                }
            }
            dialog.show()
        } else {
            // just show error message, if this isn't restore
            showFragment(StorageCheckFragment.newInstance(getCheckFragmentTitle(), errorMsg))
        }
    }

    private fun isRestore(): Boolean {
        return intent?.getBooleanExtra(INTENT_EXTRA_IS_RESTORE, false) ?: false
    }

    private fun isSetupWizard(): Boolean {
        return intent?.getBooleanExtra(INTENT_EXTRA_IS_SETUP_WIZARD, false) ?: false
    }

    private fun canUseStorageRootsFragment(): Boolean {
        return checkSelfPermission(MANAGE_DOCUMENTS) == PERMISSION_GRANTED
    }

    private fun getCheckFragmentTitle() = if (viewModel.isRestoreOperation) {
        getString(R.string.storage_check_fragment_restore_title)
    } else {
        getString(R.string.storage_check_fragment_backup_title)
    }

}

private class OpenPersistableDocumentTree : OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).apply {
            val flags = FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
            addFlags(flags)
        }
    }
}
