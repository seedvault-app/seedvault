/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import org.koin.androidx.viewmodel.ext.android.activityViewModel

internal interface AppStatusToggleListener {
    fun onAppStatusToggled(status: AppStatus)
}

class AppStatusFragment : Fragment(), AppStatusToggleListener {

    private val viewModel: SettingsViewModel by activityViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = AppStatusAdapter(this)

    private lateinit var list: RecyclerView
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_app_status, container, false)

        progressBar = v.requireViewById(R.id.progressBar)
        list = v.requireViewById(R.id.list)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.requireViewById<Toolbar>(R.id.toolbar).apply {
            setOnMenuItemClickListener(::onMenuItemSelected)
            setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        viewModel.appEditMode.observe(viewLifecycleOwner) { enabled ->
            toolbar.menu.findItem(R.id.edit_app_blacklist)?.isChecked = enabled
            adapter.setEditMode(enabled)
        }

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

    private fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.edit_app_blacklist -> {
            viewModel.setEditMode(!item.isChecked)
            true
        }
        else -> false
    }

    override fun onAppStatusToggled(status: AppStatus) {
        adapter.onItemChanged(status)
        viewModel.onAppStatusToggled(status)
    }

}
