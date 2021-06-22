package com.stevesoltys.seedvault.transport.backup

import android.app.backup.RestoreSet
import android.content.pm.PackageInfo
import java.io.IOException
import java.io.OutputStream

interface BackupPlugin {

    val kvBackupPlugin: KVBackupPlugin

    val fullBackupPlugin: FullBackupPlugin

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
     * Returns an [OutputStream] for writing backup metadata.
     */
    @Throws(IOException::class)
    suspend fun getMetadataOutputStream(): OutputStream

    /**
     * Returns an [OutputStream] for writing an APK to be backed up.
     */
    @Throws(IOException::class)
    suspend fun getApkOutputStream(packageInfo: PackageInfo, suffix: String): OutputStream

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
