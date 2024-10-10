/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.pm.PackageInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.github.luben.zstd.ZstdOutputStream
import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.BackupData
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.repo.SnapshotCreatorFactory
import com.stevesoltys.seedvault.transport.backup.PackageService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@MediumTest
class IconManagerTest : KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageService by inject<PackageService>()
    private val backupReceiver = mockk<BackupReceiver>()
    private val loader = mockk<Loader>()
    private val appBackupManager = mockk<AppBackupManager>()
    private val snapshotCreatorFactory by inject<SnapshotCreatorFactory>()
    private val snapshotCreator = snapshotCreatorFactory.createSnapshotCreator()

    private val iconManager = IconManager(
        context = context,
        packageService = packageService,
        crypto = mockk(),
        backupReceiver = backupReceiver,
        loader = loader,
        appBackupManager = appBackupManager,
    )

    init {
        every { appBackupManager.snapshotCreator } returns snapshotCreator
    }

    @Test
    fun `test upload and then download`(): Unit = runBlocking {
        // prepare output data
        val output = slot<ByteArray>()
        val chunkId = Random.nextBytes(32).toHexString()
        val chunkList = listOf(chunkId)
        val blobId = Random.nextBytes(32).toHexString()
        val blob = blob { id = ByteString.fromHex(blobId) }

        // upload icons and capture plaintext bytes
        coEvery { backupReceiver.addBytes(any(), capture(output)) } just Runs
        coEvery {
            backupReceiver.finalize(any())
        } returns BackupData(chunkList, mapOf(chunkId to blob))
        iconManager.uploadIcons()
        assertTrue(output.captured.isNotEmpty())

        // @pm@ is needed
        val pmPackageInfo = PackageInfo().apply { packageName = MAGIC_PACKAGE_MANAGER }
        val backupData = BackupData(emptyList(), emptyMap())
        snapshotCreator.onPackageBackedUp(pmPackageInfo, BackupType.KV, backupData)

        // get snapshot and assert it has icon chunks
        val snapshot = snapshotCreator.finalizeSnapshot()
        assertTrue(snapshot.iconChunkIdsCount > 0)

        // prepare data for downloading icons
        val repoId = Random.nextBytes(32).toHexString()
        val inputStream = ByteArrayInputStream(output.captured)
        coEvery { loader.loadFile(AppBackupFileType.Blob(repoId, blobId)) } returns inputStream

        // download icons and ensure we had an icon for at least one app
        val iconSet = iconManager.downloadIcons(repoId, snapshot)
        assertTrue(iconSet.isNotEmpty())
    }

    @Test
    fun `test upload produces deterministic output`(): Unit = runBlocking {
        val output1 = slot<ByteArray>()
        val output2 = slot<ByteArray>()

        coEvery { backupReceiver.addBytes(any(), capture(output1)) } just Runs
        coEvery { backupReceiver.finalize(any()) } returns BackupData(emptyList(), emptyMap())
        iconManager.uploadIcons()
        assertTrue(output1.captured.isNotEmpty())

        coEvery { backupReceiver.addBytes(any(), capture(output2)) } just Runs
        coEvery { backupReceiver.finalize(any()) } returns BackupData(emptyList(), emptyMap())
        iconManager.uploadIcons()
        assertTrue(output2.captured.isNotEmpty())

        assertArrayEquals(output1.captured, output2.captured)

        // print compressed and uncompressed size
        val size = output1.captured.size.toFloat() / 1024 / 1024
        val outputStream = ByteArrayOutputStream()
        ZstdOutputStream(outputStream).use { it.write(output1.captured) }
        val compressedSize = outputStream.size().toFloat() / 1024 / 1024
        println("Icon size: $size MB, compressed $compressedSize MB")
    }

}
