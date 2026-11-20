/*
 * SPDX-FileCopyrightText: 2026 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.recoverycode

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests the pure parts of [PasswordManagerSaver]:
 * how save errors map to results, and the format of the saved secret.
 */
class PasswordManagerSaverTest {

    @Test
    fun `cancelled prompt maps to Cancelled`() {
        val e = CreateCredentialCancellationException()
        assertEquals(PasswordSaveResult.Cancelled, e.toPasswordSaveResult())
    }

    @Test
    fun `interrupted prompt maps to Interrupted`() {
        val e = CreateCredentialInterruptedException()
        assertEquals(PasswordSaveResult.Interrupted, e.toPasswordSaveResult())
    }

    @Test
    fun `no create option maps to NoPasswordManager`() {
        val e = CreateCredentialNoCreateOptionException()
        assertEquals(PasswordSaveResult.NoPasswordManager, e.toPasswordSaveResult())
    }

    @Test
    fun `provider configuration error maps to NoPasswordManager`() {
        val e = CreateCredentialProviderConfigurationException()
        assertEquals(PasswordSaveResult.NoPasswordManager, e.toPasswordSaveResult())
    }

    @Test
    fun `unknown error with Google's no password manager code maps to NoPasswordManager`() {
        val e = CreateCredentialUnknownException("$GOOGLE_ERROR_NO_PASSWORD_MANAGER some text")
        assertEquals(PasswordSaveResult.NoPasswordManager, e.toPasswordSaveResult())
    }

    @Test
    fun `unknown error with Google's save prompt disabled code maps to SavePromptDisabled`() {
        val e = CreateCredentialUnknownException("$GOOGLE_ERROR_SAVE_PROMPT_DISABLED some text")
        assertEquals(PasswordSaveResult.SavePromptDisabled, e.toPasswordSaveResult())
    }

    @Test
    fun `unknown error without a known code maps to Unexpected`() {
        val e = CreateCredentialUnknownException("something else went wrong")
        assertEquals(PasswordSaveResult.Unexpected(e), e.toPasswordSaveResult())
    }

    @Test
    fun `unknown error without a message maps to Unexpected`() {
        val e = CreateCredentialUnknownException()
        assertEquals(PasswordSaveResult.Unexpected(e), e.toPasswordSaveResult())
    }

    @Test
    fun `any other exception maps to Unexpected`() {
        val e = IllegalStateException()
        assertEquals(PasswordSaveResult.Unexpected(e), e.toPasswordSaveResult())
    }

    @Test
    fun `secret is all words in order separated by the word separator`() {
        val words = List(WORD_NUM) { i -> "word$i" }
        val secret = words.map { it.toCharArray() }.toPasswordManagerSecret()
        assertEquals(words.joinToString(RECOVERY_CODE_WORD_SEPARATOR), secret)
        assertEquals(WORD_NUM, secret.split(RECOVERY_CODE_WORD_SEPARATOR).size)
    }
}
