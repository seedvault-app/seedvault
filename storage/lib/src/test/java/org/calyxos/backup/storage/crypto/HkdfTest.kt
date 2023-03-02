/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

internal class HkdfTest {

    @Test
    fun rfc5869testCase1() {
        checkStep2(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            "f0f1f2f3f4f5f6f7f8f9",
            42,
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        )
    }

    @Test
    @Throws(Exception::class)
    fun rfc5869testCase2() {
        checkStep2(
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4" +
                "c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9" +
                "dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
            82,
            "b11e398dc80327a1c8e7f78c596a49344f012eda2" +
                "d4efad8a050cc4c19afa97c59045a99cac7827271c" +
                "b41c65e590e09da3275600c2f09b8367793a9aca3db" +
                "71cc30c58179ec3e87c14c01d5c1f3434f1d87"
        )
    }

    @Test
    @Throws(Exception::class)
    fun rfc5869testCase3() {
        checkStep2(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            "",
            42,
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun checkStep2(prk: String, info: String, length: Int, okm: String) {
        val seed = decodeHexString(prk)
        val secretKey = SecretKeySpec(seed, 0, seed.size, "AES")
        val currentOkm: ByteArray = Hkdf.expand(secretKey, decodeHexString(info), length)
        assertArrayEquals(decodeHexString(okm), currentOkm)
    }

    private fun decodeHexString(hexString: String): ByteArray {
        require(hexString.length % 2 != 1) { "Invalid hexadecimal String supplied." }
        val bytes = ByteArray(hexString.length / 2)
        var i = 0
        while (i < hexString.length) {
            bytes[i / 2] = hexToByte(hexString.substring(i, i + 2))
            i += 2
        }
        return bytes
    }

    private fun hexToByte(hexString: String): Byte {
        val firstDigit = toDigit(hexString[0])
        val secondDigit = toDigit(hexString[1])
        return ((firstDigit shl 4) + secondDigit).toByte()
    }

    private fun toDigit(hexChar: Char): Int {
        val digit = Character.digit(hexChar, 16)
        require(digit != -1) { "Invalid Hexadecimal Character: $hexChar" }
        return digit
    }

}
