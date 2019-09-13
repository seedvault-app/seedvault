package com.stevesoltys.backup.ui.recoverycode

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R
import com.stevesoltys.backup.isDebugBuild
import io.github.novacrypto.bip39.Validation.InvalidChecksumException
import io.github.novacrypto.bip39.Validation.WordNotFoundException
import io.github.novacrypto.bip39.wordlists.English
import kotlinx.android.synthetic.main.fragment_recovery_code_input.*
import kotlinx.android.synthetic.main.recovery_code_input.*

class RecoveryCodeInputFragment : Fragment() {

    private lateinit var viewModel: RecoveryCodeViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_recovery_code_input, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(requireActivity()).get(RecoveryCodeViewModel::class.java)

        val adapter = getAdapter()

        for (i in 0 until WORD_NUM) {
            val wordLayout = getWordLayout(i)
            val editText = wordLayout.editText as AutoCompleteTextView
            editText.onFocusChangeListener = OnFocusChangeListener { _, focus ->
                if (!focus) wordLayout.isErrorEnabled = false
            }
            editText.setAdapter(adapter)
        }
        doneButton.setOnClickListener { done() }

        if (isDebugBuild() && !viewModel.isRestore) debugPreFill()
    }

    private fun getAdapter(): ArrayAdapter<String> {
        val adapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        for (i in 0 until WORD_LIST_SIZE) {
            adapter.add(English.INSTANCE.getWord(i))
        }
        return adapter
    }

    private fun getInput(): List<CharSequence> = ArrayList<String>(WORD_NUM).apply {
        for (i in 0 until WORD_NUM) add(getWordLayout(i).editText!!.text.toString())
    }

    private fun done() {
        val input = getInput()
        if (!allFilledOut(input)) return
        try {
            viewModel.validateAndContinue(input)
        } catch (e: InvalidChecksumException) {
            Toast.makeText(context, R.string.recovery_code_error_checksum_word, LENGTH_LONG).show()
        } catch (e: WordNotFoundException) {
            showWrongWordError(input, e)
        }
    }

    private fun allFilledOut(input: List<CharSequence>): Boolean {
        for (i in input.indices) {
            if (input[i].isNotEmpty()) continue
            showError(i, getString(R.string.recovery_code_error_empty_word))
            return false
        }
        return true
    }

    private fun showWrongWordError(input: List<CharSequence>, e: WordNotFoundException) {
        val i = input.indexOf(e.word)
        if (i == -1) throw AssertionError()
        showError(i, getString(R.string.recovery_code_error_invalid_word, e.suggestion1, e.suggestion2))
    }

    private fun showError(i: Int, errorMsg: CharSequence) {
        getWordLayout(i).apply {
            error = errorMsg
            requestFocus()
        }
    }

    private fun getWordLayout(i: Int) = when (i + 1) {
        1 -> wordLayout1
        2 -> wordLayout2
        3 -> wordLayout3
        4 -> wordLayout4
        5 -> wordLayout5
        6 -> wordLayout6
        7 -> wordLayout7
        8 -> wordLayout8
        9 -> wordLayout9
        10 -> wordLayout10
        11 -> wordLayout11
        12 -> wordLayout12
        else -> throw IllegalArgumentException()
    }

    private fun debugPreFill() {
        val words = viewModel.wordList
        for (i in words.indices) {
            getWordLayout(i).editText!!.setText(words[i])
        }
    }

}
