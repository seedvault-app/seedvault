package com.stevesoltys.backup.settings

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.*
import android.os.Bundle
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.lifecycle.ViewModelProviders
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.stevesoltys.backup.R

private val TAG = BackupLocationFragment::class.java.name

class BackupLocationFragment : PreferenceFragmentCompat() {

    private lateinit var viewModel: SettingsViewModel

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.backup_location, rootKey)

        requireActivity().setTitle(R.string.settings_backup_location_title)

        viewModel = ViewModelProviders.of(requireActivity()).get(SettingsViewModel::class.java)

        val externalStorage = Preference(requireContext()).apply {
            setIcon(R.drawable.ic_storage)
            setTitle(R.string.settings_backup_external_storage)
            setOnPreferenceClickListener {
                showChooseFolderActivity()
                true
            }
        }
        preferenceScreen.addPreference(externalStorage)
    }

    private fun showChooseFolderActivity() {
        val openTreeIntent = Intent(ACTION_OPEN_DOCUMENT_TREE)
        openTreeIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            val documentChooser = createChooser(openTreeIntent, null)
            startActivityForResult(documentChooser, REQUEST_CODE_OPEN_DOCUMENT_TREE)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "Please install a file manager.", LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE) {
            viewModel.handleChooseFolderResult(result)
        } else {
            super.onActivityResult(requestCode, resultCode, result)
        }
    }

}
