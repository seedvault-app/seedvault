/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendTest
import org.junit.Test

internal class WebDavBackendTest : BackendTest() {
    override val backend: Backend = WebDavBackend(WebDavTestConfig.getConfig(), ".SeedvaultTest")

    @Test
    fun `test write, list, read, rename, delete`(): Unit = runBlocking {
        testWriteListReadRenameDelete()
    }

    @Test
    fun `test remove, create, write file`(): Unit = runBlocking {
        testRemoveCreateWriteFile()
    }

    @Test
    fun `test, free space and create app blob without root folder`(): Unit = runBlocking {
        testTestFreeSpaceAndCreateBlob()
    }
}
