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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.stevesoltys.seedvault.R
import kotlinx.android.synthetic.main.fragment_app_status.*
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

internal interface AppStatusToggleListener {
    fun onAppStatusToggled(status: AppStatus)
}

class AppStatusFragment : Fragment(), AppStatusToggleListener {

    private val viewModel: SettingsViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = AppStatusAdapter(this)
    private lateinit var appEditMenuItem: MenuItem

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_app_status, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        activity?.setTitle(R.string.settings_backup_status_title)

        list.apply {
            layoutManager = this@AppStatusFragment.layoutManager
            adapter = this@AppStatusFragment.adapter
        }

        progressBar.visibility = VISIBLE
        viewModel.appStatusList.observe(this, Observer { result ->
            adapter.update(result.appStatusList, result.diff)
            progressBar.visibility = INVISIBLE
        })
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.app_status_menu, menu)
        appEditMenuItem = menu.findItem(R.id.edit_app_blacklist)

        // observe edit mode changes here where we are sure to have the MenuItem
        viewModel.appEditMode.observe(this, Observer { enabled ->
            appEditMenuItem.isChecked = enabled
            adapter.setEditMode(enabled)
        })
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
