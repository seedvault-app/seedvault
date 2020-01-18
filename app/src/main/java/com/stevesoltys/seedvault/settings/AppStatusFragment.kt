package com.stevesoltys.seedvault.settings

import android.os.Bundle
import android.view.LayoutInflater
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

class AppStatusFragment : Fragment() {

    private val viewModel: SettingsViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = AppStatusAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
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

}
