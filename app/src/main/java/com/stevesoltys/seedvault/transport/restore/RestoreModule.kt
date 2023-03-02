/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val restoreModule = module {
    single { OutputFactory() }
    single { KVRestore(get(), get(), get(), get(), get(), get()) }
    single { FullRestore(get(), get(), get(), get(), get()) }
    single {
        RestoreCoordinator(androidContext(), get(), get(), get(), get(), get(), get(), get(), get())
    }
}
