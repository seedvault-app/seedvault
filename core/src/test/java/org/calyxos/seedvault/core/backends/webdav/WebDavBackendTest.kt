/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import at.bitfire.dav4jvm.okhttp.DavCollection
import at.bitfire.dav4jvm.okhttp.ResponseCallback
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendTest
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.junit.Test
import kotlin.test.assertEquals

internal class WebDavBackendTest : BackendTest() {
    override val backend: Backend by lazy {
        WebDavBackend(WebDavTestConfig.getConfig(), ".SeedvaultTest")
    }

    @Test
    fun `delete requests skip the Nextcloud trashbin`(): Unit = runBlocking {
        val headers = mutableListOf<Map<String, String>>()
        mockkConstructor(DavCollection::class)
        every {
            anyConstructed<DavCollection>().delete(
                ifETag = null,
                ifScheduleTag = null,
                headers = capture(headers),
                callback = any(),
            )
        } answers {
            arg<ResponseCallback>(3).onResponse(mockk(relaxed = true))
        }

        try {
            val backend = WebDavBackend(
                WebDavConfig("https://example.com", "user", "password"),
                ".SeedvaultTest",
            )
            backend.remove(TopLevelFolder("folder"))
            backend.removeAll()
        } finally {
            unmockkConstructor(DavCollection::class)
        }

        val expected = mapOf("X-NC-Skip-Trashbin" to "true")
        assertEquals(listOf(expected, expected), headers)
    }

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
