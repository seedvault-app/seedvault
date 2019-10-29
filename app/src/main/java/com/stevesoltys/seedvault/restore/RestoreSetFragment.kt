package com.stevesoltys.seedvault.restore

import android.app.backup.RestoreSet
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.seedvault.R
import kotlinx.android.synthetic.main.fragment_restore_set.*

class RestoreSetFragment : Fragment() {

    private lateinit var viewModel: RestoreViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_restore_set, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(requireActivity()).get(RestoreViewModel::class.java)

        viewModel.restoreSets.observe(this, Observer { result -> onRestoreSetsLoaded(result) })

        backView.setOnClickListener { requireActivity().finishAfterTransition() }
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.recoveryCodeIsSet() && viewModel.validLocationIsSet()) {
            viewModel.loadRestoreSets()
        }
    }

    private fun onRestoreSetsLoaded(result: RestoreSetResult) {
        if (result.hasError()) {
            errorView.visibility = VISIBLE
            listView.visibility = INVISIBLE
            progressBar.visibility = INVISIBLE

            errorView.text = result.errorMsg
        } else {
            errorView.visibility = INVISIBLE
            listView.visibility = VISIBLE
            progressBar.visibility = INVISIBLE

            listView.adapter = RestoreSetAdapter(viewModel, result.sets)
        }
    }

}

internal interface RestoreSetClickListener {
    fun onRestoreSetClicked(set: RestoreSet)
}
