/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.metadata

import com.stevesoltys.seedvault.Utf8
import com.stevesoltys.seedvault.metadata.PackageState.APK_AND_DATA
import org.json.JSONObject

interface MetadataWriter {
    fun encode(metadata: BackupMetadata): ByteArray
}

internal class MetadataWriterImpl : MetadataWriter {

    override fun encode(metadata: BackupMetadata): ByteArray {
        val json = JSONObject().apply {
            put(JSON_METADATA, JSONObject())
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
                if (packageMetadata.size != null) {
                    put(JSON_PACKAGE_SIZE, packageMetadata.size)
                }
            })
        }
        return json.toString().toByteArray(Utf8)
    }
}
