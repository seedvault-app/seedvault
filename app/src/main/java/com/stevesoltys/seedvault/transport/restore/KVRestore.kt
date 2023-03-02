/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.TRANSPORT_ERROR
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import com.stevesoltys.seedvault.ANCESTRAL_RECORD_KEY
import com.stevesoltys.seedvault.GLOBAL_METADATA_KEY
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.decodeBase64
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.getADForKV
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.transport.backup.KVDb
import com.stevesoltys.seedvault.transport.backup.KvDbManager
import libcore.io.IoUtils.closeQuietly
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.zip.GZIPInputStream
import javax.crypto.AEADBadTagException

private class KVRestoreState(
    val version: Byte,
    val token: Long,
    val name: String,
    val packageInfo: PackageInfo,
    /**
     * Optional [PackageInfo] for single package restore, optimizes restore of @pm@
     */
    val autoRestorePackageInfo: PackageInfo?,
)

private val TAG = KVRestore::class.java.simpleName

internal class KVRestore(
    private val pluginManager: StoragePluginManager,
    @Suppress("Deprecation")
    private val legacyPlugin: LegacyStoragePlugin,
    private val outputFactory: OutputFactory,
    private val headerReader: HeaderReader,
    private val crypto: Crypto,
    private val dbManager: KvDbManager,
) {

    private val plugin get() = pluginManager.appPlugin
    private var state: KVRestoreState? = null

    /**
     * Return true if there are records stored for the given package.
     *
     * Deprecated. Use only for v0 backups.
     */
    @Throws(IOException::class)
    @Deprecated("Use BackupPlugin#hasData() instead")
    suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        return legacyPlugin.hasDataForPackage(token, packageInfo)
    }

    /**
     * This prepares to restore the given package from the given restore token.
     *
     * It is possible that the system decides to not restore the package.
     * Then a new state will be initialized right away without calling other methods.
     *
     * @param autoRestorePackageInfo single optional [PackageInfo] to optimize restore of @pm@
     */
    fun initializeState(
        version: Byte,
        token: Long,
        name: String,
        packageInfo: PackageInfo,
        autoRestorePackageInfo: PackageInfo? = null,
    ) {
        state = KVRestoreState(version, token, name, packageInfo, autoRestorePackageInfo)
    }

    /**
     * Get the data for the current package.
     *
     * @param data An open, writable file into which the key/value backup data should be stored.
     * @return One of [TRANSPORT_OK]
     * or [TRANSPORT_ERROR] (an error occurred, the restore should be aborted and rescheduled).
     */
    suspend fun getRestoreData(data: ParcelFileDescriptor): Int {
        val state = this.state ?: throw IllegalStateException("no state")

        // take legacy path for version 0
        if (state.version == 0x00.toByte()) return getRestoreDataV0(state, data)

        val pmPackageName = state.autoRestorePackageInfo?.packageName
        val isAutoRestore = state.packageInfo.packageName == MAGIC_PACKAGE_MANAGER &&
            pmPackageName != null
        return try {
            val database = if (isAutoRestore) {
                getCachedRestoreDb(state)
            } else {
                downloadRestoreDb(state)
            }
            database.use { db ->
                val out = outputFactory.getBackupDataOutput(data)
                val records = if (isAutoRestore) {
                    val keys = listOf(ANCESTRAL_RECORD_KEY, GLOBAL_METADATA_KEY, pmPackageName)
                    Log.d(TAG, "Single package restore, restrict restore keys to $pmPackageName")
                    db.getAll().filter { it.first in keys }
                } else {
                    db.getAll()
                }
                records.sortedBy { it.first }.forEach { (key, value) ->
                    val size = value.size
                    Log.v(TAG, "    ... key=$key size=$size")
                    out.writeEntityHeader(key, size)
                    out.writeEntityData(value, size)
                }
            }
            TRANSPORT_OK
        } catch (e: UnsupportedVersionException) {
            Log.e(TAG, "Unsupported version in backup: ${e.version}", e)
            TRANSPORT_ERROR
        } catch (e: IOException) {
            Log.e(TAG, "Unable to process K/V backup database", e)
            TRANSPORT_ERROR
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "General security exception while reading backup database", e)
            TRANSPORT_ERROR
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "Decryption failed", e)
            TRANSPORT_ERROR
        } finally {
            dbManager.deleteDb(state.packageInfo.packageName, true)
            this.state = null
            closeQuietly(data)
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class, UnsupportedVersionException::class)
    private suspend fun getCachedRestoreDb(state: KVRestoreState): KVDb {
        val packageName = state.packageInfo.packageName
        return if (dbManager.existsDb(packageName)) {
            dbManager.getDb(packageName)
        } else {
            downloadRestoreDb(state)
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class, UnsupportedVersionException::class)
    private suspend fun downloadRestoreDb(state: KVRestoreState): KVDb {
        val packageName = state.packageInfo.packageName
        plugin.getInputStream(state.token, state.name).use { inputStream ->
            headerReader.readVersion(inputStream, state.version)
            val ad = getADForKV(VERSION, packageName)
            crypto.newDecryptingStream(inputStream, ad).use { decryptedStream ->
                GZIPInputStream(decryptedStream).use { gzipStream ->
                    dbManager.getDbOutputStream(packageName).use { outputStream ->
                        gzipStream.copyTo(outputStream)
                    }
                }
            }
        }
        return dbManager.getDb(packageName, true)
    }

    //
    // v0 restore legacy code below
    //

    private suspend fun getRestoreDataV0(state: KVRestoreState, data: ParcelFileDescriptor): Int {
        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory.
        // We return the data in lexical order sorted by key,
        // so that apps which use synthetic keys like BLOB_1, BLOB_2, etc
        // will see the date in the most obvious order.
        val sortedKeys = getSortedKeysV0(state.token, state.packageInfo)
        if (sortedKeys == null) {
            // nextRestorePackage() ensures the dir exists, so this is an error
            Log.e(TAG, "No keys for package: ${state.packageInfo.packageName}")
            return TRANSPORT_ERROR
        }

        // We expect at least some data if the directory exists in the first place
        Log.v(TAG, "  getRestoreData() found ${sortedKeys.size} key files")

        return try {
            val dataOutput = outputFactory.getBackupDataOutput(data)
            for (keyEntry in sortedKeys) {
                readAndWriteValueV0(state, keyEntry, dataOutput)
            }
            TRANSPORT_OK
        } catch (e: IOException) {
            Log.e(TAG, "Unable to read backup records", e)
            TRANSPORT_ERROR
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception while reading backup records", e)
            TRANSPORT_ERROR
        } catch (e: UnsupportedVersionException) {
            Log.e(TAG, "Unsupported version in backup: ${e.version}", e)
            TRANSPORT_ERROR
        } catch (e: AEADBadTagException) {
            Log.e(TAG, "Decryption failed", e)
            TRANSPORT_ERROR
        } finally {
            this.state = null
            closeQuietly(data)
        }
    }

    /**
     * Return a list of the records (represented by key files) in the given directory,
     * sorted lexically by the Base64-decoded key file name, not by the on-disk filename.
     */
    private suspend fun getSortedKeysV0(token: Long, packageInfo: PackageInfo): List<DecodedKey>? {
        val records: List<String> = try {
            legacyPlugin.listRecords(token, packageInfo)
        } catch (e: IOException) {
            return null
        }
        if (records.isEmpty()) return null

        // Decode the key filenames into keys then sort lexically by key
        val contents = ArrayList<DecodedKey>().apply {
            for (recordKey in records) add(DecodedKey(recordKey))
        }
        contents.sort()
        return contents
    }

    /**
     * Read the encrypted value for the given key and write it to the given [BackupDataOutput].
     */
    @Suppress("Deprecation")
    @Throws(IOException::class, UnsupportedVersionException::class, GeneralSecurityException::class)
    private suspend fun readAndWriteValueV0(
        state: KVRestoreState,
        dKey: DecodedKey,
        out: BackupDataOutput,
    ) = legacyPlugin.getInputStreamForRecord(state.token, state.packageInfo, dKey.base64Key)
        .use { inputStream ->
            val version = headerReader.readVersion(inputStream, state.version)
            val packageName = state.packageInfo.packageName
            crypto.decryptHeader(inputStream, version, packageName, dKey.key)
            val value = crypto.decryptMultipleSegments(inputStream)
            val size = value.size
            Log.v(TAG, "    ... key=${dKey.key} size=$size")

            out.writeEntityHeader(dKey.key, size)
            out.writeEntityData(value, size)
            Unit
        }

    private class DecodedKey(val base64Key: String) : Comparable<DecodedKey> {
        val key = base64Key.decodeBase64()

        override fun compareTo(other: DecodedKey) = key.compareTo(other.key)
    }

}
