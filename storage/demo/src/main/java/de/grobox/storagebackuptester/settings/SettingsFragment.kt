/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.settings

import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.grobox.storagebackuptester.MainViewModel
import de.grobox.storagebackuptester.R
import de.grobox.storagebackuptester.restore.DemoSnapshotFragment
import de.grobox.storagebackuptester.scanner.DocumentScanFragment
import de.grobox.storagebackuptester.scanner.MediaScanFragment
import org.calyxos.backup.storage.api.MediaType
import org.calyxos.backup.storage.ui.backup.BackupContentFragment
import org.calyxos.backup.storage.ui.backup.BackupContentItem
import org.calyxos.backup.storage.ui.backup.OpenTree

class SettingsFragment : BackupContentFragment() {

    companion object {
        fun newInstance(): SettingsFragment = SettingsFragment()
    }

    override val viewModel: MainViewModel by activityViewModels()
    private lateinit var backupLocationItem: MenuItem
    private lateinit var jobItem: MenuItem
    private lateinit var restoreItem: MenuItem

    private val backupLocationRequest = registerForActivityResult(OpenTree()) { uri ->
        onBackupUriReceived(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        setHasOptionsMenu(true)
        requireActivity().title = "Settings"
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_settings, menu)
        backupLocationItem = menu.findItem(R.id.backup_location)
        backupLocationItem.isChecked = viewModel.hasBackupLocation()
        jobItem = menu.findItem(R.id.backup_job)
        jobItem.isEnabled = backupLocationItem.isChecked
        jobItem.isChecked = viewModel.areAutomaticBackupsEnabled()
        restoreItem = menu.findItem(R.id.restore_backup)
        restoreItem.isEnabled = backupLocationItem.isChecked
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.info -> {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, InfoFragment.newInstance("Media Info"))
                    .addToBackStack("INFO")
                    .commit()
                true
            }
            R.id.backup_location -> {
                if (item.isChecked) {
                    viewModel.setBackupLocation(null)
                    item.isChecked = false
                    jobItem.isEnabled = false
                    restoreItem.isEnabled = false
                } else backupLocationRequest.launch(null)
                true
            }
            R.id.backup_job -> {
                if (item.isChecked) {
                    viewModel.setAutomaticBackupsEnabled(false)
                    item.isChecked = false
                } else {
                    viewModel.setAutomaticBackupsEnabled(true)
                    item.isChecked = true
                }
                true
            }
            R.id.restore_backup -> {
                onRestoreClicked()
                true
            }
            R.id.clear_db -> {
                viewModel.clearDb()
                Toast.makeText(requireContext(), "Cache cleared", LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onContentClicked(view: View, item: BackupContentItem) {
        val f = if (item.contentType is MediaType) {
            MediaScanFragment.newInstance(item.getName(requireContext()), item.uri)
        } else {
            DocumentScanFragment.newInstance(item.getName(requireContext()), item.uri)
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .addToBackStack("LOG")
            .commit()
    }

    private fun onBackupUriReceived(uri: Uri?) {
        if (uri == null) {
            Toast.makeText(requireContext(), "No location set", LENGTH_SHORT).show()
        } else {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setBackupLocation(uri)
            backupLocationItem.isChecked = true
            jobItem.isEnabled = true
            restoreItem.isEnabled = true
        }
    }

    private fun onRestoreClicked() {
        MaterialAlertDialogBuilder(requireContext())
            .setIcon(android.R.drawable.stat_sys_warning)
            .setTitle("Warning")
            .setMessage("This will override data and should only be used on a clean phone. Not the one you just made the backup on.")
            .setPositiveButton("I have been warned") { dialog, _ ->
                onStartRestore()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun onStartRestore() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, DemoSnapshotFragment())
            .addToBackStack("SNAPSHOTS")
            .commit()
    }

}
