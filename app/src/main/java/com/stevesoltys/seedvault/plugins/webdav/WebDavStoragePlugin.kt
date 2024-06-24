/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.content.Context
import android.util.Log
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response.HrefRelation.SELF
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.QuotaAvailableBytes
import at.bitfire.dav4jvm.property.webdav.ResourceType
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.chunkFolderRegex
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.plugins.saf.FILE_NO_MEDIA
import com.stevesoltys.seedvault.plugins.tokenRegex
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.calyxos.backup.storage.plugin.PluginConstants.SNAPSHOT_EXT
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class WebDavStoragePlugin(
    context: Context,
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) : WebDavStorage(webDavConfig, root), StoragePlugin<WebDavConfig> {

    override suspend fun test(): Boolean {
        val location = (if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/").toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val webDavSupported = suspendCoroutine { cont ->
            davCollection.options { davCapabilities, response ->
                debugLog { "test() = $davCapabilities $response" }
                if (davCapabilities.contains("1")) cont.resume(true)
                else if (davCapabilities.contains("2")) cont.resume(true)
                else if (davCapabilities.contains("3")) cont.resume(true)
                else cont.resume(false)
            }
        }
        return webDavSupported
    }

    override suspend fun getFreeSpace(): Long? {
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val availableBytes = suspendCoroutine { cont ->
            davCollection.propfind(depth = 0, QuotaAvailableBytes.NAME) { response, _ ->
                debugLog { "getFreeSpace() = $response" }
                val quota = response.properties.getOrNull(0) as? QuotaAvailableBytes
                val availableBytes = quota?.quotaAvailableBytes ?: -1
                if (availableBytes > 0) {
                    cont.resume(availableBytes)
                } else {
                    cont.resume(null)
                }
            }
        }
        return availableBytes
    }

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        val location = "$url/$token/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val response = davCollection.createFolder()
        debugLog { "startNewRestoreSet($token) = $response" }
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // TODO does it make sense to delete anything
        //  when [startNewRestoreSet] is always called first? Maybe unify both calls?
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        try {
            davCollection.head { response ->
                debugLog { "Root exists: $response" }
            }
        } catch (e: NotFoundException) {
            val response = davCollection.createFolder()
            debugLog { "initializeDevice() = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun hasData(token: Long, name: String): Boolean {
        val location = "$url/$token/$name".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        return try {
            val response = suspendCoroutine { cont ->
                davCollection.head { response ->
                    cont.resume(response)
                }
            }
            debugLog { "hasData($token, $name) = $response" }
            response.isSuccessful
        } catch (e: NotFoundException) {
            debugLog { "hasData($token, $name) = $e" }
            false
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getOutputStream(token: Long, name: String): OutputStream {
        val location = "$url/$token/$name".toHttpUrl()
        return try {
            getOutputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting OutputStream for $token and $name: ", e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        val location = "$url/$token/$name".toHttpUrl()
        return try {
            getInputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting InputStream for $token and $name: ", e)
        }
    }

    @Throws(IOException::class)
    override suspend fun removeData(token: Long, name: String) {
        val location = "$url/$token/$name".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        try {
            val response = suspendCoroutine { cont ->
                davCollection.delete { response ->
                    cont.resume(response)
                }
            }
            debugLog { "removeData($token, $name) = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>? {
        return try {
            doGetAvailableBackups()
        } catch (e: Throwable) { // NoClassDefFound isn't an [Exception], can get thrown by dav4jvm
            Log.e(TAG, "Error getting available backups: ", e)
            null
        }
    }

    private suspend fun doGetAvailableBackups(): Sequence<EncryptedMetadata> {
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        // get all restore set tokens in root folder
        val tokens = ArrayList<Long>()
        try {
            davCollection.propfind(
                depth = 2,
                reqProp = arrayOf(DisplayName.NAME, ResourceType.NAME),
            ) { response, relation ->
                debugLog { "getAvailableBackups() = $response" }
                // This callback will be called for every file in the folder
                if (relation != SELF && !response.isFolder() && response.href.pathSize >= 2 &&
                    response.hrefName() == FILE_BACKUP_METADATA
                ) {
                    val tokenName = response.href.pathSegments[response.href.pathSegments.size - 2]
                    getTokenOrNull(tokenName)?.let { token ->
                        tokens.add(token)
                    }
                }
            }
        } catch (e: HttpException) {
            if (e.isUnsupportedPropfind()) getBackupTokenWithDepthOne(davCollection, tokens)
            else throw e
        }
        val tokenIterator = tokens.iterator()
        return generateSequence {
            if (!tokenIterator.hasNext()) return@generateSequence null // end sequence
            val token = tokenIterator.next()
            EncryptedMetadata(token) {
                getInputStream(token, FILE_BACKUP_METADATA)
            }
        }
    }

    private fun getBackupTokenWithDepthOne(davCollection: DavCollection, tokens: ArrayList<Long>) {
        davCollection.propfind(
            depth = 1,
            reqProp = arrayOf(DisplayName.NAME, ResourceType.NAME),
        ) { response, relation ->
            debugLog { "getBackupTokenWithDepthOne() = $response" }

            // we are only interested in sub-folders, skip rest
            if (relation == SELF || !response.isFolder()) return@propfind

            val token = getTokenOrNull(response.hrefName()) ?: return@propfind
            val tokenUrl = response.href.newBuilder()
                .addPathSegment(FILE_BACKUP_METADATA)
                .build()
            // check if .backup.metadata file exists using HEAD request,
            // because some servers (e.g. nginx don't list hidden files with PROPFIND)
            try {
                DavCollection(okHttpClient, tokenUrl).head {
                    debugLog { "getBackupTokenWithDepthOne() = $response" }
                    tokens.add(token)
                }
            } catch (e: Exception) {
                // just log exception and continue, we want to find all files that are there
                Log.e(TAG, "Error retrieving $tokenUrl: ", e)
            }
        }
    }

    private fun getTokenOrNull(name: String): Long? {
        val looksLikeToken = name.isNotEmpty() && tokenRegex.matches(name)
        if (looksLikeToken) {
            return try {
                name.toLong()
            } catch (e: NumberFormatException) {
                throw AssertionError(e) // regex must be wrong
            }
        }
        if (isUnexpectedFile(name)) {
            Log.w(TAG, "Found invalid backup set folder: $name")
        }
        return null
    }

    private fun isUnexpectedFile(name: String): Boolean {
        return name != FILE_NO_MEDIA &&
            !chunkFolderRegex.matches(name) &&
            !name.endsWith(SNAPSHOT_EXT)
    }

    override val providerPackageName: String = context.packageName // 100% built-in plugin

}
