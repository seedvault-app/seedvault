package com.stevesoltys.seedvault.restore

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.stevesoltys.seedvault.R
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class AppSelectionFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = AppSelectionAdapter(lifecycleScope, this::loadIcon) { item ->
        viewModel.onAppSelected(item)
    }

    private lateinit var backupNameView: TextView
    private lateinit var toggleAllTextView: TextView
    private lateinit var toggleAllView: MaterialCheckBox
    private lateinit var appList: RecyclerView
    private lateinit var button: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_restore_app_selection, container, false)

        backupNameView = v.requireViewById(R.id.backupNameView)
        toggleAllTextView = v.requireViewById(R.id.toggleAllTextView)
        toggleAllView = v.requireViewById(R.id.toggleAllView)
        appList = v.requireViewById(R.id.appList)
        button = v.requireViewById(R.id.button)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        toggleAllTextView.setOnClickListener {
            viewModel.onCheckAllAppsClicked()
        }
        toggleAllView.setOnClickListener {
            viewModel.onCheckAllAppsClicked()
        }

        appList.apply {
            layoutManager = this@AppSelectionFragment.layoutManager
            adapter = this@AppSelectionFragment.adapter
        }
        button.setOnClickListener { viewModel.onNextClickedAfterSelectingApps() }

        viewModel.chosenRestorableBackup.observe(viewLifecycleOwner) { restorableBackup ->
            backupNameView.text = restorableBackup.name
        }
        viewModel.selectedApps.observe(viewLifecycleOwner) { state ->
            adapter.submitList(state.apps)
            toggleAllView.isChecked = state.allSelected
            // enable toggle all views only after icons have loaded
            toggleAllView.isEnabled = state.iconsLoaded
            toggleAllTextView.isEnabled = state.iconsLoaded
            button.isEnabled = state.iconsLoaded
        }
    }

    private suspend fun loadIcon(item: SelectableAppItem, callback: (Drawable) -> Unit) {
        viewModel.loadIcon(item, callback)
    }

}
