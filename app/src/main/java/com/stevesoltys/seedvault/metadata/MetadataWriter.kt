package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.crypto.Crypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream

interface MetadataWriter {
    @Throws(IOException::class)
    fun write(metadata: BackupMetadata, outputStream: OutputStream)

    fun encode(metadata: BackupMetadata): ByteArray
}

internal class MetadataWriterImpl(private val crypto: Crypto) : MetadataWriter {

    @Throws(IOException::class)
    override fun write(metadata: BackupMetadata, outputStream: OutputStream) {
        outputStream.write(ByteArray(1).apply { this[0] = metadata.version })
        crypto.encryptMultipleSegments(outputStream, encode(metadata))
    }

    override fun encode(metadata: BackupMetadata): ByteArray {
        val json = JSONObject().apply {
            put(JSON_METADATA, JSONObject().apply {
                put(JSON_METADATA_VERSION, metadata.version.toInt())
                put(JSON_METADATA_TOKEN, metadata.token)
                put(JSON_METADATA_TIME, metadata.time)
                put(JSON_METADATA_SDK_INT, metadata.androidVersion)
                put(JSON_METADATA_INCREMENTAL, metadata.androidIncremental)
                put(JSON_METADATA_NAME, metadata.deviceName)
            })
        }
        for ((packageName, packageMetadata) in metadata.packageMetadata) {
            json.put(packageName, JSONObject().apply {
                put(JSON_PACKAGE_TIME, packageMetadata.time)
                packageMetadata.version?.let { put(JSON_PACKAGE_VERSION, it) }
                packageMetadata.installer?.let { put(JSON_PACKAGE_INSTALLER, it) }
                packageMetadata.signatures?.let { put(JSON_PACKAGE_SIGNATURES, JSONArray(it)) }
            })
        }
        return json.toString().toByteArray(Utf8)
    }

}
