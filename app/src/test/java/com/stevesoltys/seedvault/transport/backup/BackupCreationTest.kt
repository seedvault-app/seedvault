/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.FLAG_INCREMENTAL
import android.app.backup.BackupTransport.TRANSPORT_OK
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KEY_SIZE_BYTES
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.repo.BlobCache
import com.stevesoltys.seedvault.repo.BlobCreator
import com.stevesoltys.seedvault.repo.SnapshotCreator
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Creates encrypted backups from known data that can be used for further tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34], // TODO: Drop once robolectric supports 35
    application = TestApp::class
)
internal class BackupCreationTest : BackupTest() {

    private val secretKey = SecretKeySpec(
        "This is a legacy backup key 1234".toByteArray(), 0, KEY_SIZE_BYTES, "AES"
    )
    private val keyManager = KeyManagerTestImpl(secretKey)
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl =
        CryptoImpl(context, keyManager, cipherFactory, headerReader, "0123456789")

    private val blobCache = BlobCache(context)
    private val backendManager = mockk<BackendManager>()
    private val blobCreator = BlobCreator(cryptoImpl, backendManager)
    private val backupReceiver = BackupReceiver(blobCache, blobCreator, cryptoImpl)
    private val appBackupManager = mockk<AppBackupManager>()
    private val packageService = mockk<PackageService>()
    private val snapshotCreator =
        SnapshotCreator(context, clock, packageService, mockk(relaxed = true))
    private val notificationManager = mockk<BackupNotificationManager>()
    private val db = TestKvDbManager()

    private val kvBackup = KVBackup(backupReceiver, inputFactory, db)
    private val fullBackup =
        FullBackup(settingsManager, notificationManager, backupReceiver, inputFactory)

    private val backup = BackupCoordinator(
        context = context,
        backendManager = backendManager,
        appBackupManager = appBackupManager,
        kv = kvBackup,
        full = fullBackup,
        packageService = packageService,
        metadataManager = metadataManager,
        settingsManager = settingsManager,
        nm = notificationManager,
    )

    private val backend = mockk<Backend>()
    private val backupDataInput = mockk<BackupDataInput>()
    private val newRepoId = "b575506bb32d4279128cb423d347384f1985822d11254218bcd3ae77d6cccd27"

    init {
        every { backendManager.backend } returns backend
        every { appBackupManager.snapshotCreator } returns snapshotCreator
        every { clock.time() } returns token
        every { packageInfo.applicationInfo?.loadLabel(any()) } returns packageName
    }

    @Test
    fun `KV backup`() = runBlocking {
        // return data
        every { inputFactory.getBackupDataInput(data) } returns backupDataInput
        every { backupDataInput.readNextHeader() } returns true andThen true andThen false
        every { backupDataInput.key } returns key andThen key2
        every { backupDataInput.dataSize } returns appData.size andThen appData2.size

        // read in data
        val byteSlot1 = slot<ByteArray>()
        val byteSlot2 = slot<ByteArray>()
        every { backupDataInput.readEntityData(capture(byteSlot1), 0, appData.size) } answers {
            appData.copyInto(byteSlot1.captured) // write the app data into the passed ByteArray
            appData.size
        }
        every { backupDataInput.readEntityData(capture(byteSlot2), 0, appData2.size) } answers {
            appData2.copyInto(byteSlot2.captured) // write the app data into the passed ByteArray
            appData2.size
        }

        every { data.close() } just Runs

        assertEquals(
            TRANSPORT_OK,
            backup.performIncrementalBackup(packageInfo, data, FLAG_INCREMENTAL),
        )

        val handleSlot = slot<AppBackupFileType.Blob>()
        val outputStream = ByteArrayOutputStream()
        coEvery { backend.save(capture(handleSlot)) } returns outputStream

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertEquals(newRepoId, handleSlot.captured.repoId)

        println(handleSlot.captured)
        println(outputStream.toByteArray().toHexString())
    }

    @Test
    fun `full backup`() = runBlocking {
        every { inputFactory.getInputStream(data) } returns ByteArrayInputStream(appData2)
        assertEquals(
            TRANSPORT_OK,
            backup.performFullBackup(packageInfo, data, 0),
        )

        every { settingsManager.quota } returns quota
        every { data.close() } just Runs
        assertEquals(TRANSPORT_OK, backup.sendBackupData(appData2.size))

        val handleSlot = slot<AppBackupFileType.Blob>()
        val outputStream = ByteArrayOutputStream()
        coEvery { backend.save(capture(handleSlot)) } returns outputStream

        assertEquals(TRANSPORT_OK, backup.finishBackup())
        assertEquals(newRepoId, handleSlot.captured.repoId)

        println(handleSlot.captured)
        println(outputStream.toByteArray().toHexString())
    }

}
