package com.stevesoltys.seedvault.transport.restore

import java.io.IOException
import java.io.InputStream

interface RestorePlugin {

    val kvRestorePlugin: KVRestorePlugin

    val fullRestorePlugin: FullRestorePlugin

    /**
     * Returns an [InputStream] for the given token, for reading an APK that is to be restored.
     */
    @Throws(IOException::class)
    suspend fun getApkInputStream(token: Long, packageName: String, suffix: String): InputStream

}
