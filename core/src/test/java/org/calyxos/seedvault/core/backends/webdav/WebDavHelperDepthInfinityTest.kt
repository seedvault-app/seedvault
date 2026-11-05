/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import at.bitfire.dav4jvm.okhttp.DavCollection
import at.bitfire.dav4jvm.okhttp.Response.HrefRelation.SELF
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = ".SeedvaultTest"
private const val ANDROID_ID = "0123456789abcdef"
private const val TIME = 1700000000000L
private val BLOB_NAME = "ab" + "cd".repeat(31) // 64 hex chars, chunk folder "ab"

internal class WebDavHelperDepthInfinityTest {

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: DavDispatcher
    private lateinit var davCollection: DavCollection

    @Before
    fun setUp() {
        dispatcher = DavDispatcher()
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()

        // The production backend only allows TLS, but this unit test exercises
        // propfindDepthInfinity() directly with a cleartext client on localhost.
        val client = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .followRedirects(false)
            .build()
        davCollection = DavCollection(client, server.url("/$ROOT/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `depth infinity fallback lists all children recursively`() {
        val members = mutableSetOf<String>()
        davCollection.propfindDepthInfinity(3) { response, relation ->
            if (relation != SELF) members.add(response.href.encodedPath)
        }

        // The server silently degrades Depth: infinity, so the initial request
        // must have been attempted before falling back to depth=1.
        assertTrue(dispatcher.depthHeaders.contains("infinity"))

        assertEquals(
            setOf(
                "/$ROOT/$ANDROID_ID.sv/",
                "/$ROOT/$ANDROID_ID.sv/$TIME.SeedSnap",
                "/$ROOT/$ANDROID_ID.sv/ab/",
                "/$ROOT/$ANDROID_ID.sv/ab/$BLOB_NAME",
                "/$ROOT/$TIME/",
                "/$ROOT/$TIME/.backup.metadata",
            ),
            members,
        )
    }
}

private data class MockEntry(
    val href: String,
    val isCollection: Boolean,
    val size: Long = 0,
)

// Full list of resources on the mock server. Folders end with a trailing slash.
private val MOCK_ENTRIES = listOf(
    MockEntry("/$ROOT/", true),
    MockEntry("/$ROOT/$ANDROID_ID.sv/", true),
    MockEntry("/$ROOT/$ANDROID_ID.sv/$TIME.SeedSnap", false, 1337),
    MockEntry("/$ROOT/$ANDROID_ID.sv/ab/", true),
    MockEntry("/$ROOT/$ANDROID_ID.sv/ab/$BLOB_NAME", false, 42),
    MockEntry("/$ROOT/$TIME/", true),
    MockEntry("/$ROOT/$TIME/.backup.metadata", false, 100),
)

private class DavDispatcher : Dispatcher() {

    val depthHeaders = mutableListOf<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.method) {
            "PROPFIND" -> propfind(request)
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath ?: "/"
        val depth = request.getHeader("Depth") ?: ""
        depthHeaders.add(depth)
        // cpdavd behaves like Depth: 0 for Depth: infinity; Depth: 1 lists direct children.
        val includeChildren = depth != "0" && depth != "infinity"
        val entries = MOCK_ENTRIES.filter {
            it.href == path || (includeChildren && parentOf(it.href) == path)
        }
        return MockResponse()
            .setResponseCode(207)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .setBody(buildMultiStatus(entries))
    }

    private fun parentOf(href: String): String {
        val trimmed = href.trimEnd('/')
        return trimmed.substring(0, trimmed.lastIndexOf('/') + 1)
    }

    private fun buildMultiStatus(entries: List<MockEntry>): String = buildString {
        append(
            """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
"""
        )
        for (entry in entries) {
            append(
                """<d:response>
    <d:href>${entry.href}</d:href>
    <d:propstat>
        <d:prop>
            <d:displayname>${entry.name()}</d:displayname>
            ${entry.resourceTypeXml()}
        </d:prop>
        <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
</d:response>
"""
            )
        }
        append("</d:multistatus>")
    }

    private fun MockEntry.resourceTypeXml(): String = if (isCollection) {
        "<d:resourcetype><d:collection/></d:resourcetype>"
    } else {
        "<d:resourcetype/>\n            <d:getcontentlength>$size</d:getcontentlength>"
    }

    private fun MockEntry.name(): String = href.trimEnd('/').substringAfterLast('/')
}
