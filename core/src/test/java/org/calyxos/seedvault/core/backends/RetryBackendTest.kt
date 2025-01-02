/*
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class RetryBackendTest {

    private val delegate: Backend = mockk()
    private val backend = RetryBackend(delegate)

    @Test
    fun `test retry works at last attempt`() = runTest {
        val e = IOException()
        coEvery {
            delegate.test()
        } throws e andThenThrows e andThenThrows e andThenThrows e andThen true
        every { delegate.isTransientException(e) } returns true

        assertTrue(backend.test())
        verify(exactly = MAX_RETRIES - 1) {
            delegate.isTransientException(e)
        }
    }

    @Test
    fun `test retry fails after max attempts reached`() = runTest {
        val e = IOException()
        coEvery { delegate.test() } throws e
        every { delegate.isTransientException(e) } returns true

        assertFailsWith<IOException> {
            backend.test()
        }
        coVerify(exactly = MAX_RETRIES) { delegate.test() }
    }

    @Test
    fun `test no retry because non-transient exception`() = runTest {
        val e = IOException()
        coEvery { delegate.test() } throws e
        every { delegate.isTransientException(e) } returns false

        assertFailsWith<IOException> {
            backend.test()
        }
        coVerify(exactly = 1) { delegate.test() } // no retries
    }
}
