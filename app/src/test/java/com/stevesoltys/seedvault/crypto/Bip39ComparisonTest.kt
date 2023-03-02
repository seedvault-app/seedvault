/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.crypto

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.stevesoltys.seedvault.ui.recoverycode.toMnemonicChars
import org.bitcoinj.crypto.MnemonicCode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

/**
 * Compares kotlin-bip39 library with bitcoinj library
 * to ensure that kotlin-bip39 is not malicious and can be upgraded safely.
 */
class Bip39ComparisonTest {

    companion object {
        private const val ITERATIONS = 128
        private val SEED_SIZE = Mnemonics.WordCount.COUNT_12.bitLength / 8

        @JvmStatic
        @Suppress("unused")
        private fun provideEntropy() = ArrayList<Arguments>(ITERATIONS).apply {
            for (i in 0 until ITERATIONS) {
                add(Arguments.of(Random.nextBytes(SEED_SIZE)))
            }
        }
    }

    @ParameterizedTest
    @MethodSource("provideEntropy")
    fun compareLibs(entropy: ByteArray) {
        val actualCodeFromEntropy = Mnemonics.MnemonicCode(entropy)
        val actualWordsFromEntropy = actualCodeFromEntropy.words.map { it.joinToString("") }
        val expectedWordsFromEntropy = MnemonicCode.INSTANCE.toMnemonic(entropy)
        // check that entropy produces the same words
        assertEquals(expectedWordsFromEntropy, actualWordsFromEntropy)

        val actualCodeFromWords =
            Mnemonics.MnemonicCode(expectedWordsFromEntropy.toMnemonicChars())
        // check that both codes are valid
        MnemonicCode.INSTANCE.check(expectedWordsFromEntropy)
        actualCodeFromEntropy.validate()

        // check that both codes produce same seed
        assertArrayEquals(
            MnemonicCode.toSeed(expectedWordsFromEntropy, ""),
            actualCodeFromWords.toSeed()
        )
    }

}
