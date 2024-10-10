/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupTransport.FLAG_NON_INCREMENTAL
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.stevesoltys.seedvault.repo.BackupData
import com.stevesoltys.seedvault.repo.BackupReceiver
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.random.Random
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@MediumTest
class KvBackupInstrumentationTest : KoinComponent {

    private val backupReceiver: BackupReceiver = mockk()
    private val inputFactory: InputFactory = mockk()
    private val dbManager: KvDbManager by inject()

    private val backup = KVBackup(
        backupReceiver = backupReceiver,
        inputFactory = inputFactory,
        dbManager = dbManager,
    )

    private val data = mockk<ParcelFileDescriptor>()
    private val dataInput = mockk<BackupDataInput>()
    private val key = "foo.bar"
    private val dataValue = Random.nextBytes(23)

    @Test
    fun `test non-incremental backup with existing DB`() {
        val packageName = "com.example"
        val backupData = BackupData(emptyList(), emptyMap())

        // create existing db
        dbManager.getDb(packageName).use { db ->
            db.put("foo", "bar".toByteArray())
        }

        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
        }

        every { inputFactory.getBackupDataInput(data) } returns dataInput
        every { dataInput.readNextHeader() } returnsMany listOf(true, false)
        every { dataInput.key } returns key
        every { dataInput.dataSize } returns dataValue.size
        val slot = CapturingSlot<ByteArray>()
        every { dataInput.readEntityData(capture(slot), 0, dataValue.size) } answers {
            dataValue.copyInto(slot.captured)
            dataValue.size
        }
        every { data.close() } just Runs

        backup.performBackup(packageInfo, data, FLAG_NON_INCREMENTAL)

        coEvery { backupReceiver.readFromStream(any(), any()) } returns backupData

        runBlocking {
            assertEquals(backupData, backup.finishBackup())
        }

        dbManager.getDb(packageName).use { db ->
            assertNull(db.get("foo")) // existing data foo is gone
            assertArrayEquals(dataValue, db.get(key)) // new data got added
        }
    }

}
