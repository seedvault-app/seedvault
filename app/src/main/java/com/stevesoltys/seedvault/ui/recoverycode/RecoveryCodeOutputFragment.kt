package com.stevesoltys.seedvault.ui.recoverycode

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class RecoveryCodeOutputFragment : Fragment() {

    private val viewModel: RecoveryCodeViewModel by sharedViewModel()

    private lateinit var wordList: RecyclerView
    private lateinit var confirmCodeButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v: View = inflater.inflate(R.layout.fragment_recovery_code_output, container, false)

        wordList = v.findViewById(R.id.wordList)
        confirmCodeButton = v.findViewById(R.id.confirmCodeButton)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setGridParameters(wordList)
        wordList.adapter = RecoveryCodeAdapter(viewModel.wordList)

        confirmCodeButton.setOnClickListener { viewModel.onConfirmButtonClicked() }
    }

    @Suppress("MagicNumber")
    private fun setGridParameters(list: RecyclerView) {
        val layoutManager = list.layoutManager as GridLayoutManager
        if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
            layoutManager.spanCount = 4
        } else {
            layoutManager.spanCount = 2
        }
    }

}
