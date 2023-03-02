/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.recoverycode

import android.app.Activity.RESULT_OK
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.OnFocusChangeListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat.getMainExecutor
import androidx.fragment.app.Fragment
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.Mnemonics.ChecksumException
import cash.z.ecc.android.bip39.Mnemonics.InvalidWordException
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.isDebugBuild
import org.koin.androidx.viewmodel.ext.android.sharedViewModel
import java.util.Locale

internal const val ARG_FOR_NEW_CODE = "forStoringNewCode"

class RecoveryCodeInputFragment : Fragment() {

    private val viewModel: RecoveryCodeViewModel by sharedViewModel()

    private lateinit var introText: TextView
    private lateinit var doneButton: Button
    private lateinit var newCodeButton: Button
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
    private var forStoringNewCode: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_recovery_code_input, container, false)

        if (!isDebugBuild()) activity?.window?.addFlags(FLAG_SECURE)

        introText = v.requireViewById(R.id.introText)
        doneButton = v.requireViewById(R.id.doneButton)
        newCodeButton = v.requireViewById(R.id.newCodeButton)
        wordLayout1 = v.requireViewById(R.id.wordLayout1)
        wordLayout2 = v.requireViewById(R.id.wordLayout2)
        wordLayout3 = v.requireViewById(R.id.wordLayout3)
        wordLayout4 = v.requireViewById(R.id.wordLayout4)
        wordLayout5 = v.requireViewById(R.id.wordLayout5)
        wordLayout6 = v.requireViewById(R.id.wordLayout6)
        wordLayout7 = v.requireViewById(R.id.wordLayout7)
        wordLayout8 = v.requireViewById(R.id.wordLayout8)
        wordLayout9 = v.requireViewById(R.id.wordLayout9)
        wordLayout10 = v.requireViewById(R.id.wordLayout10)
        wordLayout11 = v.requireViewById(R.id.wordLayout11)
        wordLayout12 = v.requireViewById(R.id.wordLayout12)
        wordList = v.requireViewById(R.id.wordList)

        arguments?.getBoolean(ARG_FOR_NEW_CODE, true)?.let {
            forStoringNewCode = it
        }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity?.setTitle(R.string.recovery_code_title)

        if (viewModel.isRestore) {
            introText.setText(R.string.recovery_code_input_intro)
        }

        val adapterLayout = android.R.layout.simple_list_item_1
        val adapter = ArrayAdapter<String>(requireContext(), adapterLayout).apply {
            addAll(Mnemonics.getCachedWords(Locale.ENGLISH.language))
        }

        for (i in 0 until WORD_NUM) {
            val wordLayout = getWordLayout(i)
            val editText = wordLayout.editText as AutoCompleteTextView
            editText.onFocusChangeListener = OnFocusChangeListener { _, focus ->
                if (!focus) wordLayout.isErrorEnabled = false
            }
            editText.setAdapter(adapter)
        }
        doneButton.setOnClickListener { done() }
        newCodeButton.visibility = if (forStoringNewCode) GONE else VISIBLE
        newCodeButton.setOnClickListener { generateNewCode() }

        viewModel.existingCodeChecked.observeEvent(viewLifecycleOwner) { verified ->
            onExistingCodeChecked(verified)
        }

        if (forStoringNewCode && isDebugBuild() && !viewModel.isRestore) debugPreFill()
    }

    private fun getInput(): List<CharSequence> = ArrayList<String>(WORD_NUM).apply {
        for (i in 0 until WORD_NUM) add(getWordLayout(i).editText!!.text.toString())
    }

    private fun done() {
        val input = getInput()
        if (!allFilledOut(input)) return
        try {
            viewModel.validateCode(input)
        } catch (e: ChecksumException) {
            Toast.makeText(context, R.string.recovery_code_error_checksum_word, LENGTH_LONG).show()
            return
        } catch (e: InvalidWordException) {
            showWrongWordError(input)
            return
        }
        if (forStoringNewCode) {
            val keyguardManager = requireContext().getSystemService(KeyguardManager::class.java)
            if (keyguardManager?.isDeviceSecure == true) {
                // if we have a lock-screen secret, we can ask for it before storing the code
                storeNewCodeAfterAuth(input)
            } else {
                // user doesn't seem to care about security, store key without auth
                viewModel.storeNewCode(input)
            }
        } else {
            viewModel.verifyExistingCode(input)
        }
    }

    private fun storeNewCodeAfterAuth(input: List<CharSequence>) {
        val context = requireContext()
        val biometricPrompt = BiometricPrompt.Builder(context)
            .setConfirmationRequired(true)
            .setTitle(getString(R.string.recovery_code_auth_title))
            .setDescription(getString(R.string.recovery_code_auth_description))
            // BIOMETRIC_STRONG could be made optional in the future, setting guarded by credentials
            .setAllowedAuthenticators(DEVICE_CREDENTIAL or BIOMETRIC_STRONG)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                viewModel.storeNewCode(input)
            }
        }
        biometricPrompt.authenticate(CancellationSignal(), getMainExecutor(context), callback)
    }

    private fun allFilledOut(input: List<CharSequence>): Boolean {
        for (i in input.indices) {
            if (input[i].isNotEmpty()) continue
            showError(i, getString(R.string.recovery_code_error_empty_word))
            return false
        }
        return true
    }

    private fun showWrongWordError(input: List<CharSequence>) {
        val words = Mnemonics.getCachedWords(Locale.ENGLISH.language)
        val i = input.indexOfFirst { it !in words }
        if (i == -1) throw AssertionError()
        val str = getString(R.string.recovery_code_error_invalid_word)
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
            viewModel.reinitializeBackupLocation()
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
            getWordLayout(i).editText!!.setText(String(words[i]))
        }
    }

}
