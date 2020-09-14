package com.stevesoltys.seedvault.restore

import android.app.Activity.RESULT_OK
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager.VERTICAL
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class RestoreProgressFragment : Fragment() {

    private val viewModel: RestoreViewModel by sharedViewModel()

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = RestoreProgressAdapter()

    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var backupNameView: TextView
    private lateinit var appList: RecyclerView
    private lateinit var button: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val v: View = inflater.inflate(R.layout.fragment_restore_progress, container, false)

        progressBar = v.findViewById(R.id.progressBar)
        titleView = v.findViewById(R.id.titleView)
        backupNameView = v.findViewById(R.id.backupNameView)
        appList = v.findViewById(R.id.appList)
        button = v.findViewById(R.id.button)

        return v
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

        // decryption will fail when the device is locked, so keep the screen on to prevent locking
        requireActivity().window.addFlags(FLAG_KEEP_SCREEN_ON)

        viewModel.chosenRestorableBackup.observe(viewLifecycleOwner, Observer { restorableBackup ->
            backupNameView.text = restorableBackup.name
            progressBar.max = restorableBackup.packageMetadataMap.size
        })

        viewModel.restoreProgress.observe(viewLifecycleOwner, Observer { list ->
            stayScrolledAtTop { adapter.update(list) }
            progressBar.progress = list.size
        })

        viewModel.restoreBackupResult.observe(viewLifecycleOwner, Observer { finished ->
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
