package com.stevesoltys.backup.metadata

import androidx.annotation.VisibleForTesting
import com.stevesoltys.backup.Utf8
import com.stevesoltys.backup.crypto.Crypto
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream

interface MetadataWriter {

    @Throws(IOException::class)
    fun write(outputStream: OutputStream, token: Long)

}

class MetadataWriterImpl(private val crypto: Crypto): MetadataWriter {

    @Throws(IOException::class)
    override fun write(outputStream: OutputStream, token: Long) {
        val metadata = BackupMetadata(token = token)
        outputStream.write(ByteArray(1).apply { this[0] = metadata.version })
        crypto.encryptSegment(outputStream, encode(metadata))
    }

    @VisibleForTesting
    internal fun encode(metadata: BackupMetadata): ByteArray {
        val json = JSONObject()
        json.put(JSON_VERSION, metadata.version.toInt())
        json.put(JSON_TOKEN, metadata.token)
        json.put(JSON_ANDROID_VERSION, metadata.androidVersion)
        json.put(JSON_DEVICE_NAME, metadata.deviceName)
        return json.toString().toByteArray(Utf8)
    }

}
