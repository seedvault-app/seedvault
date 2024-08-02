/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.RestoreActivity

class FirstRunFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false // is what really works, specifying it for the dialog only doesn't
        return MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.first_start_text)
            .setPositiveButton(R.string.setup_button) { dialog, _ ->
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment, SettingsFragment(), null)
                    .commit()
                dialog.dismiss()
            }
            .setNeutralButton(R.string.restore_backup_button) { dialog, _ ->
                val i = Intent(requireContext(), RestoreActivity::class.java)
                startActivity(i)
                dialog.dismiss()
                requireActivity().finish()
            }
            .setCancelable(false)
            .create()
    }
}
