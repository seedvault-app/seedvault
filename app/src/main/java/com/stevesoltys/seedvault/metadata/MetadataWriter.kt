package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
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
        crypto.newEncryptingStream(outputStream, getAD(metadata.version, metadata.token)).use {
            it.write(encode(metadata))
        }
    }

    override fun encode(metadata: BackupMetadata): ByteArray {
        val json = JSONObject().apply {
            put(JSON_METADATA, JSONObject().apply {
                put(JSON_METADATA_VERSION, metadata.version.toInt())
                put(JSON_METADATA_TOKEN, metadata.token)
                put(JSON_METADATA_SALT, metadata.salt)
                put(JSON_METADATA_TIME, metadata.time)
                put(JSON_METADATA_SDK_INT, metadata.androidVersion)
                put(JSON_METADATA_INCREMENTAL, metadata.androidIncremental)
                put(JSON_METADATA_NAME, metadata.deviceName)
            })
        }
        for ((packageName, packageMetadata) in metadata.packageMetadataMap) {
            json.put(packageName, JSONObject().apply {
                put(JSON_PACKAGE_TIME, packageMetadata.time)
                if (packageMetadata.state != APK_AND_DATA) {
                    put(JSON_PACKAGE_STATE, packageMetadata.state.name)
                }
                // We can't require a backup type in metadata at this point,
                // only when version > 0 and we have actual restore data
                if (packageMetadata.backupType != null) {
                    put(JSON_PACKAGE_BACKUP_TYPE, packageMetadata.backupType!!.name)
                }
                if (packageMetadata.system) {
                    put(JSON_PACKAGE_SYSTEM, packageMetadata.system)
                }
                packageMetadata.version?.let { put(JSON_PACKAGE_VERSION, it) }
                packageMetadata.installer?.let { put(JSON_PACKAGE_INSTALLER, it) }
                packageMetadata.splits?.let { splits ->
                    put(JSON_PACKAGE_SPLITS, JSONArray().apply {
                        for (split in splits) put(JSONObject().apply {
                            put(JSON_PACKAGE_SPLIT_NAME, split.name)
                            put(JSON_PACKAGE_SHA256, split.sha256)
                        })
                    })
                }
                packageMetadata.sha256?.let { put(JSON_PACKAGE_SHA256, it) }
                packageMetadata.signatures?.let { put(JSON_PACKAGE_SIGNATURES, JSONArray(it)) }
            })
        }
        return json.toString().toByteArray(Utf8)
    }

}
