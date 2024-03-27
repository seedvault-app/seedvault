/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.plugin

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.grobox.storagebackuptester.crypto.KeyManager
import org.calyxos.backup.storage.plugin.saf.SafStoragePlugin
import java.io.IOException
import java.io.OutputStream
import javax.crypto.SecretKey

@Suppress("BlockingMethodInNonBlockingContext")
class TestSafStoragePlugin(
    appContext: Context,
    private val getLocationUri: () -> Uri?,
) : SafStoragePlugin(appContext) {

    override val context = appContext
    override val root: DocumentFile?
        get() {
            val uri = getLocationUri() ?: return null
            return DocumentFile.fromTreeUri(context, uri) ?: error("No doc file from tree Uri")
        }

    private val nullStream = object : OutputStream() {
        override fun write(b: Int) {
            // oops
        }
    }

    override fun getMasterKey(): SecretKey {
        return KeyManager.getMasterKey()
    }

    override fun hasMasterKey(): Boolean {
        return KeyManager.hasMasterKey()
    }

    @Throws(IOException::class)
    override suspend fun getChunkOutputStream(chunkId: String): OutputStream {
        if (getLocationUri() == null) return nullStream
        return super.getChunkOutputStream(chunkId)
    }

    override suspend fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        if (root == null) return nullStream
        return super.getBackupSnapshotOutputStream(timestamp)
    }

}
