package com.stevesoltys.seedvault.ui.recoverycode

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.OnFocusChangeListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.isDebugBuild
import com.stevesoltys.seedvault.ui.LiveEventHandler
import io.github.novacrypto.bip39.Validation.InvalidChecksumException
import io.github.novacrypto.bip39.Validation.WordNotFoundException
import io.github.novacrypto.bip39.wordlists.English
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

internal const val ARG_FOR_NEW_CODE = "forVerifyingNewCode"

class RecoveryCodeInputFragment : Fragment() {

    private val viewModel: RecoveryCodeViewModel by sharedViewModel()

    private lateinit var introText: TextView
    private lateinit var doneButton: Button
    private lateinit var newCodeButton: Button
    private lateinit var backView: TextView
    private lateinit var wordLayout1: TextInputLayout
    private lateinit var wordLayout2: TextInputLayout
    private lateinit var wordLayout3: TextInputLayout
    private lateinit var wordLayout4: TextInputLayout
    private lateinit var wordLayout5: TextInputLayout
    private lateinit var wordLayout6: TextInputLayout
    private lateinit var wordLayout7: TextInputLayout
    private lateinit var wordLayout8: TextInputLayout
    private lateinit var wordLayout9: TextInputLayout
    private lateinit var wordLayout10: TextInputLayout
    private lateinit var wordLayout11: TextInputLayout
    private lateinit var wordLayout12: TextInputLayout
    private lateinit var wordList: ConstraintLayout

    /**
     * True if this is for verifying a new recovery code, false for verifying an existing one.
     */
    private var forVerifyingNewCode: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v: View = inflater.inflate(R.layout.fragment_recovery_code_input, container, false)

        introText = v.findViewById(R.id.introText)
        doneButton = v.findViewById(R.id.doneButton)
        newCodeButton = v.findViewById(R.id.newCodeButton)
        backView = v.findViewById(R.id.backView)
        wordLayout1 = v.findViewById(R.id.wordLayout1)
        wordLayout2 = v.findViewById(R.id.wordLayout2)
        wordLayout3 = v.findViewById(R.id.wordLayout3)
        wordLayout4 = v.findViewById(R.id.wordLayout4)
        wordLayout5 = v.findViewById(R.id.wordLayout5)
        wordLayout6 = v.findViewById(R.id.wordLayout6)
        wordLayout7 = v.findViewById(R.id.wordLayout7)
        wordLayout8 = v.findViewById(R.id.wordLayout8)
        wordLayout9 = v.findViewById(R.id.wordLayout9)
        wordLayout10 = v.findViewById(R.id.wordLayout10)
        wordLayout11 = v.findViewById(R.id.wordLayout11)
        wordLayout12 = v.findViewById(R.id.wordLayout12)
        wordList = v.findViewById(R.id.wordList)

        arguments?.getBoolean(ARG_FOR_NEW_CODE, true)?.let {
            forVerifyingNewCode = it
        }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.isRestore) {
            introText.setText(R.string.recovery_code_input_intro)
            backView.visibility = VISIBLE
            backView.setOnClickListener { requireActivity().finishAfterTransition() }
        }

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
        newCodeButton.visibility = if (forVerifyingNewCode) GONE else VISIBLE
        newCodeButton.setOnClickListener { generateNewCode() }

        viewModel.existingCodeChecked.observeEvent(viewLifecycleOwner,
            LiveEventHandler { verified -> onExistingCodeChecked(verified) }
        )

        if (forVerifyingNewCode && isDebugBuild() && !viewModel.isRestore) debugPreFill()
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
            viewModel.validateAndContinue(input, forVerifyingNewCode)
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
        val str = getString(R.string.recovery_code_error_invalid_word, e.suggestion1, e.suggestion2)
        showError(i, str)
    }

    private fun showError(i: Int, errorMsg: CharSequence) {
        getWordLayout(i).apply {
            error = errorMsg
            requestFocus()
        }
    }

    private fun onExistingCodeChecked(verified: Boolean) {
        AlertDialog.Builder(requireContext()).apply {
            if (verified) {
                setTitle(R.string.recovery_code_verification_ok_title)
                setMessage(R.string.recovery_code_verification_ok_message)
                setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
                setOnDismissListener { parentFragmentManager.popBackStack() }
            } else {
                setIcon(R.drawable.ic_warning)
                setTitle(R.string.recovery_code_verification_error_title)
                setMessage(R.string.recovery_code_verification_error_message)
                setPositiveButton(R.string.recovery_code_verification_try_again) { dialog, _ ->
                    dialog.dismiss()
                }
                setNegativeButton(R.string.recovery_code_verification_generate_new) { dialog, _ ->
                    dialog.dismiss()
                }
            }
        }.show()
    }

    private val regenRequest = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            viewModel.deleteAllBackup()
            parentFragmentManager.popBackStack()
            Snackbar.make(requireView(), R.string.recovery_code_recreated, Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private fun generateNewCode() {
        AlertDialog.Builder(requireContext())
            .setIcon(R.drawable.ic_warning)
            .setTitle(R.string.recovery_code_verification_new_dialog_title)
            .setMessage(R.string.recovery_code_verification_new_dialog_message)
            .setPositiveButton(R.string.recovery_code_verification_generate_new) { dialog, _ ->
                dialog.dismiss()
                val i = Intent(requireContext(), RecoveryCodeActivity::class.java)
                regenRequest.launch(i)
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    @Suppress("MagicNumber")
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
