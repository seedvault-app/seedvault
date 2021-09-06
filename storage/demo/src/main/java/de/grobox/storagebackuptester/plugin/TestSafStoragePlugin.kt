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
    private val context: Context,
    private val getLocationUri: () -> Uri?,
) : SafStoragePlugin(context) {

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
    override fun getChunkOutputStream(chunkId: String): OutputStream {
        if (getLocationUri() == null) return nullStream
        return super.getChunkOutputStream(chunkId)
    }

    override fun getBackupSnapshotOutputStream(timestamp: Long): OutputStream {
        if (root == null) return nullStream
        return super.getBackupSnapshotOutputStream(timestamp)
    }

}
