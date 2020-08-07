package com.stevesoltys.seedvault.transport.backup

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.OutputStream

interface BackupPlugin {

    val kvBackupPlugin: KVBackupPlugin

    val fullBackupPlugin: FullBackupPlugin

    /**
     * Initialize the storage for this device, erasing all stored data.
     *
     * @return true if the device needs initialization or
     * false if the device was initialized already and initialization should be a no-op.
     */
    @Throws(IOException::class)
    suspend fun initializeDevice(newToken: Long): Boolean

    /**
     * Returns an [OutputStream] for writing backup metadata.
     */
    @Throws(IOException::class)
    suspend fun getMetadataOutputStream(): OutputStream

    /**
     * Returns an [OutputStream] for writing an APK to be backed up.
     */
    @Throws(IOException::class)
    suspend fun getApkOutputStream(packageInfo: PackageInfo): OutputStream

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
