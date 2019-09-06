package com.stevesoltys.backup.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.*
import android.os.Bundle
import android.provider.DocumentsContract.EXTRA_PROMPT
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.stevesoltys.backup.R

class BackupLocationFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.backup_location, rootKey)

        requireActivity().setTitle(R.string.settings_backup_location_title)

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
        openTreeIntent.putExtra(EXTRA_PROMPT, getString(R.string.settings_backup_location_picker))
        openTreeIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            val documentChooser = createChooser(openTreeIntent, null)
            // start from the activity context, so we can receive and handle the result there
            requireActivity().startActivityForResult(documentChooser, REQUEST_CODE_OPEN_DOCUMENT_TREE)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "Please install a file manager.", LENGTH_LONG).show()
        }
    }

}
