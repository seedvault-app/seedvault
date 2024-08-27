/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.stevesoltys.seedvault.settings.SettingsManager
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.BackendTest
import org.calyxos.seedvault.core.backends.saf.SafBackend
import org.calyxos.seedvault.core.backends.saf.SafProperties
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
@MediumTest
class SafBackendTest : BackendTest(), KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settingsManager by inject<SettingsManager>()
    override val plugin: Backend
        get() {
            val safStorage = settingsManager.getSafProperties() ?: error("No SAF storage")
            val safProperties = SafProperties(
                config = safStorage.config,
                name = safStorage.name,
                isUsb = safStorage.isUsb,
                requiresNetwork = safStorage.requiresNetwork,
                rootId = safStorage.rootId,
            )
            return SafBackend(context, safProperties, ".SeedvaultTest")
        }

    @Test
    fun test(): Unit = runBlocking {
        testWriteListReadRenameDelete()
    }
}
