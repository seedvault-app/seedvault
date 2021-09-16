package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

@WorkerThread
@Suppress("BlockingMethodInNonBlockingContext") // all methods do I/O
internal class DocumentsProviderRestorePlugin(
    private val context: Context,
    private val storage: DocumentsStorage,
    override val kvRestorePlugin: KVRestorePlugin,
    override val fullRestorePlugin: FullRestorePlugin
) : RestorePlugin {

    @Throws(IOException::class)
    override suspend fun getApkInputStream(
        token: Long,
        packageName: String,
        suffix: String
    ): InputStream {
        val setDir = storage.getSetDir(token) ?: throw IOException()
        val file = setDir.findFileBlocking(context, "$packageName$suffix.apk")
            ?: throw FileNotFoundException()
        return storage.getInputStream(file)
    }

}
