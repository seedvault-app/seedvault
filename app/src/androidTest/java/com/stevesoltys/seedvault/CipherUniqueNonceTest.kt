package com.stevesoltys.seedvault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
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
