/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.TurbineTestContext
import app.cash.turbine.test
import com.stevesoltys.seedvault.MAGIC_PACKAGE_MANAGER
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.metadata.PackageMetadataMap
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_CONTACTS
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_SETTINGS
import com.stevesoltys.seedvault.ui.PACKAGE_NAME_SYSTEM
import com.stevesoltys.seedvault.worker.IconManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.calyxos.seedvault.core.backends.Backend
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(
    sdk = [34] // TODO: Drop once robolectric supports 35
)
internal class AppSelectionManagerTest : TransportTest() {

    private val backendManager: BackendManager = mockk()
    private val iconManager: IconManager = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(testDispatcher)

    private val packageName1 = "org.example.1"
    private val packageName2 = "org.example.2"
    private val packageName3 = "org.example.3"
    private val packageName4 = "org.example.4"
    private val backupMetadata = BackupMetadata(
        token = Random.nextLong(),
        salt = getRandomString(),
    )

    private val appSelectionManager = AppSelectionManager(
        context = context,
        backendManager = backendManager,
        iconManager = iconManager,
        coroutineScope = scope,
        workDispatcher = testDispatcher,
    )

    @Test
    fun `apps without backup and APK, as well as system apps are filtered out`() = scope.runTest {
        coEvery { iconManager.downloadIcons(repoId, snapshot) } returns emptySet()
        appSelectionManager.selectedAppsFlow.test {
            val initialState = awaitItem()
            assertEquals(emptyList<SelectableAppItem>(), initialState.apps)
            assertTrue(initialState.allSelected)
            assertFalse(initialState.iconsLoaded)

            val backup = getRestorableBackup(
                mapOf(
                    PACKAGE_NAME_SETTINGS to PackageMetadata(), // no backup and no APK
                    packageName1 to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            val initialApps = awaitItem()
            // only the meta system app item remains
            assertEquals(1, initialApps.apps.size)
            assertEquals(PACKAGE_NAME_SYSTEM, initialApps.apps[0].packageName)
            assertTrue(initialApps.allSelected)
            assertFalse(initialApps.iconsLoaded)

            // now icons have loaded and apps were updated
            awaitItem()
        }
    }

    @Test
    fun `apps get sorted by name, special items on top`() = scope.runTest {
        coEvery { iconManager.downloadIcons(repoId, snapshot) } returns emptySet()
        appSelectionManager.selectedAppsFlow.test {
            awaitItem()

            val backup = getRestorableBackup(
                mapOf(
                    packageName1 to PackageMetadata(
                        time = 23L,
                        name = "B",
                    ),
                    packageName2 to PackageMetadata(
                        time = 42L,
                        name = "A",
                    ),
                    PACKAGE_NAME_SETTINGS to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            val initialApps = awaitItem()
            assertEquals(4, initialApps.apps.size)
            assertEquals(PACKAGE_NAME_SETTINGS, initialApps.apps[0].packageName)
            assertEquals(PACKAGE_NAME_SYSTEM, initialApps.apps[1].packageName)
            assertEquals(packageName2, initialApps.apps[2].packageName)
            assertEquals(packageName1, initialApps.apps[3].packageName)

            // now icons have loaded and apps were updated
            awaitItem()
        }
    }

    @Test
    fun `test app selection`() = scope.runTest {
        coEvery {
            iconManager.downloadIcons(repoId, snapshot)
        } returns setOf(packageName1, packageName2)
        appSelectionManager.selectedAppsFlow.test {
            awaitItem()

            val backup = getRestorableBackup(
                mapOf(
                    packageName1 to PackageMetadata(time = 23L),
                    packageName2 to PackageMetadata(time = 42L),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            // first all are selected
            val initialApps = awaitItem()
            assertEquals(3, initialApps.apps.size)
            initialApps.apps.forEach { assertTrue(it.selected) }
            assertTrue(initialApps.allSelected)

            // now icons have loaded and apps were updated
            awaitItem()

            // deselect last app in list
            appSelectionManager.onAppSelected(initialApps.apps[2])
            val oneDeselected = awaitItem()
            oneDeselected.apps.forEach {
                if (it.packageName == packageName2) assertFalse(it.selected)
                else assertTrue(it.selected)
            }
            assertFalse(oneDeselected.allSelected)

            // select all apps
            appSelectionManager.onCheckAllAppsClicked()
            val allSelected = awaitItem()
            allSelected.apps.forEach { assertTrue(it.selected) }
            assertTrue(allSelected.allSelected)

            // de-select all apps
            appSelectionManager.onCheckAllAppsClicked()
            val noneSelected = awaitItem()
            noneSelected.apps.forEach { assertFalse(it.selected) }
            assertFalse(noneSelected.allSelected)

            // re-select first (meta) app
            appSelectionManager.onAppSelected(noneSelected.apps[0])
            val firstSelected = awaitItem()
            firstSelected.apps.forEach {
                if (it.packageName == PACKAGE_NAME_SYSTEM) assertTrue(it.selected)
                else assertFalse(it.selected)
            }
            assertFalse(firstSelected.allSelected)
        }
    }

    @Test
    fun `test icon loading`() = scope.runTest {
        coEvery {
            iconManager.downloadIcons(repoId, snapshot)
        } returns setOf(packageName1) // only icons found for packageName1

        appSelectionManager.selectedAppsFlow.test {
            awaitItem()

            val backup = getRestorableBackup(
                mapOf(
                    packageName1 to PackageMetadata(time = 23),
                    packageName2 to PackageMetadata(time = 42L),
                    PACKAGE_NAME_SETTINGS to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            // all apps (except special ones) have an unknown item state initially
            val initialApps = awaitItem()
            assertEquals(4, initialApps.apps.size)
            initialApps.apps.forEach {
                assertNull(it.hasIcon)
            }

            // all apps except packageName2 have icons now
            val itemsWithIcons = awaitItem()
            itemsWithIcons.apps.forEach {
                if (it.packageName == packageName2) assertFalse(it.hasIcon ?: fail())
                else assertTrue(it.hasIcon ?: fail())
            }
            assertTrue(itemsWithIcons.iconsLoaded)
        }
    }

    @Test
    fun `test icon loading fails`() = scope.runTest {
        val backend: Backend = mockk()
        every { backendManager.backend } returns backend
        coEvery {
            iconManager.downloadIcons(repoId, snapshot)
        } throws IOException()

        appSelectionManager.selectedAppsFlow.test {
            awaitItem()

            val backup = getRestorableBackup(
                mapOf(
                    packageName1 to PackageMetadata(time = 23),
                    packageName2 to PackageMetadata(time = 42L),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            val initialApps = awaitItem()
            assertEquals(3, initialApps.apps.size)

            // no apps have icons now (except special system app), but their state is known
            val itemsWithoutIcons = awaitItem()
            itemsWithoutIcons.apps.forEach {
                if (it.packageName == PACKAGE_NAME_SYSTEM) assertTrue(it.hasIcon ?: fail())
                else assertFalse(it.hasIcon ?: fail())
            }
            assertTrue(itemsWithoutIcons.iconsLoaded)
        }
    }

    @Test
    fun `finishing selection filters unselected apps, leaves system apps`() = scope.runTest {
        testFiltering { backup ->
            val itemsWithIcons = awaitItem()

            // unselect app1 and contacts app
            val app1 = itemsWithIcons.apps.find { it.packageName == packageName1 } ?: fail()
            val contacts = itemsWithIcons.apps.find { it.packageName == PACKAGE_NAME_CONTACTS }
                ?: fail()
            appSelectionManager.onAppSelected(app1)
            awaitItem()
            appSelectionManager.onAppSelected(contacts)

            // assert that both apps are unselected
            val finalSelection = awaitItem()
            // we have 6 real apps (two are hidden) plus system meta item, makes 5
            assertEquals(5, finalSelection.apps.size)
            finalSelection.apps.forEach {
                if (it.packageName in listOf(packageName1, PACKAGE_NAME_CONTACTS)) {
                    assertFalse(it.selected)
                } else {
                    assertTrue(it.selected)
                }
            }

            // 4 apps should survive: app2, app3 (system app), app4 (hidden) and settings
            val filteredBackup = appSelectionManager.onAppSelectionFinished(backup)
            assertEquals(4, filteredBackup.packageMetadataMap.size)
            assertEquals(
                setOf(packageName2, packageName3, packageName4, PACKAGE_NAME_SETTINGS),
                filteredBackup.packageMetadataMap.keys,
            )
        }
    }

    @Test
    fun `finishing selection without system apps only removes non-special system apps`() =
        scope.runTest {
            testFiltering { backup ->
                val itemsWithIcons = awaitItem()

                // unselect all system apps and settings, contacts should stay
                val systemMeta = itemsWithIcons.apps.find { it.packageName == PACKAGE_NAME_SYSTEM }
                    ?: fail()
                val settings = itemsWithIcons.apps.find { it.packageName == PACKAGE_NAME_SETTINGS }
                    ?: fail()
                appSelectionManager.onAppSelected(systemMeta)
                awaitItem()
                appSelectionManager.onAppSelected(settings)

                // assert that both apps are unselected
                val finalSelection = awaitItem()
                // we have 6 real apps (two are hidden) plus system meta item, makes 5
                assertEquals(5, finalSelection.apps.size)
                finalSelection.apps.forEach {
                    if (it.packageName in listOf(PACKAGE_NAME_SYSTEM, PACKAGE_NAME_SETTINGS)) {
                        assertFalse(it.selected)
                    } else {
                        assertTrue(it.selected)
                    }
                }

                // 4 apps should survive: app1, app2, app4 (hidden) and contacts
                val filteredBackup = appSelectionManager.onAppSelectionFinished(backup)
                assertEquals(4, filteredBackup.packageMetadataMap.size)
                assertEquals(
                    setOf(packageName1, packageName2, packageName4, PACKAGE_NAME_CONTACTS),
                    filteredBackup.packageMetadataMap.keys,
                )
            }
        }

    @Test
    fun `system apps only pre-selected in setup wizard`() = scope.runTest {
        val backup = getRestorableBackup(
            mutableMapOf(
                packageName1 to PackageMetadata(system = true, isLaunchableSystemApp = false),
            )
        )
        coEvery { iconManager.downloadIcons(repoId, snapshot) } returns (emptySet())
        // choose restore set in setup wizard
        appSelectionManager.selectedAppsFlow.test {
            awaitItem()
            appSelectionManager.onRestoreSetChosen(backup, true)
            // only system apps meta item in list
            val initialApps = awaitItem()
            assertEquals(1, initialApps.apps.size)
            assertEquals(PACKAGE_NAME_SYSTEM, initialApps.apps[0].packageName)
            assertTrue(initialApps.apps[0].selected) // system settings is selected

            // now icons have loaded and apps were updated
            awaitItem()
        }
        appSelectionManager.selectedAppsFlow.test {
            awaitItem()
            appSelectionManager.onRestoreSetChosen(backup, false)
            // only system apps meta item in list
            val initialApps = awaitItem()
            assertEquals(1, initialApps.apps.size)
            assertEquals(PACKAGE_NAME_SYSTEM, initialApps.apps[0].packageName)
            assertFalse(initialApps.apps[0].selected) // system settings is NOT selected

            // now icons have loaded and apps were updated
            awaitItem()
        }
    }

    @Test
    fun `@pm@ doesn't get filtered out`() = scope.runTest {
        coEvery { iconManager.downloadIcons(repoId, snapshot) } returns emptySet()
        appSelectionManager.selectedAppsFlow.test {
            awaitItem()

            val backup = getRestorableBackup(
                mutableMapOf(
                    MAGIC_PACKAGE_MANAGER to PackageMetadata(
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            // only system apps meta item in list
            val initialApps = awaitItem()
            assertEquals(1, initialApps.apps.size)
            assertEquals(PACKAGE_NAME_SYSTEM, initialApps.apps[0].packageName)

            // now icons have loaded and apps were updated
            awaitItem()

            // actual filtered backup includes @pm@ only
            val filteredBackup = appSelectionManager.onAppSelectionFinished(backup)
            assertEquals(1, filteredBackup.packageMetadataMap.size)
            assertEquals(
                setOf(MAGIC_PACKAGE_MANAGER),
                filteredBackup.packageMetadataMap.keys,
            )
        }
    }

    private fun getRestorableBackup(map: Map<String, PackageMetadata>) = RestorableBackup(
        backupMetadata = backupMetadata.copy(packageMetadataMap = map as PackageMetadataMap),
        repoId = repoId,
        snapshot = snapshot,
    )

    private suspend fun testFiltering(
        block: suspend TurbineTestContext<SelectedAppsState>.(RestorableBackup) -> Unit,
    ) {
        coEvery {
            iconManager.downloadIcons(repoId, snapshot)
        } returns setOf(packageName1, packageName2)
        appSelectionManager.selectedAppsFlow.test {
            awaitItem()

            val backup = getRestorableBackup(
                mapOf(
                    packageName1 to PackageMetadata(time = 23L),
                    packageName2 to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = true,
                    ),
                    packageName3 to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                    packageName4 to PackageMetadata(), // no backup and no APK
                    PACKAGE_NAME_CONTACTS to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                    PACKAGE_NAME_SETTINGS to PackageMetadata(
                        time = 42L,
                        system = true,
                        isLaunchableSystemApp = false,
                    ),
                )
            )
            appSelectionManager.onRestoreSetChosen(backup, true)

            val initialApps = awaitItem()
            // we have 6 real apps (two are hidden) plus system meta item, makes 5
            assertEquals(5, initialApps.apps.size)
            block(backup)
        }
    }
}
