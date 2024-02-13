package com.stevesoltys.seedvault.plugins.webdav

import android.content.Context
import android.util.Log
import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response.HrefRelation.SELF
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.ResourceType
import at.bitfire.dav4jvm.property.ResourceType.Companion.COLLECTION
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.chunkFolderRegex
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.plugins.saf.FILE_NO_MEDIA
import com.stevesoltys.seedvault.plugins.tokenRegex
import com.stevesoltys.seedvault.settings.Storage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val TAG = WebDavStoragePlugin::class.java.simpleName
const val DEBUG_LOG = true
const val DIRECTORY_ROOT = ".SeedVaultAndroidBackup"

@OptIn(DelicateCoroutinesApi::class)
@Suppress("BlockingMethodInNonBlockingContext")
internal class WebDavStoragePlugin(
    context: Context,
    webDavConfig: WebDavConfig,
) : StoragePlugin {

    private val authHandler = BasicDigestAuthHandler(
        domain = null, // Optional, to only authenticate against hosts with this domain.
        username = webDavConfig.username,
        password = webDavConfig.password,
    )
    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .authenticator(authHandler)
        .addNetworkInterceptor(authHandler)
        .build()

    private val url = "${webDavConfig.url}/$DIRECTORY_ROOT"

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        try {
            val location = "$url/$token".toHttpUrl()
            val davCollection = DavCollection(okHttpClient, location)

            val response = suspendCoroutine { cont ->
                davCollection.mkCol(null) { response ->
                    cont.resume(response)
                }
            }
            debugLog { "startNewRestoreSet($token) = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // TODO does it make sense to delete anything
        //  when [startNewRestoreSet] is always called first? Maybe unify both calls?
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
        return try {
            doGetOutputStream(token, name)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting OutputStream for $token and $name: ", e)
        }
    }

    @Throws(IOException::class)
    private suspend fun doGetOutputStream(token: Long, name: String): OutputStream {
        val location = "$url/$token/$name".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val pipedInputStream = PipedInputStream()
        val pipedOutputStream = PipedCloseActionOutputStream(pipedInputStream)

        val body = object : RequestBody() {
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
                debugLog { "getOutputStream($token, $name) = $response" }
            }
        }
        pipedOutputStream.doOnClose {
            runBlocking { // blocking i/o wait
                deferred.await()
            }
        }
        return pipedOutputStream
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        return try {
            doGetInputStream(token, name)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting InputStream for $token and $name: ", e)
        }
    }

    @Throws(IOException::class)
    private fun doGetInputStream(token: Long, name: String): InputStream {
        val location = "$url/$token/$name".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val pipedInputStream = PipedInputStream()
        val pipedOutputStream = PipedOutputStream(pipedInputStream)

        GlobalScope.launch(Dispatchers.IO) {
            davCollection.get(accept = "", headers = null) { response ->
                val inputStream = response.body?.byteStream()
                    ?: throw IOException("No response body")
                debugLog { "getInputStream($token, $name) = $response" }
                pipedOutputStream.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return pipedInputStream
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

    @Throws(IOException::class)
    override suspend fun hasBackup(storage: Storage): Boolean {
        // TODO this requires refactoring
        return true
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>? {
        return try {
            doGetAvailableBackups()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available backups: ", e)
            null
        }
    }

    private suspend fun doGetAvailableBackups(): Sequence<EncryptedMetadata> {
        val location = url.toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        // get all restore set tokens in root folder
        val tokens = ArrayList<Long>()
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
        val tokenIterator = tokens.iterator()
        return generateSequence {
            if (!tokenIterator.hasNext()) return@generateSequence null // end sequence
            val token = tokenIterator.next()
            EncryptedMetadata(token) {
                getInputStream(token, FILE_BACKUP_METADATA)
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
            !name.endsWith(".SeedSnap")
    }

    private fun Response.isFolder(): Boolean {
        return this[ResourceType::class.java]?.types?.contains(COLLECTION) == true
    }

    override val providerPackageName: String = context.packageName // 100% built-in plugin

    private class PipedCloseActionOutputStream(
        inputStream: PipedInputStream,
    ) : PipedOutputStream(inputStream) {

        private var onClose: (() -> Unit)? = null

        @Throws(IOException::class)
        override fun close() {
            super.close()
            try {
                onClose?.invoke()
            } catch (e: Exception) {
                if (e is IOException) throw e
                else throw IOException(e)
            }
        }

        fun doOnClose(function: () -> Unit) {
            this.onClose = function
        }
    }

    private fun debugLog(block: () -> String) {
        if (DEBUG_LOG) Log.d(TAG, block())
    }

}
