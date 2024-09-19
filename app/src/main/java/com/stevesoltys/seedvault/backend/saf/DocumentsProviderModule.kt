/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.backend.saf

import com.stevesoltys.seedvault.backend.LegacyStoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storagePluginModuleSaf = module {
    single { SafHandler(androidContext(), get(), get(), get()) }

    @Suppress("Deprecation")
    single<LegacyStoragePlugin> {
        DocumentsProviderLegacyPlugin(
            context = androidContext(),
            storageGetter = {
                val safProperties = get<SettingsManager>().getSafProperties()
                    ?: error("No SAF storage")
                DocumentsStorage(androidContext(), safProperties)
            },
        )
    }
}
