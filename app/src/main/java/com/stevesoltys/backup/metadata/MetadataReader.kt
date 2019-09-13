package com.stevesoltys.backup.metadata

import androidx.annotation.VisibleForTesting
import com.stevesoltys.backup.Utf8
import com.stevesoltys.backup.crypto.Crypto
import com.stevesoltys.backup.header.UnsupportedVersionException
import com.stevesoltys.backup.header.VERSION
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import javax.crypto.AEADBadTagException

interface MetadataReader {

    @Throws(FormatException::class, SecurityException::class, UnsupportedVersionException::class, IOException::class)
    fun readMetadata(inputStream: InputStream, expectedToken: Long): BackupMetadata

}

class MetadataReaderImpl(private val crypto: Crypto) : MetadataReader {

    @Throws(FormatException::class, SecurityException::class, UnsupportedVersionException::class, IOException::class)
    override fun readMetadata(inputStream: InputStream, expectedToken: Long): BackupMetadata {
        val version = inputStream.read().toByte()
        if (version < 0) throw IOException()
        if (version > VERSION) throw UnsupportedVersionException(version)
        val metadataBytes = try {
            crypto.decryptSegment(inputStream)
        } catch (e: AEADBadTagException) {
            // TODO use yet another exception?
            throw SecurityException(e)
        }
        return decode(metadataBytes, version, expectedToken)
    }

    @VisibleForTesting
    @Throws(FormatException::class, SecurityException::class)
    internal fun decode(bytes: ByteArray, expectedVersion: Byte, expectedToken: Long): BackupMetadata {
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
                throw SecurityException("Invalid token '$token' in metadata, expected '$expectedToken'.")
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
