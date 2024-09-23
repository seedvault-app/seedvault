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
import com.stevesoltys.seedvault.TestApp
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.PackageService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test
    fun `test onApkBackedUp`() {
        every { applicationInfo.loadLabel(any()) } returns name
        every { clock.time() } returns token

        snapshotCreator.onApkBackedUp(packageInfo, apk, blobMap)
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
        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(name, s.appsMap[packageName]?.name)
        assertEquals(token, s.appsMap[packageName]?.time)
        assertEquals(Snapshot.BackupType.FULL, s.appsMap[packageName]?.type)
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
        snapshotCreator.finalizeSnapshot()
    }

    @Test
    fun `test onIconsBackedUp`() {
        every { clock.time() } returns token andThen token + 1

        snapshotCreator.onIconsBackedUp(apkBackupData)
        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(apkBackupData.chunkIds.forProto(), s.iconChunkIdsList)
        assertEquals(apkBackupData.blobMap, s.blobsMap)
    }

    @Test
    fun `test finalize`() {
        every { clock.time() } returns token

        val s = snapshotCreator.finalizeSnapshot()

        assertEquals(VERSION, s.version.toByte())
        assertEquals(token, s.token)
        assertEquals("robolectric robolectric", s.name)
        assertEquals("", s.user) // no perm
        assertEquals("", s.androidId) // not mocked
        assertEquals(34, s.sdkInt) // as per config above, needs bump once possible
        assertEquals("unknown", s.androidIncremental)
        assertTrue(s.d2D)
        assertEquals(0, s.appsCount)
        assertEquals(0, s.iconChunkIdsCount)
        assertEquals(emptyMap<String, Snapshot.Blob>(), s.blobsMap)
    }

}
