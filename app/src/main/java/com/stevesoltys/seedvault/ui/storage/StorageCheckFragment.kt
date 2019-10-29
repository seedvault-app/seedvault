package com.stevesoltys.seedvault.ui.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R
import kotlinx.android.synthetic.main.fragment_storage_check.*

private const val TITLE = "title"
private const val ERROR_MSG = "errorMsg"

class StorageCheckFragment : Fragment() {

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_storage_check, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        titleView.text = arguments!!.getString(TITLE)

        val errorMsg = arguments!!.getString(ERROR_MSG)
        if (errorMsg != null) {
            progressBar.visibility = INVISIBLE
            errorView.text = errorMsg
            errorView.visibility = VISIBLE
            backButton.visibility = VISIBLE
            backButton.setOnClickListener { requireActivity().supportFinishAfterTransition() }
        }
    }

}
