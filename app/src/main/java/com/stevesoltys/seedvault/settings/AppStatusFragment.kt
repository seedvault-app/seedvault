/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

internal interface AppStatusToggleListener {
    fun onAppStatusToggled(status: AppStatus)
}

class AppStatusFragment : Fragment(), AppStatusToggleListener {

    private val viewModel: SettingsViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = AppStatusAdapter(this)

    private lateinit var appEditMenuItem: MenuItem
    private lateinit var list: RecyclerView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        setHasOptionsMenu(true)
        val v: View = inflater.inflate(R.layout.fragment_app_status, container, false)

        progressBar = v.requireViewById(R.id.progressBar)
        list = v.requireViewById(R.id.list)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.setTitle(R.string.settings_backup_status_title)

        list.apply {
            layoutManager = this@AppStatusFragment.layoutManager
            adapter = this@AppStatusFragment.adapter
        }

        progressBar.visibility = VISIBLE
        viewModel.appStatusList.observe(viewLifecycleOwner) { result ->
            adapter.update(result.appStatusList, result.diff)
            progressBar.visibility = INVISIBLE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.app_status_menu, menu)
        appEditMenuItem = menu.findItem(R.id.edit_app_blacklist)

        // observe edit mode changes here where we are sure to have the MenuItem
        viewModel.appEditMode.observe(viewLifecycleOwner) { enabled ->
            appEditMenuItem.isChecked = enabled
            adapter.setEditMode(enabled)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.edit_app_blacklist -> {
            viewModel.setEditMode(!item.isChecked)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onAppStatusToggled(status: AppStatus) {
        adapter.onItemChanged(status)
        viewModel.onAppStatusToggled(status)
    }

}
