/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.RequireProvisioningActivity
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import com.stevesoltys.seedvault.ui.recoverycode.ARG_FOR_NEW_CODE
import com.stevesoltys.seedvault.ui.storage.StorageCheckFragment
import org.koin.androidx.viewmodel.ext.android.viewModel

internal const val ACTION_APP_STATUS_LIST = "com.stevesoltys.seedvault.APP_STATUS_LIST"
private const val PREF_BACKUP_RECOVERY_CODE = "backup_recovery_code"

class SettingsActivity : RequireProvisioningActivity(), OnPreferenceStartFragmentCallback {

    private val viewModel: SettingsViewModel by viewModel()

    override fun getViewModel(): RequireProvisioningViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_fragment_container)

        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null && viewModel.isFirstStart) {
            // let user choose whether to restore on first start
            FirstRunFragment().show(supportFragmentManager, null)
        } else if (savedInstanceState == null) {
            // always start with settings fragment as a base (when fresh start)
            showFragment(SettingsFragment())
        }
        // add app status fragment on the stack, if started via intent
        if (intent?.action == ACTION_APP_STATUS_LIST) {
            showFragment(AppStatusFragment(), true)
        }

        // observe initialization and show/remove init fragment
        // this can happen when enabling backup and storage wasn't initialized
        viewModel.initEvent.observeEvent(this) { show ->
            val tag = "INIT"
            if (show) {
                val title = getString(R.string.storage_check_fragment_backup_title)
                showFragment(StorageCheckFragment.newInstance(title), true, tag)
            } else {
                val fragment = supportFragmentManager.findFragmentByTag(tag)
                if (fragment?.isVisible == true) supportFragmentManager.popBackStack()
            }
        }
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean {
        val fragment =
            supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment!!)
        if (pref.key == PREF_BACKUP_RECOVERY_CODE) fragment.arguments = Bundle().apply {
            putBoolean(ARG_FOR_NEW_CODE, false)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

}
