/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import com.stevesoltys.seedvault.repo.Padding.getPadTo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaddingTest {
    @Test
    fun test() = runBlocking {
        assertEquals(52, getPadTo(49))
        assertEquals(52, getPadTo(50))
        assertEquals(60, getPadTo(60))
        assertEquals(4096, getPadTo(4000))
        assertEquals(8192, getPadTo(8000))
        assertEquals(12288, getPadTo(12000))
        assertEquals(12288, getPadTo(12000))
        assertEquals(61440, getPadTo(60000))
        assertEquals(12288, getPadTo(12000))
        assertEquals(638976, getPadTo(634000))
        assertEquals(1277952, getPadTo(1250000))
        assertEquals(8388608, getPadTo(8260000))
        assertEquals(8388608, getPadTo(8380000))
        assertEquals(8388608, getPadTo(8388608))
        assertEquals(8650752, getPadTo(8388609))
    }
}
