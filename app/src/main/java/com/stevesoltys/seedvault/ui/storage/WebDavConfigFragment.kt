/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager.beginDelayedTransition
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import com.google.android.material.textfield.TextInputEditText
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.backend.webdav.WebDavConfigState
import com.stevesoltys.seedvault.ui.INTENT_EXTRA_IS_RESTORE
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.getSharedViewModel

class WebDavConfigFragment : Fragment(), View.OnClickListener {

    companion object {
        fun newInstance(isRestore: Boolean): WebDavConfigFragment {
            val f = WebDavConfigFragment()
            f.arguments = Bundle().apply {
                putBoolean(INTENT_EXTRA_IS_RESTORE, isRestore)
            }
            return f
        }
    }

    private lateinit var viewModel: StorageViewModel

    private lateinit var urlInput: TextInputEditText
    private lateinit var userInput: TextInputEditText
    private lateinit var passInput: TextInputEditText
    private lateinit var button: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_webdav_config, container, false)
        urlInput = v.requireViewById(R.id.webdavUrlInput)
        userInput = v.requireViewById(R.id.webdavUserInput)
        passInput = v.requireViewById(R.id.webDavPassInput)
        button = v.requireViewById(R.id.webdavButton)
        button.setOnClickListener(this)
        progressBar = v.requireViewById(R.id.progressBar)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = if (requireArguments().getBoolean(INTENT_EXTRA_IS_RESTORE)) {
            getSharedViewModel<RestoreStorageViewModel>()
        } else {
            getSharedViewModel<BackupStorageViewModel>()
        }
        lifecycleScope.launch {
            viewModel.webdavConfigState.flowWithLifecycle(lifecycle, STARTED).collect {
                onConfigStateChanged(it)
            }
        }
    }

    override fun onClick(v: View) {
        if (urlInput.text.isNullOrBlank()) {
            Snackbar.make(
                requireView(),
                R.string.storage_webdav_config_malformed_url,
                LENGTH_LONG
            ).setAnchorView(button).show()
        } else {
            viewModel.onWebDavConfigReceived(
                url = urlInput.text.toString(),
                user = userInput.text.toString(),
                pass = passInput.text.toString(),
            )
        }
    }

    override fun onDestroy() {
        viewModel.resetWebDavConfig()
        super.onDestroy()
    }

    private fun onConfigStateChanged(state: WebDavConfigState) {
        when (state) {
            WebDavConfigState.Empty -> {

            }

            WebDavConfigState.Checking -> {
                beginDelayedTransition(requireView() as ViewGroup)
                progressBar.visibility = VISIBLE
                button.visibility = INVISIBLE
            }

            is WebDavConfigState.Success -> {
                viewModel.onWebDavConfigSuccess(state.properties, state.backend)
            }

            is WebDavConfigState.Error -> {
                val s = if (state.e == null) {
                    getString(R.string.storage_check_fragment_backup_error)
                } else {
                    getString(R.string.storage_check_fragment_backup_error) +
                        " ${state.e::class.java.simpleName} ${state.e.message}"
                }
                Snackbar.make(requireView(), s, LENGTH_LONG).setAnchorView(button).show()

                beginDelayedTransition(requireView() as ViewGroup)
                progressBar.visibility = INVISIBLE
                button.visibility = VISIBLE
            }
        }
    }

}
