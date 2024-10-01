/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.BackupType.KV
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.SnapshotKt.apk
import com.stevesoltys.seedvault.proto.SnapshotKt.app
import com.stevesoltys.seedvault.proto.SnapshotKt.split
import com.stevesoltys.seedvault.proto.copy
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.PackageService
import com.stevesoltys.seedvault.worker.BASE_SPLIT
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [34], // TODO: Drop once robolectric supports 35
    application = TestApp::class
)
internal class SnapshotCreatorTest : TransportTest() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val packageService: PackageService = mockk()
    private val snapshotCreator = SnapshotCreator(ctx, clock, packageService, metadataManager)

    init {
        every { packageService.launchableSystemApps } returns emptyList()
        every { metadataManager.onPackageBackedUp(pmPackageInfo, any(), any()) } just Runs
    }

    @Test
    fun `test onApkBackedUp`() {
        every { applicationInfo.loadLabel(any()) } returns name
        every { clock.time() } returns token

        snapshotCreator.onApkBackedUp(packageInfo, apk, blobMap)
        snapshotCreator.onPackageBackedUp(pmPackageInfo, KV, BackupData(emptyList(), emptyMap()))
        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(apk, s.appsMap[packageName]?.apk)
        assertEquals(name, s.appsMap[packageName]?.name)
        assertEquals(blobMap, s.blobsMap)
    }

    @Test
    fun `test onPackageBackedUp`() {
        val size = apkBackupData.size
        val isSystem = Random.nextBoolean()
        val appInfo = mockk<ApplicationInfo> {
            flags = if (isSystem) FLAG_SYSTEM else 0
        }
        packageInfo.applicationInfo = appInfo
        val resolveInfo = ResolveInfo().apply { // if isSystem, then it will be launchable
            activityInfo = ActivityInfo().apply {
                packageName = this@SnapshotCreatorTest.packageName
            }
        }
        every { appInfo.loadLabel(any()) } returns name
        every { metadataManager.onPackageBackedUp(packageInfo, BackupType.FULL, size) } just Runs
        every { clock.time() } returns token andThen token + 1
        every { packageService.launchableSystemApps } returns listOf(resolveInfo)

        snapshotCreator.onPackageBackedUp(packageInfo, BackupType.FULL, apkBackupData)
        snapshotCreator.onPackageBackedUp(pmPackageInfo, KV, BackupData(emptyList(), emptyMap()))
        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(name, s.appsMap[packageName]?.name)
        assertEquals(token, s.appsMap[packageName]?.time)
        assertEquals(Snapshot.BackupType.FULL, s.appsMap[packageName]?.type)
        assertEquals(size, s.appsMap[packageName]?.size)
        assertEquals(isSystem, s.appsMap[packageName]?.system)
        assertEquals(isSystem, s.appsMap[packageName]?.launchableSystemApp)
        assertEquals(apkBackupData.chunkIds.forProto(), s.appsMap[packageName]?.chunkIdsList)
        assertEquals(apkBackupData.blobMap, s.blobsMap)
    }

    @Test
    fun `test onPackageBackedUp handles no application info`() {
        packageInfo.applicationInfo = null

        val size = apkBackupData.size
        every { metadataManager.onPackageBackedUp(packageInfo, BackupType.FULL, size) } just Runs
        every { clock.time() } returns token andThen token + 1
        every { packageService.launchableSystemApps } returns emptyList()

        snapshotCreator.onPackageBackedUp(packageInfo, BackupType.FULL, apkBackupData)
        snapshotCreator.onPackageBackedUp(pmPackageInfo, KV, BackupData(emptyList(), emptyMap()))
        snapshotCreator.finalizeSnapshot()
    }

    @Test
    fun `test onNoDataInCurrentRun is no-op if no data in last snapshot`() {
        snapshotCreator.onNoDataInCurrentRun(snapshot, MAGIC_PACKAGE_MANAGER)

        every { clock.time() } returns token

        // finalizing complains about not having @pm@
        val e = assertThrows<IllegalStateException> {
            snapshotCreator.finalizeSnapshot()
        }
        assertTrue(e.message?.contains(MAGIC_PACKAGE_MANAGER) == true)
    }

    @Test
    fun `test onNoDataInCurrentRun doesn't overwrite existing data`() {
        val snapshot1 = snapshot.copy {
            apps[MAGIC_PACKAGE_MANAGER] = app {
                system = true
                type = Snapshot.BackupType.KV
                size = 42L
                chunkIds.addAll(listOf(chunkId1).forProto())
            }
            blobs.clear()
            blobs[chunkId1] = blob1
        }
        val snapshot2 = snapshot.copy {
            apps[MAGIC_PACKAGE_MANAGER] = app {
                system = true
                type = Snapshot.BackupType.KV
                size = 1337L
                chunkIds.addAll(listOf(chunkId2).forProto())
            }
            blobs.clear()
            blobs[chunkId2] = blob2
        }

        every {
            metadataManager.onPackageBackedUp(match {
                it.packageName == MAGIC_PACKAGE_MANAGER
            }, KV, 42L) // doesn't get run for size of snapshot2
        } just Runs

        // We just call the same method twice for ease of testing,
        // but in reality, the existing data could come from other calls.
        // Important is that existing data doesn't get replaced with data from old snapshots.
        snapshotCreator.onNoDataInCurrentRun(snapshot1, MAGIC_PACKAGE_MANAGER)
        snapshotCreator.onNoDataInCurrentRun(snapshot2, MAGIC_PACKAGE_MANAGER)

        every { clock.time() } returns token

        // finalizing includes @pm@ app and its blobs
        snapshotCreator.finalizeSnapshot().also { s ->
            // data from snapshot1 is used, not from snapshot2
            assertEquals(snapshot1.appsMap[MAGIC_PACKAGE_MANAGER], s.appsMap[MAGIC_PACKAGE_MANAGER])
            // only first blob is in map
            assertEquals(1, s.blobsMap.size)
            assertEquals(blob1, s.blobsMap[chunkId1])
        }
    }

    @Test
    fun `test onNoDataInCurrentRun`() {
        val snapshot = snapshot.copy {
            apps[MAGIC_PACKAGE_MANAGER] = app {
                system = true
                type = Snapshot.BackupType.KV
                size = 42L
                chunkIds.addAll(listOf(chunkId1).forProto())
                apk = apk { // @pm@ doesn't have an APK, but we just add one for testing
                    val split = split {
                        this.name = BASE_SPLIT
                        this.chunkIds.addAll(listOf(chunkId2).forProto())
                    }
                    splits.add(split)
                }
            }
            blobs.clear()
            blobs[chunkId1] = blob1
            blobs[chunkId2] = blob2
        }

        every {
            metadataManager.onPackageBackedUp(match {
                it.packageName == MAGIC_PACKAGE_MANAGER
            }, KV, 42L)
        } just Runs

        snapshotCreator.onNoDataInCurrentRun(snapshot, MAGIC_PACKAGE_MANAGER)

        every { clock.time() } returns token

        // finalizing includes @pm@ app and its blobs
        snapshotCreator.finalizeSnapshot().also { s ->
            assertEquals(snapshot.appsMap[MAGIC_PACKAGE_MANAGER], s.appsMap[MAGIC_PACKAGE_MANAGER])
            assertEquals(blob1, s.blobsMap[chunkId1])
            assertEquals(blob2, s.blobsMap[chunkId2])
        }
    }

    @Test
    fun `test onIconsBackedUp`() {
        every { clock.time() } returns token andThen token + 1

        snapshotCreator.onIconsBackedUp(apkBackupData)
        snapshotCreator.onPackageBackedUp(pmPackageInfo, KV, BackupData(emptyList(), emptyMap()))
        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(apkBackupData.chunkIds.forProto(), s.iconChunkIdsList)
        assertEquals(apkBackupData.blobMap, s.blobsMap)
    }

    @Test
    fun `test finalize`() {
        every { clock.time() } returns token

        snapshotCreator.onPackageBackedUp(pmPackageInfo, KV, BackupData(emptyList(), emptyMap()))
        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(VERSION, s.version.toByte())
        assertEquals(token, s.token)
        assertEquals("robolectric robolectric", s.name)
        assertEquals("", s.user) // no perm
        assertEquals("", s.androidId) // not mocked
        assertEquals(34, s.sdkInt) // as per config above, needs bump once possible
        assertEquals("unknown", s.androidIncremental)
        assertTrue(s.d2D)
        assertEquals(1, s.appsCount)
        assertEquals(0, s.iconChunkIdsCount)
        assertEquals(emptyMap<String, Snapshot.Blob>(), s.blobsMap)
    }

}
