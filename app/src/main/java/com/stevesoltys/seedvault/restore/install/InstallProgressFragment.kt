package com.stevesoltys.seedvault.restore.install

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager.VERTICAL
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.restore.RestoreViewModel
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class InstallProgressFragment : Fragment(), InstallItemListener {

    private val viewModel: RestoreViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = InstallProgressAdapter(this)

    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var backupNameView: TextView
    private lateinit var appList: RecyclerView
    private lateinit var button: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v: View = inflater.inflate(R.layout.fragment_restore_progress, container, false)

        progressBar = v.findViewById(R.id.progressBar)
        titleView = v.findViewById(R.id.titleView)
        backupNameView = v.findViewById(R.id.backupNameView)
        appList = v.findViewById(R.id.appList)
        button = v.findViewById(R.id.button)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleView.setText(R.string.restore_installing_packages)

        appList.apply {
            layoutManager = this@InstallProgressFragment.layoutManager
            adapter = this@InstallProgressFragment.adapter
            addItemDecoration(DividerItemDecoration(context, VERTICAL))
        }
        button.setText(R.string.restore_next)
        button.setOnClickListener { viewModel.onNextClicked() }

        viewModel.chosenRestorableBackup.observe(viewLifecycleOwner, Observer { restorableBackup ->
            backupNameView.text = restorableBackup.name
        })

        viewModel.installResult.observe(viewLifecycleOwner, Observer { result ->
            onInstallResult(result)
        })

        viewModel.nextButtonEnabled.observe(viewLifecycleOwner, Observer { enabled ->
            button.isEnabled = enabled
        })
    }

    private fun onInstallResult(installResult: InstallResult) {
        // skip this screen, if there are no apps to install
        if (installResult.isEmpty) viewModel.onNextClicked()

        // if finished, treat all still queued apps as failed and resort/redisplay adapter items
        if (installResult.isFinished) {
            installResult.queuedToFailed()
            adapter.setFinished()
        }

        // update progress bar
        progressBar.progress = installResult.progress
        progressBar.max = installResult.total

        // just update adapter, or perform final action, if finished
        if (installResult.isFinished) onFinished(installResult)
        else updateAdapter(installResult.getNotQueued())
    }

    private fun onFinished(installResult: InstallResult) {
        if (installResult.hasFailed) {
            AlertDialog.Builder(requireContext())
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.restore_installing_error_title)
                .setMessage(R.string.restore_installing_error_message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }
                .setOnDismissListener {
                    updateAdapter(installResult.getNotQueued())
                }
                .show()
        } else {
            updateAdapter(installResult.getNotQueued())
        }
    }

    private fun updateAdapter(items: Collection<ApkInstallResult>) {
        val position = layoutManager.findFirstVisibleItemPosition()
        adapter.update(items)
        if (position == 0) layoutManager.scrollToPosition(0)
    }

    override fun onFailedItemClicked(item: ApkInstallResult) {
        // TODO restrict intent to installer package names to one of:
        //  * "org.fdroid.fdroid" "org.fdroid.fdroid.privileged"
        //  * "com.aurora.store"
        //  * "com.android.vending"
        val i = Intent(ACTION_VIEW, Uri.parse("market://details?id=${item.packageName}")).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
            addFlags(FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            // setPackage("com.aurora.store")
        }
        startActivity(i)
    }

}
