package com.stevesoltys.backup.metadata

import com.stevesoltys.backup.Utf8
import org.json.JSONException
import org.json.JSONObject

interface MetadataDecoder {

    @Throws(FormatException::class, SecurityException::class)
    fun decode(bytes: ByteArray, expectedVersion: Byte, expectedToken: Long): BackupMetadata

}

class MetadataDecoderImpl : MetadataDecoder {

    @Throws(FormatException::class, SecurityException::class)
    override fun decode(bytes: ByteArray, expectedVersion: Byte, expectedToken: Long): BackupMetadata {
        // NOTE: We don't do extensive validation of the parsed input here,
        // because it was encrypted with authentication, so we should be able to trust it.
        //
        // However, it is important to ensure that the expected unauthenticated version and token
        // matches the authenticated version and token in the JSON.
        try {
            val json = JSONObject(bytes.toString(Utf8))
            val version = json.getInt(JSON_VERSION).toByte()
            if (version != expectedVersion) {
                throw SecurityException("Invalid version '${version.toInt()}' in metadata, expected '${expectedVersion.toInt()}'.")
            }
            val token = json.getLong(JSON_TOKEN)
            if (token != expectedToken) {
                throw SecurityException("Invalid token '$expectedVersion' in metadata, expected '$expectedToken'.")
            }
            return BackupMetadata(
                    version = version,
                    token = token,
                    androidVersion = json.getInt(JSON_ANDROID_VERSION),
                    deviceName = json.getString(JSON_DEVICE_NAME)
            )
        } catch (e: JSONException) {
            throw FormatException(e)
        }
    }

}
