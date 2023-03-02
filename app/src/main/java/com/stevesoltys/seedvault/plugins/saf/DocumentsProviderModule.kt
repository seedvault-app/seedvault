/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.settings.SettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storagePluginModuleSaf = module {
    single { SafFactory(androidContext(), get(), get()) }
    single { SafHandler(androidContext(), get(), get(), get()) }

    @Suppress("Deprecation")
    single<LegacyStoragePlugin> {
        DocumentsProviderLegacyPlugin(
            context = androidContext(),
            storageGetter = {
                val safStorage = get<SettingsManager>().getSafStorage() ?: error("No SAF storage")
                DocumentsStorage(androidContext(), get(), safStorage)
            },
        )
    }
}
