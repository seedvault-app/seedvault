/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendTest
import kotlin.test.Test

public class WebDavBackendTest : BackendTest() {
    override val backend: Backend = WebDavBackend(WebDavTestConfig.getConfig(), ".SeedvaultTest")

    @Test
    public fun `test write, list, read, rename, delete`(): Unit = runBlocking {
        testWriteListReadRenameDelete()
    }

    @Test
    public fun `test remove, create, write file`(): Unit = runBlocking {
        testRemoveCreateWriteFile()
    }
}
