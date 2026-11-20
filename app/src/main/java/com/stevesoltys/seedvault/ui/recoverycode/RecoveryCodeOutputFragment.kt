/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.recoverycode

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.isDebugBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class RecoveryCodeOutputFragment : Fragment() {

    private val viewModel: RecoveryCodeViewModel by activityViewModel()

    private lateinit var wordList: RecyclerView
    private lateinit var savePasswordManagerButton: Button
    private lateinit var confirmCodeButton: Button

    /**
     * The password manager dialog currently showing, if any.
     * Kept so we never show two at once, and can close it when our view goes away.
     */
    private var passwordDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_recovery_code_output, container, false)

        if (!isDebugBuild()) activity?.window?.addFlags(FLAG_SECURE)

        wordList = v.requireViewById(R.id.wordList)
        savePasswordManagerButton = v.requireViewById(R.id.savePasswordManagerButton)
        confirmCodeButton = v.requireViewById(R.id.confirmCodeButton)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.requireViewById<Toolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        setGridParameters(wordList)
        wordList.adapter = RecoveryCodeAdapter(viewModel.wordList)

        savePasswordManagerButton.setOnClickListener { viewModel.onSavePasswordClicked() }
        confirmCodeButton.setOnClickListener { viewModel.onConfirmButtonClicked() }

        // Also called right away with the current state, e.g. to re-show a dialog after rotation
        viewModel.passwordSaveState.observe(viewLifecycleOwner) { state ->
            onPasswordSaveStateChanged(state)
        }
    }

    override fun onDestroyView() {
        // Close the dialog without changing the state,
        // so the re-created view (e.g. after rotation) shows it again.
        passwordDialog?.dismiss()
        super.onDestroyView()
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

    /** Shows what belongs to the current step of saving to a password manager. */
    private fun onPasswordSaveStateChanged(state: PasswordSaveState) {
        when (state) {
            PasswordSaveState.Idle, PasswordSaveState.Saving -> Unit // nothing extra on screen
            PasswordSaveState.Confirming -> showTrustDialog()
            PasswordSaveState.Success -> showSavedMessage()
            is PasswordSaveState.Error -> showErrorDialog(state.type)
        }
    }

    /**
     * If the default password manager is Google Password Manager, warn the user about it.
     * Otherwise, warn the user to only use a password manager they trust, because anyone with the
     * recovery code can restore their backups.
     */
    private fun showTrustDialog() {
        if (passwordDialog?.isShowing == true) return
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recovery_code_password_manager_dialog_title)
            // Back button or tap outside. Not called when we dismiss() it in onDestroyView().
            .setOnCancelListener { viewModel.onSavePasswordCleared() }
            .setOnDismissListener { passwordDialog = null }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.onSavePasswordCleared()
            }
        if (PasswordManagerSaver.isGoogleDefaultPasswordManager(requireContext())) {
            val googleBody = getString(R.string.recovery_code_password_manager_google_body)
            val learnMore = getString(R.string.recovery_code_password_manager_learn_more)
            val url = GOOGLE_SUPPORT_ON_DEVICE_ENCRYPTION_HELP_URL
            val message = "$googleBody $learnMore"

            val spannable = SpannableString(message)
            val startIndex = message.indexOf(learnMore)
            val endIndex = startIndex + learnMore.length

            spannable.setSpan(URLSpan(url), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setMessage(spannable)

            val googlePasswordManagerIntent =
                PasswordManagerSaver.googlePasswordManagerIntent(requireContext())
            val openGoogle = R.string.recovery_code_password_manager_open_google
            val continueAnyway = R.string.recovery_code_password_manager_continue_anyway
            if (googlePasswordManagerIntent != null) {
                builder.setPositiveButton(openGoogle) { _, _ ->
                    viewModel.onSavePasswordCleared()
                    startActivity(googlePasswordManagerIntent)
                }
                builder.setNeutralButton(continueAnyway) { _, _ ->
                    saveToPasswordManager()
                }
            } else {
                builder.setPositiveButton(continueAnyway) { _, _ ->
                    saveToPasswordManager()
                }
            }

            passwordDialog = builder.show()
            passwordDialog?.findViewById<TextView>(android.R.id.message)?.movementMethod =
                LinkMovementMethod.getInstance()
        } else {
            passwordDialog = builder
                .setMessage(R.string.recovery_code_password_manager_trust_body)
                .setPositiveButton(R.string.recovery_code_password_manager_continue) { _, _ ->
                    saveToPasswordManager()
                }
                .show()
        }
    }

    /**
     * The user continued in the trust dialog: hand the recovery code to their password manager.
     * This is the only place a save starts, so a re-created view can never save twice.
     */
    private fun saveToPasswordManager() {
        if (!viewModel.onSavePasswordStarted()) return // already started, e.g. double tap
        val activity = requireActivity()
        val appName = getString(R.string.app_name)
        val label = getString(R.string.recovery_code_password_manager_id, appName)
        // Tied to our view: if it goes away (e.g. rotation), this gets cancelled
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                PasswordManagerSaver.save(
                    activity = activity,
                    label = label,
                    secret = viewModel.recoveryCodeForPasswordManager(),
                )
            } catch (e: CancellationException) {
                // Reset, so the user can try again on the re-created view
                viewModel.onSavePasswordFinished(PasswordSaveResult.Cancelled)
                throw e
            }
            viewModel.onSavePasswordFinished(result)
        }
    }

    /**
     * Explain why saving failed and, if the device has one, offer a shortcut to the password
     * manager settings to fix it.
     */
    private fun showErrorDialog(error: PasswordSaveError) {
        if (passwordDialog?.isShowing == true) return
        val message = when (error) {
            PasswordSaveError.NO_PASSWORD_MANAGER ->
                getString(R.string.recovery_code_password_manager_missing)

            PasswordSaveError.SAVE_PROMPT_DISABLED -> getString(
                R.string.recovery_code_password_manager_prompt_disabled,
                getString(R.string.app_name),
            )
        }
        val settingsIntent = PasswordManagerSaver.settingsIntent(requireContext())
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recovery_code_password_manager_cant_save_title)
            .setMessage(message)
            // Back button or tap outside. Not called when we dismiss() it in onDestroyView().
            .setOnCancelListener { viewModel.onSavePasswordCleared() }
            .setOnDismissListener { passwordDialog = null }
        if (settingsIntent == null) {
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.onSavePasswordCleared()
            }
        } else {
            val goToSettings = R.string.recovery_code_password_manager_go_to_settings
            builder.setPositiveButton(goToSettings) { _, _ ->
                viewModel.onSavePasswordCleared()
                startActivity(settingsIntent)
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ ->
                viewModel.onSavePasswordCleared()
            }
        }
        passwordDialog = builder.show()
    }

    /** Briefly confirms the save, without leaving the recovery code screen. */
    private fun showSavedMessage() {
        Snackbar.make(requireView(), R.string.recovery_code_password_manager_saved, LENGTH_LONG)
            .setAnchorView(savePasswordManagerButton) // above the buttons, not over them
            .show()
        viewModel.onSavePasswordCleared()
    }
}
