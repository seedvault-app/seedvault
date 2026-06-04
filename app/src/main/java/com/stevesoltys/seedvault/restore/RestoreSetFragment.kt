/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager.beginDelayedTransition
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.system.exitProcess

private val TAG = RestoreSetFragment::class.simpleName

class RestoreSetFragment : Fragment() {

    private val viewModel: RestoreViewModel by activityViewModel()

    private lateinit var listView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var tryAgainButton: Button
    private lateinit var restartButton: Button
    private lateinit var skipView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_restore_set, container, false)

        listView = v.requireViewById(R.id.listView)
        progressBar = v.requireViewById(R.id.progressBar)
        errorView = v.requireViewById(R.id.errorView)
        tryAgainButton = v.requireViewById(R.id.tryAgainButton)
        restartButton = v.requireViewById(R.id.restartButton)
        skipView = v.requireViewById(R.id.skipView)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.restoreSetResults.observe(viewLifecycleOwner) { result ->
            onRestoreResultsLoaded(result)
        }

        skipView.setOnClickListener {
            viewModel.onFinishClickedAfterRestoringAppData()
        }
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.recoveryCodeIsSet() && viewModel.validLocationIsSet()) {
            viewModel.loadRestoreSets()
        }
    }

    private fun onRestoreResultsLoaded(results: RestoreSetResult) {
        if (results.hasError()) {
            errorView.visibility = VISIBLE
            tryAgainButton.visibility = VISIBLE
            if (viewModel.isSetupWizard) restartButton.visibility = VISIBLE
            listView.visibility = INVISIBLE
            progressBar.visibility = INVISIBLE

            errorView.text = results.errorMsg
            errorView.movementMethod = ScrollingMovementMethod()
            tryAgainButton.setOnClickListener { tryAgain() }
            restartButton.setOnClickListener { restart() }
        } else {
            errorView.visibility = INVISIBLE
            tryAgainButton.visibility = INVISIBLE
            restartButton.visibility = INVISIBLE
            listView.visibility = VISIBLE
            progressBar.visibility = INVISIBLE

            listView.adapter = RestoreSetAdapter(
                listener = viewModel,
                items = results.restorableBackups.sortedByDescending { it.time },
            )
        }
    }

    private fun tryAgain() {
        beginDelayedTransition(view as ViewGroup)

        progressBar.visibility = VISIBLE
        listView.visibility = VISIBLE

        errorView.visibility = INVISIBLE
        tryAgainButton.visibility = INVISIBLE
        restartButton.visibility = INVISIBLE

        viewModel.loadRestoreSets()
    }

    private fun restart() {
        lifecycleScope.launch {
            viewModel.restartRestore()
            // we'll need to kill our process to not have references to the old key around
            // trying to re-set all those references is complicated, so exiting the app is easier.
            Log.w(TAG, "Shutting down app...")
            exitProcess(0)
        }
    }
}

internal interface RestorableBackupClickListener {
    fun onRestorableBackupClicked(restorableBackup: RestorableBackup)
}
