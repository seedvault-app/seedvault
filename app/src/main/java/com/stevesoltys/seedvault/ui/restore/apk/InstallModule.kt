package com.stevesoltys.seedvault.ui.restore.apk

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val installModule = module {
    factory { ApkInstaller(androidContext()) }
    factory { DeviceInfo(androidContext()) }
    factory { ApkSplitCompatibilityChecker(get()) }
    factory { ApkRestore(androidContext(), get(), get(), get(), get(), get()) }
}
