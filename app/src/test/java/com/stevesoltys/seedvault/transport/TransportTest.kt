/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
import android.content.pm.ApplicationInfo.FLAG_INSTALLED
import android.content.pm.PackageInfo
import android.content.pm.SigningInfo
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.ByteString.copyFromUtf8
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.decodeBase64
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.METADATA_SALT_SIZE
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.proto.SnapshotKt
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import com.stevesoltys.seedvault.proto.SnapshotKt.split
import com.stevesoltys.seedvault.proto.snapshot
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.backup.BackupData
import com.stevesoltys.seedvault.transport.backup.hexFromProto
import com.stevesoltys.seedvault.worker.BASE_SPLIT
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.toHexString
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD
import kotlin.random.Random

@TestInstance(PER_METHOD)
internal abstract class TransportTest {

    protected val clock: Clock = mockk()
    protected val crypto = mockk<Crypto>()
    protected val settingsManager = mockk<SettingsManager>()
    protected val metadataManager = mockk<MetadataManager>()
    protected val context = mockk<Context>(relaxed = true)

    protected val sigInfo: SigningInfo = mockk()
    protected val token = Random.nextLong()
    protected val applicationInfo = mockk<ApplicationInfo> {
        flags = FLAG_ALLOW_BACKUP or FLAG_INSTALLED
    }
    protected val packageInfo = PackageInfo().apply {
        packageName = "org.example"
        longVersionCode = Random.nextLong()
        applicationInfo = this@TransportTest.applicationInfo
        signingInfo = sigInfo
    }
    protected val packageName: String = packageInfo.packageName
    protected val pmPackageInfo = PackageInfo().apply {
        packageName = MAGIC_PACKAGE_MANAGER
    }
    protected val metadata = BackupMetadata(
        token = token,
        salt = getRandomBase64(METADATA_SALT_SIZE),
        androidVersion = Random.nextInt(),
        androidIncremental = getRandomString(),
        deviceName = getRandomString(),
        packageMetadataMap = PackageMetadataMap().apply {
            put(packageInfo.packageName, PackageMetadata(backupType = BackupType.KV))
        }
    )
    protected val d2dMetadata = metadata.copy(
        d2dBackup = true
    )

    protected val salt = metadata.salt
    protected val name = getRandomString(12)
    protected val name2 = getRandomString(23)
    protected val storageProviderPackageName = getRandomString(23)
    protected val handle = LegacyAppBackupFile.Blob(token, name)

    protected val repoId = Random.nextBytes(32).toHexString()
    protected val splitName = getRandomString()
    protected val splitBytes = byteArrayOf(0x07, 0x08, 0x09)
    protected val apkChunkId = Random.nextBytes(32).toHexString()
    protected val splitChunkId = Random.nextBytes(32).toHexString()
    protected val apkBlob = blob {
        id = ByteString.copyFrom(Random.nextBytes(32))
    }
    protected val splitBlob = blob {
        id = ByteString.copyFrom(Random.nextBytes(32))
    }
    protected val apkBlobHandle = AppBackupFileType.Blob(repoId, apkBlob.id.hexFromProto())
    protected val apkBackupData = BackupData(listOf(apkChunkId), mapOf(apkChunkId to apkBlob))
    protected val splitBackupData =
        BackupData(listOf(splitChunkId), mapOf(splitChunkId to splitBlob))
    protected val chunkMap = apkBackupData.chunkMap + splitBackupData.chunkMap
    protected val baseSplit = split {
        name = BASE_SPLIT
        chunkIds.add(ByteString.fromHex(apkChunkId))
    }
    protected val apkSplit = split {
        name = splitName
        chunkIds.add(ByteString.fromHex(splitChunkId))
    }
    protected val apk = SnapshotKt.apk {
        versionCode = packageInfo.longVersionCode - 1
        installer = getRandomString()
        signatures.add(copyFromUtf8("AwIB".decodeBase64()))
        splits.add(baseSplit)
        splits.add(apkSplit)
    }
    protected val app = SnapshotKt.app {
        apk = this@TransportTest.apk
    }
    protected val snapshot = snapshot {
        token = this@TransportTest.token
        apps[packageName] = app
        blobs.putAll(chunkMap)
    }

    init {
        mockkStatic(Log::class)
        val logTagSlot = slot<String>()
        val logMsgSlot = slot<String>()
        val logExSlot = slot<Throwable>()
        every { Log.v(any(), any()) } returns 0
        every { Log.d(capture(logTagSlot), capture(logMsgSlot)) } answers {
            println("${logTagSlot.captured} - ${logMsgSlot.captured}")
            0
        }
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), ofType(String::class)) } returns 0
        every { Log.w(any(), ofType(String::class), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(capture(logTagSlot), capture(logMsgSlot), capture(logExSlot)) } answers {
            println("${logTagSlot.captured} - ${logMsgSlot.captured} ${logExSlot.captured}")
            logExSlot.captured.printStackTrace()
            0
        }
    }

}
