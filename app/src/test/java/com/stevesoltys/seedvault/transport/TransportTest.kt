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
import com.stevesoltys.seedvault.repo.BackupData
import com.stevesoltys.seedvault.repo.hexFromProto
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.worker.BASE_SPLIT
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.FileInfo
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
    protected val quota = Random.nextLong(1, Long.MAX_VALUE)
    protected val applicationInfo = mockk<ApplicationInfo> {
        flags = FLAG_ALLOW_BACKUP or FLAG_INSTALLED
    }
    protected val packageInfo = PackageInfo().apply {
        packageName = "org.example"
        longVersionCode = Random.nextLong()
        applicationInfo = this@TransportTest.applicationInfo
        signingInfo = sigInfo
        splitNames = emptyArray()
    }
    protected val packageName: String = packageInfo.packageName
    protected val pmPackageInfo = PackageInfo().apply {
        packageName = MAGIC_PACKAGE_MANAGER
    }

    protected val name = getRandomString(12)
    protected val name2 = getRandomString(23)
    protected val storageProviderPackageName = getRandomString(23)
    protected val handle = LegacyAppBackupFile.Blob(token, name)

    protected val repoId = Random.nextBytes(32).toHexString()
    protected val splitName = getRandomString()
    protected val splitBytes = byteArrayOf(0x07, 0x08, 0x09)
    protected val chunkId1 = Random.nextBytes(32).toHexString()
    protected val chunkId2 = Random.nextBytes(32).toHexString()
    protected val blob1 = blob {
        id = ByteString.copyFrom(Random.nextBytes(32))
        length = Random.nextInt(0, Int.MAX_VALUE)
        uncompressedLength = Random.nextInt(0, Int.MAX_VALUE)
    }
    protected val blob2 = blob {
        id = ByteString.copyFrom(Random.nextBytes(32))
        length = Random.nextInt(0, Int.MAX_VALUE)
        uncompressedLength = Random.nextInt(0, Int.MAX_VALUE)
    }
    protected val blobHandle1 = AppBackupFileType.Blob(repoId, blob1.id.hexFromProto())
    protected val blobHandle2 = AppBackupFileType.Blob(repoId, blob2.id.hexFromProto())
    protected val fileInfo1 = FileInfo(
        fileHandle = blobHandle1,
        size = blob1.length.toLong(),
    )
    protected val fileInfo2 = FileInfo(
        fileHandle = blobHandle2,
        size = blob2.length.toLong(),
    )
    protected val apkBackupData = BackupData(listOf(chunkId1), mapOf(chunkId1 to blob1))
    protected val splitBackupData =
        BackupData(listOf(chunkId2), mapOf(chunkId2 to blob2))
    protected val blobMap = apkBackupData.blobMap + splitBackupData.blobMap
    protected val baseSplit = split {
        name = BASE_SPLIT
        chunkIds.add(ByteString.fromHex(chunkId1))
    }
    protected val apkSplit = split {
        name = splitName
        chunkIds.add(ByteString.fromHex(chunkId2))
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
        blobs.putAll(blobMap)
    }
    protected val metadata = BackupMetadata(
        token = token,
        salt = getRandomBase64(METADATA_SALT_SIZE),
        androidVersion = Random.nextInt(),
        androidIncremental = getRandomString(),
        deviceName = getRandomString(),
        packageMetadataMap = PackageMetadataMap().apply {
            put(
                packageInfo.packageName,
                PackageMetadata(backupType = BackupType.KV, chunkIds = listOf(chunkId1)),
            )
        }
    )
    protected val d2dMetadata = metadata.copy(
        d2dBackup = true
    )
    protected val salt = metadata.salt

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
