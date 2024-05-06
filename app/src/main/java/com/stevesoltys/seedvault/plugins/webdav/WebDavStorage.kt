/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import android.util.Log
import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.property.ResourceType
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.internal.closeQuietly
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

const val DEBUG_LOG = true
const val DIRECTORY_ROOT = ".SeedVaultAndroidBackup"

@OptIn(DelicateCoroutinesApi::class)
internal abstract class WebDavStorage(
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) {

    companion object {
        val TAG: String = WebDavStorage::class.java.simpleName
    }

    private val authHandler = BasicDigestAuthHandler(
        domain = null, // Optional, to only authenticate against hosts with this domain.
        username = webDavConfig.username,
        password = webDavConfig.password,
    )
    protected val okHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .authenticator(authHandler)
        .addNetworkInterceptor(authHandler)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .retryOnConnectionFailure(true)
        .build()

    protected val baseUrl = webDavConfig.url
    protected val url = "${webDavConfig.url}/$root"

    @Throws(IOException::class)
    protected suspend fun getOutputStream(location: HttpUrl): OutputStream {
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
                debugLog { "getOutputStream($location) = $response" }
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
    protected fun getInputStream(location: HttpUrl): InputStream {
        val davCollection = DavCollection(okHttpClient, location)

        val pipedInputStream = PipedExceptionInputStream()
        val pipedOutputStream = PipedOutputStream(pipedInputStream)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                davCollection.get(accept = "", headers = null) { response ->
                    val inputStream = response.body?.byteStream()
                        ?: throw IOException("No response body")
                    debugLog { "getInputStream($location) = $response" }
                    pipedOutputStream.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                debugLog { "Exception while getting input stream: $e" }
                // pass exception to stream, so it gets thrown when stream is closed
                // if we'd just throw it here, it would be uncaught, on a different thread
                pipedInputStream.throwable = e
                pipedOutputStream.closeQuietly()
            }
        }
        return pipedInputStream
    }

    protected suspend fun DavCollection.createFolder(xmlBody: String? = null): okhttp3.Response {
        return try {
            suspendCoroutine { cont ->
                mkCol(xmlBody) { response ->
                    cont.resume(response)
                }
            }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    protected inline fun debugLog(block: () -> String) {
        if (DEBUG_LOG) Log.d(TAG, block())
    }

    protected fun Response.isFolder(): Boolean {
        return this[ResourceType::class.java]?.types?.contains(ResourceType.COLLECTION) == true
    }

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

    private class PipedExceptionInputStream : PipedInputStream() {
        var throwable: Throwable? = null

        override fun close() {
            super.close()
            throwable?.let { e ->
                if (e is IOException) throw e
                else throw IOException(e)
            }
        }
    }

}
