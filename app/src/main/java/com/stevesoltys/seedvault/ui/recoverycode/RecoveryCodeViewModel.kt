package com.stevesoltys.seedvault.ui.recoverycode

import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.stevesoltys.seedvault.App
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.transport.backup.BackupPlugin
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import io.github.novacrypto.bip39.JavaxPBKDF2WithHmacSHA512
import io.github.novacrypto.bip39.MnemonicGenerator
import io.github.novacrypto.bip39.MnemonicValidator
import io.github.novacrypto.bip39.SeedCalculator
import io.github.novacrypto.bip39.Validation.InvalidChecksumException
import io.github.novacrypto.bip39.Validation.InvalidWordCountException
import io.github.novacrypto.bip39.Validation.UnexpectedWhiteSpaceException
import io.github.novacrypto.bip39.Validation.WordNotFoundException
import io.github.novacrypto.bip39.Words
import io.github.novacrypto.bip39.wordlists.English
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.SecureRandom
import java.util.ArrayList

internal const val WORD_NUM = 12
internal const val WORD_LIST_SIZE = 2048

class RecoveryCodeViewModel(
    app: App,
    private val crypto: Crypto,
    private val keyManager: KeyManager,
    private val backupPlugin: BackupPlugin
) : AndroidViewModel(app) {

    internal val wordList: List<CharSequence> by lazy {
        val items: ArrayList<CharSequence> = ArrayList(WORD_NUM)
        val entropy = ByteArray(Words.TWELVE.byteLength())
        SecureRandom().nextBytes(entropy)
        MnemonicGenerator(English.INSTANCE).createMnemonic(entropy) {
            if (it != " ") items.add(it)
        }
        items
    }

    private val mConfirmButtonClicked = MutableLiveEvent<Boolean>()
    internal val confirmButtonClicked: LiveEvent<Boolean> = mConfirmButtonClicked
    internal fun onConfirmButtonClicked() = mConfirmButtonClicked.setEvent(true)

    private val mRecoveryCodeSaved = MutableLiveEvent<Boolean>()
    internal val recoveryCodeSaved: LiveEvent<Boolean> = mRecoveryCodeSaved

    private val mExistingCodeChecked = MutableLiveEvent<Boolean>()
    internal val existingCodeChecked: LiveEvent<Boolean> = mExistingCodeChecked

    internal var isRestore: Boolean = false

    @Throws(WordNotFoundException::class, InvalidChecksumException::class)
    fun validateAndContinue(input: List<CharSequence>, forVerifyingNewCode: Boolean) {
        try {
            MnemonicValidator.ofWordList(English.INSTANCE).validate(input)
        } catch (e: UnexpectedWhiteSpaceException) {
            throw AssertionError(e)
        } catch (e: InvalidWordCountException) {
            throw AssertionError(e)
        }
        val mnemonic = input.joinToString(" ")
        val seed = SeedCalculator(JavaxPBKDF2WithHmacSHA512.INSTANCE).calculateSeed(mnemonic, "")
        if (forVerifyingNewCode) {
            keyManager.storeBackupKey(seed)
            keyManager.storeMainKey(seed)
            mRecoveryCodeSaved.setEvent(true)
        } else {
            val verified = crypto.verifyBackupKey(seed)
            if (verified && !keyManager.hasMainKey()) keyManager.storeMainKey(seed)
            mExistingCodeChecked.setEvent(verified)
        }
    }

    fun deleteAllBackup() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                backupPlugin.deleteAllBackups()
            } catch (e: IOException) {
                Log.e("RecoveryCodeViewModel", "Error deleting backups", e)
            }
        }
    }

}
