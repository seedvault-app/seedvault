/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.MultiResponseCallback
import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.Response.HrefRelation.SELF
import at.bitfire.dav4jvm.ResponseCallback
import at.bitfire.dav4jvm.exception.ConflictException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.ResourceType
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl

private val log = KotlinLogging.logger {}

/**
 * Tries to do [DavCollection.propfind] with a depth of `-1`.
 * Since `infinity` isn't supported by nginx either,
 * we fallback to iterating over all folders found with depth `1`
 * and do another PROPFIND on those, passing the given [callback].
 *
 * @param maxDepth in case we need to fallback to recursive propfinds, we only go that far down.
 */
internal fun DavCollection.propfindDepthInfinity(maxDepth: Int, callback: MultiResponseCallback) {
    try {
        propfind(
            depth = -1,
            reqProp = arrayOf(DisplayName.NAME, ResourceType.NAME, GetContentLength.NAME),
            callback = callback,
        )
    } catch (e: HttpException) {
        if (e.isUnsupportedPropfind()) {
            log.info { "Got ${e.response}, trying recursive depth=1 PROPFINDs..." }
            propfindFakeInfinity(maxDepth, callback)
        } else {
            throw e
        }
    }
}

internal fun DavCollection.propfindFakeInfinity(depth: Int, callback: MultiResponseCallback) {
    if (depth <= 0) return
    propfind(
        depth = 1,
        reqProp = arrayOf(DisplayName.NAME, ResourceType.NAME, GetContentLength.NAME),
    ) { response, relation ->
        // This callback will be called for everything in the folder
        callback.onResponse(response, relation)
        if (relation != SELF && response.isFolder()) {
            DavCollection(httpClient, response.href).propfindFakeInfinity(depth - 1, callback)
        }
    }
}

internal fun DavCollection.mkColCreateMissing(callback: ResponseCallback) {
    try {
        mkCol(null) { response ->
            callback.onResponse(response)
        }
    } catch (e: ConflictException) {
        log.warn { "Error creating $location: $e" }
        if (location.pathSize <= 1) throw e
        val newLocation = location.newBuilder()
            .removePathSegment(location.pathSize - 1)
            .build()
        DavCollection(httpClient, newLocation).mkColCreateMissing(callback)
        // re-run original command to create parent collection
        mkCol(null) { response ->
            callback.onResponse(response)
        }
    }
}

internal fun DavCollection.ensureFoldersExist(log: KLogger, folders: MutableSet<HttpUrl>) {
    if (location.pathSize <= 2) return
    val parent = location.newBuilder()
        .removePathSegment(location.pathSize - 1)
        .build()
    if (parent in folders) return
    val parentCollection = DavCollection(httpClient, parent)
    try {
        parentCollection.head { response ->
            log.debugLog { "head($parent) = $response" }
            folders.add(parent)
        }
    } catch (e: NotFoundException) {
        log.debugLog { "$parent not found, creating..." }
        parentCollection.mkColCreateMissing { response ->
            log.debugLog { "mkColCreateMissing($parent) = $response" }
            folders.add(parent)
        }
    }
}

private fun HttpException.isUnsupportedPropfind(): Boolean {
    // nginx is not including 'propfind-finite-depth' in body, so just relay on code
    return code == 403 || code == 400 // dufs returns 400
}

internal fun List<Property>.contentLength(): Long {
    // crash intentionally, if this isn't in the list
    return filterIsInstance<GetContentLength>()[0].contentLength ?: error("No contentLength")
}

internal fun Response.isFolder(): Boolean {
    return this[ResourceType::class.java]?.types?.contains(ResourceType.COLLECTION) == true
}
