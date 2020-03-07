package com.stevesoltys.seedvault.restore

import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager.VERTICAL
import com.stevesoltys.seedvault.R
import kotlinx.android.synthetic.main.fragment_restore_progress.*
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class RestoreProgressFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = RestoreProgressAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_restore_progress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleView.setText(R.string.restore_restoring)

        appList.apply {
            layoutManager = this@RestoreProgressFragment.layoutManager
            adapter = this@RestoreProgressFragment.adapter
            addItemDecoration(DividerItemDecoration(context, VERTICAL))
        }

        button.setText(R.string.restore_finished_button)
        button.setOnClickListener {
            requireActivity().setResult(RESULT_OK)
            requireActivity().finishAfterTransition()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        // decryption will fail when the device is locked, so keep the screen on to prevent locking
        requireActivity().window.addFlags(FLAG_KEEP_SCREEN_ON)

        viewModel.chosenRestorableBackup.observe(this, Observer { restorableBackup ->
            backupNameView.text = restorableBackup.name
            progressBar.max = restorableBackup.packageMetadataMap.size
        })

        viewModel.restoreProgress.observe(this, Observer { list ->
            stayScrolledAtTop { adapter.update(list) }
            progressBar.progress = list.size
        })

        viewModel.restoreBackupResult.observe(this, Observer { finished ->
            button.isEnabled = true
            if (finished.hasError()) {
                backupNameView.text = finished.errorMsg
                backupNameView.setTextColor(getColor(requireContext(), R.color.red))
            } else {
                backupNameView.text = getString(R.string.restore_finished_success)
            }
            activity?.window?.clearFlags(FLAG_KEEP_SCREEN_ON)
        })
    }

    private fun stayScrolledAtTop(add: () -> Unit) {
        val position = layoutManager.findFirstVisibleItemPosition()
        add.invoke()
        if (position == 0) layoutManager.scrollToPosition(0)
    }

}
