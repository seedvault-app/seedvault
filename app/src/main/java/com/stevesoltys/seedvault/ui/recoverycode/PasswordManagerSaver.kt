/*
 * SPDX-FileCopyrightText: 2026 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.recoverycode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.autofill.AutofillManager
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import kotlinx.coroutines.CancellationException

private val TAG = PasswordManagerSaver::class.java.simpleName

/*
 * Google's password manager reports some failures as a [CreateCredentialUnknownException] with its
 * own status code in square brackets inside the message, e.g. "[28434] ...".
 * The Credential Manager library offers no structured field for these codes, and Google doesn't
 * document them, so we have to match the message text.
 * Values and meanings are based on Signal-Android's SignalCredentialManager, which handles the same
 * cases.
 */

/** Google: no password manager is set up that can save passwords. */
internal const val GOOGLE_ERROR_NO_PASSWORD_MANAGER = "[28434]"

/** Google: the user chose "never save" for this app in Google's password manager. */
internal const val GOOGLE_ERROR_SAVE_PROMPT_DISABLED = "[28435]"

/** Separates the words of the recovery code in the saved secret, e.g. "word1 word2 …". */
internal const val RECOVERY_CODE_WORD_SEPARATOR = " "

/**
 * Package of Google Play Services, which provides Google Password Manager's autofill service.
 * Same check as Signal-Android uses to detect Google Password Manager.
 */
internal const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"

/** Package of the Google Password Manager app, as Signal-Android opens it. */
internal const val GOOGLE_PASSWORD_MANAGER_PACKAGE = "com.google.android.apps.credentialmanager"

/** Google's help page "Get started with on-device encryption" for Google Password Manager. */
internal const val GOOGLE_SUPPORT_ON_DEVICE_ENCRYPTION_HELP_URL =
    "https://support.google.com/accounts/answer/11350823"

/** URI scheme Android settings screens use to refer to an app, e.g. "package:com.example". */
private const val URI_SCHEME_PACKAGE = "package"

/**
 * The outcome of asking the user's password manager to save the recovery code.
 * The UI decides what to show for each outcome, see [RecoveryCodeViewModel.onSavePasswordFinished].
 */
internal sealed interface PasswordSaveResult {
    /** The password manager saved the entry. */
    data object Success : PasswordSaveResult

    /** The user cancelled the system save prompt. */
    data object Cancelled : PasswordSaveResult

    /** The system interrupted the save prompt. The user can simply try again. */
    data object Interrupted : PasswordSaveResult

    /** No password manager is available that could save the entry. */
    data object NoPasswordManager : PasswordSaveResult

    /** The user previously told their password manager to never save for this app. */
    data object SavePromptDisabled : PasswordSaveResult

    /** Any other failure. Not shown to the user, as there's nothing they could do about it. */
    data class Unexpected(val exception: Exception) : PasswordSaveResult
}

/**
 * Saves the recovery code to the user's password manager
 * using Android's Credential Manager.
 *
 * Since Android 14 (our minSdk), Credential Manager forwards the request
 * to whichever password manager the user picked in system settings,
 * e.g. Google Password Manager, Bitwarden or KeePassDX.
 */
internal object PasswordManagerSaver {

    /**
     * Shows Android's save prompt for an entry named [label] holding [secret],
     * and suspends until the user confirmed or dismissed it.
     *
     * @param activity the screen the system prompt is shown on top of.
     * The Credential Manager needs a visible window for its UI,
     * so this can't be the application context.
     */
    suspend fun save(activity: Activity, label: String, secret: String): PasswordSaveResult {
        return try {
            val request = CreatePasswordRequest(id = label, password = secret)
            CredentialManager.create(activity).createCredential(activity, request)
            PasswordSaveResult.Success
        } catch (e: CancellationException) {
            // The calling coroutine was cancelled, e.g. because the screen got rotated.
            // This is not a save failure, so let the coroutine stop as it should.
            throw e
        } catch (e: Exception) {
            e.toPasswordSaveResult().also { result ->
                if (result is PasswordSaveResult.Unexpected) {
                    // Only log the class: an exception message must never risk leaking the secret
                    Log.w(TAG, "Unexpected error saving to password manager: ${e.javaClass.name}")
                }
            }
        }
    }

    /**
     * An intent opening the device's password manager settings, so the user can set up or fix their
     * password manager.
     * Null if this device has no such settings page; the error dialog then only offers "OK".
     */
    // ACTION_CREDENTIAL_PROVIDER is public since API 35, but it's a constant the compiler
    // copies into our app, so using it on API 34 is safe:
    // if Settings has no such page, opensOn() is false and we fall back.
    @SuppressLint("InlinedApi")
    fun settingsIntent(context: Context): Intent? {
        val packageUri = Uri.fromParts(URI_SCHEME_PACKAGE, context.packageName, null)
        // Android 14+ "Passwords, passkeys & accounts" page
        val credentialSettings = Intent(Settings.ACTION_CREDENTIAL_PROVIDER, packageUri)
        if (credentialSettings.opensOn(context)) return credentialSettings
        // Fallback for devices that removed that page: the autofill service picker
        val autofillManager = context.getSystemService(AutofillManager::class.java)
        val autofillSettings = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, packageUri)
        return autofillSettings.takeIf {
            autofillManager?.isAutofillSupported == true && it.opensOn(context)
        }
    }

    /**
     * Check whether the Google Password Manager is the default password manager. On API 34+,
     * choosing a default credential provider also sets the autofill service name, and this check
     * relies on that.
     */
    fun isGoogleDefaultPasswordManager(context: Context): Boolean {
        val autofillManager = context.getSystemService(AutofillManager::class.java)
        return autofillManager?.autofillServiceComponentName?.packageName ==
            GOOGLE_PLAY_SERVICES_PACKAGE
    }

    /**
     * Get an [Intent] to launch Google Password Manager, or null if none exists.
     */
    fun googlePasswordManagerIntent(context: Context): Intent? {
        return context.packageManager.getLaunchIntentForPackage(GOOGLE_PASSWORD_MANAGER_PACKAGE)
    }

    /** True if some app on the device can open this intent. */
    private fun Intent.opensOn(context: Context) = resolveActivity(context.packageManager) != null
}

/**
 * Maps an exception thrown by [CredentialManager.createCredential] to a [PasswordSaveResult].
 * Kept free of Android dependencies, so it can be unit tested.
 */
internal fun Exception.toPasswordSaveResult(): PasswordSaveResult = when (this) {
    is CreateCredentialCancellationException -> PasswordSaveResult.Cancelled
    is CreateCredentialInterruptedException -> PasswordSaveResult.Interrupted
    is CreateCredentialNoCreateOptionException,
    is CreateCredentialProviderConfigurationException -> PasswordSaveResult.NoPasswordManager
    is CreateCredentialUnknownException -> {
        val text = message.orEmpty()
        when {
            text.contains(GOOGLE_ERROR_NO_PASSWORD_MANAGER) ->
                PasswordSaveResult.NoPasswordManager
            text.contains(GOOGLE_ERROR_SAVE_PROMPT_DISABLED) ->
                PasswordSaveResult.SavePromptDisabled
            else -> PasswordSaveResult.Unexpected(this)
        }
    }
    else -> PasswordSaveResult.Unexpected(this)
}

/**
 * Turns the recovery code words into the secret stored in the password manager:
 * all words in display order, separated by [RECOVERY_CODE_WORD_SEPARATOR].
 */
internal fun List<CharArray>.toPasswordManagerSecret(): String =
    joinToString(RECOVERY_CODE_WORD_SEPARATOR) { String(it) }
