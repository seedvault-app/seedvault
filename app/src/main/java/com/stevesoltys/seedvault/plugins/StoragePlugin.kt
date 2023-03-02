/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins

import android.app.backup.RestoreSet
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface StoragePlugin<T> {

    /**
     * Returns true if the plugin is working, or false if it isn't.
     * @throws Exception any kind of exception to provide more info on the error
     */
    suspend fun test(): Boolean

    /**
     * Retrieves the available storage space in bytes.
     * @return the number of bytes available or null if the number is unknown.
     * Returning a negative number or zero to indicate unknown is discouraged.
     */
    suspend fun getFreeSpace(): Long?

    /**
     * Start a new [RestoreSet] with the given token.
     *
     * This is typically followed by a call to [initializeDevice].
     */
    @Throws(IOException::class)
    suspend fun startNewRestoreSet(token: Long)

    /**
     * Initialize the storage for this device, erasing all stored data in the current [RestoreSet].
     */
    @Throws(IOException::class)
    suspend fun initializeDevice()

    /**
     * Return true if there is data stored for the given name.
     */
    @Throws(IOException::class)
    suspend fun hasData(token: Long, name: String): Boolean

    /**
     * Return a raw byte stream for writing data for the given name.
     */
    @Throws(IOException::class)
    suspend fun getOutputStream(token: Long, name: String): OutputStream

    /**
     * Return a raw byte stream with data for the given name.
     */
    @Throws(IOException::class)
    suspend fun getInputStream(token: Long, name: String): InputStream

    /**
     * Remove all data associated with the given name.
     */
    @Throws(IOException::class)
    suspend fun removeData(token: Long, name: String)

    /**
     * Get the set of all backups currently available for restore.
     *
     * @return metadata for the set of restore images available,
     * or null if an error occurred (the attempt should be rescheduled).
     **/
    suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>?

    /**
     * Returns the package name of the app that provides the backend storage
     * which is used for the current backup location.
     *
     * Plugins are advised to cache this as it will be requested frequently.
     *
     * @return null if no package name could be found
     */
    val providerPackageName: String?

}

class EncryptedMetadata(val token: Long, val inputStreamRetriever: suspend () -> InputStream)

internal val tokenRegex = Regex("([0-9]{13})") // good until the year 2286
internal val chunkFolderRegex = Regex("[a-f0-9]{2}")
