/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.recoverycode

import android.app.backup.IBackupManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.Mnemonics.ChecksumException
import cash.z.ecc.android.bip39.Mnemonics.InvalidWordException
import cash.z.ecc.android.bip39.Mnemonics.WordCountException
import cash.z.ecc.android.bip39.toSeed
import com.stevesoltys.seedvault.App
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.transport.backup.BackupInitializer
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.calyxos.backup.storage.api.StorageBackup
import java.io.IOException
import kotlin.system.exitProcess

internal const val WORD_NUM = 12

private val TAG = RecoveryCodeViewModel::class.java.simpleName

internal class RecoveryCodeViewModel(
    app: App,
    private val crypto: Crypto,
    private val keyManager: KeyManager,
    private val backupManager: IBackupManager,
    private val appBackupManager: AppBackupManager,
    private val backupInitializer: BackupInitializer,
    private val notificationManager: BackupNotificationManager,
    private val storageBackup: StorageBackup,
) : AndroidViewModel(app) {

    internal val wordList: List<CharArray> by lazy {
        // we use our own entropy to not having to trust the library to use SecureRandom
        val entropy = crypto.getRandomBytes(Mnemonics.WordCount.COUNT_12.bitLength / 8)
        // create the words from the entropy
        Mnemonics.MnemonicCode(entropy).words
    }

    private val mConfirmButtonClicked = MutableLiveEvent<Boolean>()
    internal val confirmButtonClicked: LiveEvent<Boolean> = mConfirmButtonClicked
    internal fun onConfirmButtonClicked() = mConfirmButtonClicked.setEvent(true)

    /*
     * Saving the recovery code to a password manager.
     * This is LiveData (not a LiveEvent), so the current step survives screen rotation and the
     * re-created screen shows exactly the dialog that was showing before.
     */
    private val mPasswordSaveState = MutableLiveData<PasswordSaveState>(PasswordSaveState.Idle)
    internal val passwordSaveState: LiveData<PasswordSaveState> = mPasswordSaveState

    /** The user tapped "Save to password manager". Ignored while a save is already underway. */
    internal fun onSavePasswordClicked() {
        if (mPasswordSaveState.value == PasswordSaveState.Idle) {
            mPasswordSaveState.value = PasswordSaveState.Confirming
        }
    }

    /**
     * The user confirmed the trust warning.
     * @return true if the caller should start the save now, false if one was already started
     * (e.g. "Continue" tapped twice).
     */
    internal fun onSavePasswordStarted(): Boolean {
        if (mPasswordSaveState.value != PasswordSaveState.Confirming) return false
        mPasswordSaveState.value = PasswordSaveState.Saving
        return true
    }

    /**
     * The password manager answered. Only outcomes the user can act on are shown, cancellations and
     * unexpected errors silently go back to [PasswordSaveState.Idle].
     */
    internal fun onSavePasswordFinished(result: PasswordSaveResult) {
        mPasswordSaveState.value = when (result) {
            PasswordSaveResult.Success -> PasswordSaveState.Success
            PasswordSaveResult.NoPasswordManager ->
                PasswordSaveState.Error(PasswordSaveError.NO_PASSWORD_MANAGER)
            PasswordSaveResult.SavePromptDisabled ->
                PasswordSaveState.Error(PasswordSaveError.SAVE_PROMPT_DISABLED)
            PasswordSaveResult.Cancelled,
            PasswordSaveResult.Interrupted,
            is PasswordSaveResult.Unexpected -> PasswordSaveState.Idle
        }
    }

    /** The user is done with the current dialog or message, so go back to the start. */
    internal fun onSavePasswordCleared() {
        mPasswordSaveState.value = PasswordSaveState.Idle
    }

    /**
     * The secret to store in the password manager.
     * Built on demand only, so it isn't kept around as a String longer than needed.
     */
    internal fun recoveryCodeForPasswordManager(): String = wordList.toPasswordManagerSecret()

    private val mRecoveryCodeSaved = MutableLiveEvent<Boolean>()
    internal val recoveryCodeSaved: LiveEvent<Boolean> = mRecoveryCodeSaved

    private val mExistingCodeChecked = MutableLiveEvent<Boolean>()
    internal val existingCodeChecked: LiveEvent<Boolean> = mExistingCodeChecked

    internal var isRestore: Boolean = false

    @Throws(InvalidWordException::class, ChecksumException::class)
    fun validateCode(input: List<CharSequence>): Mnemonics.MnemonicCode {
        check(input.size == WORD_NUM) { "Got ${input.size} words instead of $WORD_NUM" }
        val trimmedInput = input.map { it.trim() } // white-spaces have a meaning in next line
        val code = Mnemonics.MnemonicCode(trimmedInput.toMnemonicChars())
        try {
            code.validate()
        } catch (e: WordCountException) {
            throw AssertionError(e)
        }
        return code
    }

    /**
     * Verifies existing recovery code and returns result via [existingCodeChecked].
     */
    fun verifyExistingCode(input: List<CharSequence>) {
        // we validate the code again, just in case
        val seed = validateCode(input).toSeed()
        val verified = crypto.verifyBackupKey(seed)
        // store main key at this opportunity if it is still missing
        if (verified && !keyManager.hasMainKey()) keyManager.storeMainKey(seed)
        mExistingCodeChecked.setEvent(verified)
        if (verified) notificationManager.onNoMainKeyErrorFixed()
    }

    /**
     * Stores a new recovery code and returns result via [recoveryCodeSaved].
     */
    fun storeNewCode(input: List<CharSequence>) {
        // we validate the code again, just in case
        val seed = validateCode(input).toSeed()
        keyManager.storeBackupKey(seed)
        keyManager.storeMainKey(seed)
        mRecoveryCodeSaved.setEvent(true)
        notificationManager.onNoMainKeyErrorFixed()
    }

    /**
     * Deletes all storage backups for current user and clears the storage backup cache.
     * Also starts a new app data restore set and initializes it.
     *
     * The reason is that old backups won't be readable anymore with the new key.
     * We can't delete other backups safely, because we can't be sure
     * that they don't belong to a different device or user.
     *
     * Our process will be terminated at the end to ensure the old key isn't used anymore.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun reinitializeBackupLocation() {
        Log.d(TAG, "Re-initializing backup location...")
        GlobalScope.launch(Dispatchers.IO) {
            // remove old backup repository and clear local blob cache
            try {
                appBackupManager.removeBackupRepo()
            } catch (e: IOException) {
                Log.e(TAG, "Error removing backup repo: ", e)
            }
            // remove old storage snapshots and clear cache
            storageBackup.init()
            // we'll need to kill our process to not have references to the old key around
            // trying to re-set all those references is complicated, so exiting the app is easier.
            val exitApp = {
                Log.w(TAG, "Shutting down app...")
                exitProcess(0)
            }
            try {
                // initialize the new location
                if (backupManager.isBackupEnabled) backupInitializer.initialize(exitApp, exitApp)
            } catch (e: IOException) {
                Log.e(TAG, "Error starting new RestoreSet", e)
                exitApp()
            }
        }
    }
}

internal fun List<CharSequence>.toMnemonicChars(): CharArray {
    return joinToString(" ").toCharArray()
}

/**
 * The steps of saving the recovery code to a password manager.
 * Each step maps to what the screen shows, see the transitions in [RecoveryCodeViewModel].
 */
internal sealed interface PasswordSaveState {
    /** Nothing in progress, only the recovery code screen is showing. */
    data object Idle : PasswordSaveState

    /** The user tapped the button, the trust warning dialog is showing. */
    data object Confirming : PasswordSaveState

    /** The user continued, Android's save prompt is showing. */
    data object Saving : PasswordSaveState

    /** Saved, a short confirmation message is showing. */
    data object Success : PasswordSaveState

    /** Saving failed in a way the user can fix, an explanation dialog is showing. */
    data class Error(val type: PasswordSaveError) : PasswordSaveState
}

/** Save failures the user can fix, each with its own explanation. */
internal enum class PasswordSaveError {
    /** No password manager is set up, see [PasswordSaveResult.NoPasswordManager]. */
    NO_PASSWORD_MANAGER,

    /** Saving was declined for this app before, see [PasswordSaveResult.SavePromptDisabled]. */
    SAVE_PROMPT_DISABLED,
}
