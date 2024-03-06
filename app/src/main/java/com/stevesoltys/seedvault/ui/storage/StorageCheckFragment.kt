package com.stevesoltys.seedvault.ui.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R

private const val TITLE = "title"
private const val ERROR_MSG = "errorMsg"

class StorageCheckFragment : Fragment() {

    private lateinit var titleView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var backButton: Button

    companion object {
        fun newInstance(title: String, errorMsg: String? = null): StorageCheckFragment {
            val f = StorageCheckFragment()
            f.arguments = Bundle().apply {
                putString(TITLE, title)
                putString(ERROR_MSG, errorMsg)
            }
            return f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_storage_check, container, false)

        titleView = v.requireViewById(R.id.titleView)
        progressBar = v.requireViewById(R.id.progressBar)
        errorView = v.requireViewById(R.id.errorView)
        backButton = v.requireViewById(R.id.backButton)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleView.text = requireArguments().getString(TITLE)

        val errorMsg = requireArguments().getString(ERROR_MSG)
        if (errorMsg != null) {
            view.requireViewById<View>(R.id.patienceView).visibility = GONE
            progressBar.visibility = INVISIBLE
            errorView.text = errorMsg
            errorView.visibility = VISIBLE
            backButton.visibility = VISIBLE
            backButton.setOnClickListener { requireActivity().supportFinishAfterTransition() }
        }
    }

}
