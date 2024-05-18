/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.webdav

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val storagePluginModuleWebDav = module {
    single { WebDavFactory(androidContext(), get()) }
    single { WebDavHandler(androidContext(), get(), get(), get()) }
}
