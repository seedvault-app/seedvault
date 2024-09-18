/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.webdav

import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response.HrefRelation.SELF
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.property.webdav.QuotaAvailableBytes
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_METADATA
import org.calyxos.seedvault.core.backends.Constants.appSnapshotRegex
import org.calyxos.seedvault.core.backends.Constants.blobFolderRegex
import org.calyxos.seedvault.core.backends.Constants.chunkFolderRegex
import org.calyxos.seedvault.core.backends.Constants.chunkRegex
import org.calyxos.seedvault.core.backends.Constants.fileFolderRegex
import org.calyxos.seedvault.core.backends.Constants.fileSnapshotRegex
import org.calyxos.seedvault.core.backends.Constants.repoIdRegex
import org.calyxos.seedvault.core.backends.Constants.tokenRegex
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.FileHandle
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.TopLevelFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KClass

private const val DEBUG_LOG = true

@OptIn(DelicateCoroutinesApi::class)
public class WebDavBackend(
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) : Backend {

    private val log = KotlinLogging.logger {}

    private val authHandler = BasicDigestAuthHandler(
        domain = null, // Optional, to only authenticate against hosts with this domain.
        username = webDavConfig.username,
        password = webDavConfig.password,
    )
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .authenticator(authHandler)
        .addNetworkInterceptor(authHandler)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .retryOnConnectionFailure(true)
        .build()

    private val baseUrl = webDavConfig.url.trimEnd('/')
    private val url = "$baseUrl/$root"
    private val folders = mutableSetOf<HttpUrl>() // cache for existing/created folders

    override suspend fun test(): Boolean {
        val location = "$baseUrl/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val hasCaps = suspendCoroutine { cont ->
            davCollection.options { davCapabilities, response ->
                log.debugLog { "test() = $davCapabilities $response" }
                if (davCapabilities.contains("1")) cont.resume(true)
                else if (davCapabilities.contains("2")) cont.resume(true)
                else if (davCapabilities.contains("3")) cont.resume(true)
                else cont.resume(false)
            }
        }
        if (!hasCaps) return false

        val rootCollection = DavCollection(okHttpClient, "$url/foo".toHttpUrl())
        rootCollection.ensureFoldersExist(log, folders) // only considers parents, so foo isn't used
        return true
    }

    override suspend fun getFreeSpace(): Long? {
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val availableBytes = suspendCoroutine { cont ->
            davCollection.propfind(depth = 0, QuotaAvailableBytes.NAME) { response, _ ->
                log.debugLog { "getFreeSpace() = $response" }
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

    override suspend fun save(handle: FileHandle): OutputStream {
        val location = handle.toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)
        davCollection.ensureFoldersExist(log, folders)

        val pipedInputStream = PipedInputStream()
        val pipedOutputStream = PipedCloseActionOutputStream(pipedInputStream)

        val body = object : RequestBody() {
            override fun isOneShot(): Boolean = true
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                pipedInputStream.use { inputStream ->
                    sink.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
        val deferred = GlobalScope.async(Dispatchers.IO) {
            davCollection.put(body) { response ->
                log.debugLog { "save($location) = $response" }
            }
        }
        pipedOutputStream.doOnClose {
            runBlocking { // blocking i/o wait
                deferred.await()
            }
        }
        return pipedOutputStream
    }

    override suspend fun load(handle: FileHandle): InputStream {
        val location = handle.toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val response = try {
            davCollection.get(accept = "", headers = null)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error loading $location", e)
        }
        log.debugLog { "load($location) = $response" }
        if (response.code / 100 != 2) throw IOException("HTTP error ${response.code}")
        return response.body?.byteStream() ?: throw IOException("Body was null for $location")
    }

    override suspend fun list(
        topLevelFolder: TopLevelFolder?,
        vararg fileTypes: KClass<out FileHandle>,
        callback: (FileInfo) -> Unit,
    ) {
        if (TopLevelFolder::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile.IconsFile::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile.Blob::class in fileTypes) throw UnsupportedOperationException()

        // limit depth based on wanted types and if top-level folder is given
        var depth = if (FileBackupFileType.Blob::class in fileTypes ||
            AppBackupFileType.Blob::class in fileTypes
        ) 3 else 2
        if (topLevelFolder != null) depth -= 1

        val location = if (topLevelFolder == null) {
            "$url/".toHttpUrl()
        } else {
            "$url/${topLevelFolder.name}/".toHttpUrl()
        }
        val davCollection = DavCollection(okHttpClient, location)
        val tokenFolders = mutableSetOf<HttpUrl>()
        try {
            davCollection.propfindDepthInfinity(depth) { response, relation ->
                log.debugLog { "list() = $response" }

                // work around nginx's inability to find files starting with .
                if (relation != SELF && LegacyAppBackupFile.Metadata::class in fileTypes &&
                    response.isFolder() && response.hrefName().matches(tokenRegex)
                ) {
                    tokenFolders.add(response.href)
                }
                if (relation != SELF && !response.isFolder() && response.href.pathSize >= 2) {
                    val name = response.hrefName()
                    val parentName = response.href.pathSegments[response.href.pathSegments.size - 2]

                    if (AppBackupFileType.Snapshot::class in fileTypes ||
                        AppBackupFileType::class in fileTypes
                    ) {
                        val match = appSnapshotRegex.matchEntire(name)
                        if (match != null && repoIdRegex.matches(parentName)) {
                            val size = response.properties.contentLength()
                            val snapshot = AppBackupFileType.Snapshot(
                                repoId = parentName,
                                hash = match.groupValues[1],
                            )
                            callback(FileInfo(snapshot, size))
                        }
                    }
                    if ((AppBackupFileType.Blob::class in fileTypes ||
                            AppBackupFileType::class in fileTypes) && response.href.pathSize >= 3
                    ) {
                        val repoId = response.href.pathSegments[response.href.pathSegments.size - 3]
                        if (repoIdRegex.matches(repoId) &&
                            blobFolderRegex.matches(parentName)
                        ) {
                            if (chunkRegex.matches(name)) {
                                val blob = AppBackupFileType.Blob(
                                    repoId = repoId,
                                    name = name,
                                )
                                val size = response.properties.contentLength()
                                callback(FileInfo(blob, size))
                            }
                        }
                    }
                    if (FileBackupFileType.Snapshot::class in fileTypes ||
                        FileBackupFileType::class in fileTypes
                    ) {
                        val match = fileSnapshotRegex.matchEntire(name)
                        if (match != null) {
                            val size = response.properties.contentLength()
                            val snapshot = FileBackupFileType.Snapshot(
                                androidId = parentName.substringBefore('.'),
                                time = match.groupValues[1].toLong(),
                            )
                            callback(FileInfo(snapshot, size))
                        }
                    }
                    if ((FileBackupFileType.Blob::class in fileTypes ||
                            FileBackupFileType::class in fileTypes) && response.href.pathSize >= 3
                    ) {
                        val androidIdSv =
                            response.href.pathSegments[response.href.pathSegments.size - 3]
                        if (fileFolderRegex.matches(androidIdSv) &&
                            chunkFolderRegex.matches(parentName)
                        ) {
                            if (chunkRegex.matches(name)) {
                                val blob = FileBackupFileType.Blob(
                                    androidId = androidIdSv.substringBefore('.'),
                                    name = name,
                                )
                                val size = response.properties.contentLength()
                                callback(FileInfo(blob, size))
                            }
                        }
                    }
                    if (LegacyAppBackupFile.Metadata::class in fileTypes) {
                        if (name == FILE_BACKUP_METADATA && parentName.matches(tokenRegex)) {
                            val metadata = LegacyAppBackupFile.Metadata(parentName.toLong())
                            val size = response.properties.contentLength()
                            callback(FileInfo(metadata, size))
                            // we can find .backup.metadata files, so no need for nginx workaround
                            tokenFolders.clear()
                        }
                    }
                }
            }
        } catch (e: NotFoundException) {
            log.warn(e) { "$location not found" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error listing $location", e)
        }
        // direct query for .backup.metadata as nginx doesn't support listing hidden files
        tokenFolders.forEach { url ->
            val metadataLocation = url.newBuilder().addPathSegment(FILE_BACKUP_METADATA).build()
            try {
                DavCollection(okHttpClient, metadataLocation).head { response ->
                    log.debugLog { "head($metadataLocation) = $response" }
                    val token = url.pathSegments.last { it.isNotBlank() }.toLong()
                    val metadata = LegacyAppBackupFile.Metadata(token)
                    val size = response.headers["content-length"]?.toLong()
                        ?: error("no content length")
                    callback(FileInfo(metadata, size))
                }
            } catch (e: Exception) {
                log.warn { "No $FILE_BACKUP_METADATA found in $url: $e" }
            }
        }
    }

    override suspend fun remove(handle: FileHandle) {
        val location = handle.toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        log.debugLog { "remove($handle)" }

        try {
            val response = suspendCoroutine { cont ->
                davCollection.delete { response ->
                    cont.resume(response)
                }
            }
            log.debugLog { "remove($location) = $response" }
        } catch (e: Exception) {
            when (e) {
                is NotFoundException -> log.info { "Not found: $location" }
                is IOException -> throw e
                else -> throw IOException(e)
            }
        }
    }

    /**
     * Renames [from] to [to].
     *
     * @throws HttpException if [to] already exists
     * * nginx   code 412
     * * lighttp code 207
     * * dufs    code 500
     */
    @Throws(HttpException::class)
    override suspend fun rename(from: TopLevelFolder, to: TopLevelFolder) {
        val location = "$url/${from.name}/".toHttpUrl()
        val toUrl = "$url/${to.name}/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)
        try {
            davCollection.move(toUrl, false) { response ->
                log.debugLog { "rename(${from.name}, ${to.name}) = $response" }
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error renaming $location to ${to.name}", e)
        }
    }

    override suspend fun removeAll() {
        val location = "$url/".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)
        try {
            davCollection.delete { response ->
                log.debugLog { "removeAll() = $response" }
            }
        } catch (e: NotFoundException) {
            log.info { "Not found: $location" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error removing all at $location", e)
        }
    }

    override val providerPackageName: String? = null // 100% built-in plugin

    private fun FileHandle.toHttpUrl(): HttpUrl = when (this) {
        // careful with trailing slashes, use only for folders/collections
        is TopLevelFolder -> "$url/$name/".toHttpUrl()
        else -> "$url/$relativePath".toHttpUrl()
    }

}

internal inline fun KLogger.debugLog(crossinline block: () -> String) {
    if (DEBUG_LOG) debug { block() }
}
