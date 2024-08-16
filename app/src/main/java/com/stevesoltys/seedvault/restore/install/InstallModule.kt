/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore.install

import android.os.UserManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val installModule = module {
    factory { ApkInstaller(androidContext()) }
    factory { DeviceInfo(androidContext()) }
    factory { ApkSplitCompatibilityChecker(get()) }
    factory {
        ApkRestore(androidContext(), get(), get(), get(), get(), get(), get(), get()) {
            androidContext().getSystemService(UserManager::class.java)!!.isAllowedToInstallApks()
        }
    }
}
