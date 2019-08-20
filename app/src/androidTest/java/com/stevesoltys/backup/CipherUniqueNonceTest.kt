package com.stevesoltys.backup

import android.util.Log
import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4
import com.stevesoltys.backup.crypto.CipherFactoryImpl
import com.stevesoltys.backup.crypto.KeyManagerTestImpl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private val TAG = CipherUniqueNonceTest::class.java.simpleName
private const val ITERATIONS = 1_000_000

@LargeTest
@RunWith(AndroidJUnit4::class)
class CipherUniqueNonceTest {

    private val keyManager = KeyManagerTestImpl()
    private val cipherFactory = CipherFactoryImpl(keyManager)

    private val nonceSet = HashSet<ByteArray>()

    @Test
    fun testUniqueNonce() {
        for (i in 1..ITERATIONS) {
            val iv = cipherFactory.createEncryptionCipher().iv
            Log.w(TAG, "$i: ${iv.toHexString()}")
            assertTrue(nonceSet.add(iv))
        }
    }

}
