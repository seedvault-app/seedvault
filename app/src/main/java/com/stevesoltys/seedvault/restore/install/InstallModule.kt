package com.stevesoltys.seedvault.restore.install

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val installModule = module {
    factory { ApkInstaller(androidContext()) }
    factory { ApkSplitCompatibilityChecker(DeviceInfo(androidContext())) }
    factory { ApkRestore(androidContext(), get(), get(), get()) }
}
