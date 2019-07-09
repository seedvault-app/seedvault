package com.stevesoltys.backup.transport.backup

import java.io.IOException

interface BackupPlugin {

    val kvBackupPlugin: KVBackupPlugin

    val fullBackupPlugin: FullBackupPlugin

    /**
     * Initialize the storage for this device, erasing all stored data.
     */
    @Throws(IOException::class)
    fun initializeDevice()

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
