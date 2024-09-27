/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.app.backup.BackupManager
import android.app.backup.BackupManager.PACKAGE_MANAGER_SENTINEL
import android.app.backup.IBackupManager
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.ServiceManager.getService
import android.os.StrictMode
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.WorkManager
import com.google.android.material.color.DynamicColors
import com.stevesoltys.seedvault.MemoryLogger.getMemStr
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.backend.saf.storagePluginModuleSaf
import com.stevesoltys.seedvault.backend.webdav.storagePluginModuleWebDav
import com.stevesoltys.seedvault.crypto.cryptoModule
import com.stevesoltys.seedvault.header.headerModule
import com.stevesoltys.seedvault.metadata.metadataModule
import com.stevesoltys.seedvault.repo.repoModule
import com.stevesoltys.seedvault.restore.install.installModule
import com.stevesoltys.seedvault.restore.restoreUiModule
import com.stevesoltys.seedvault.settings.AppListRetriever
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.SettingsViewModel
import com.stevesoltys.seedvault.storage.storageModule
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.transport.backup.backupModule
import com.stevesoltys.seedvault.transport.restore.restoreModule
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.recoverycode.RecoveryCodeViewModel
import com.stevesoltys.seedvault.ui.storage.BackupStorageViewModel
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import com.stevesoltys.seedvault.worker.AppBackupWorker
import com.stevesoltys.seedvault.worker.workerModule
import org.calyxos.seedvault.core.backends.BackendFactory
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.dsl.module

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
open class App : Application() {

    open val isTest: Boolean = false

    private val appModule = module {
        single { SettingsManager(this@App) }
        single { BackupNotificationManager(this@App) }
        single { BackendManager(this@App, get(), get(), get()) }
        single {
            BackendFactory {
                // uses context of the device's main user to be able to access USB storage
                this@App.applicationContext.getStorageContext {
                    get<SettingsManager>().getSafProperties()?.isUsb == true
                }
            }
        }
        single { BackupStateManager(this@App) }
        single { Clock() }
        factory<IBackupManager> { IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE)) }
        factory { AppListRetriever(this@App, get(), get(), get()) }

        viewModel {
            SettingsViewModel(
                app = this@App,
                settingsManager = get(),
                keyManager = get(),
                backendManager = get(),
                appListRetriever = get(),
                storageBackup = get(),
                backupManager = get(),
                backupStateManager = get(),
            )
        }
        viewModel {
            RecoveryCodeViewModel(this@App, get(), get(), get(), get(), get(), get(), get())
        }
        viewModel {
            BackupStorageViewModel(
                app = this@App,
                backupManager = get(),
                backupInitializer = get(),
                storageBackup = get(),
                safHandler = get(),
                webDavHandler = get(),
                settingsManager = get(),
                backendManager = get(),
            )
        }
        viewModel { RestoreStorageViewModel(this@App, get(), get(), get(), get()) }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        startKoin()
        if (!isTest) migrateToOwnScheduling()
        if (isDebugBuild()) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .penaltyFlashScreen()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }

    protected open fun startKoin() = startKoin {
        androidLogger(Level.ERROR)
        androidContext(this@App)
        modules(appModules())
    }

    open fun appModules() = listOf(
        cryptoModule,
        headerModule,
        metadataModule,
        storagePluginModuleSaf,
        storagePluginModuleWebDav,
        backupModule,
        restoreModule,
        installModule,
        storageModule,
        repoModule,
        workerModule,
        restoreUiModule,
        appModule
    )

    private val settingsManager: SettingsManager by inject()
    private val backupManager: IBackupManager by inject()
    private val backendManager: BackendManager by inject()
    private val backupStateManager: BackupStateManager by inject()

    override fun onTrimMemory(level: Int) {
        Log.w("Seedvault", "onTrimMemory($level) ${getMemStr()}")
        val processInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        Log.w("Seedvault", "  lastTrimLevel: ${processInfo.lastTrimLevel}")
        Log.w("Seedvault", "  importance: ${processInfo.importance}")
        super.onTrimMemory(level)
    }

    /**
     * Disables the framework scheduling in favor of our own.
     * Introduced in the first half of 2024 and can be removed after a suitable migration period.
     */
    protected open fun migrateToOwnScheduling() {
        if (!backupStateManager.isFrameworkSchedulingEnabled) { // already on own scheduling
            // fix things for removable drive users who had a job scheduled here before
            if (backendManager.isOnRemovableDrive) AppBackupWorker.unschedule(applicationContext)
            return
        }

        if (backupManager.currentTransport == TRANSPORT_ID) {
            backupManager.setFrameworkSchedulingEnabledForUser(UserHandle.myUserId(), false)
            if (backupManager.isBackupEnabled && !backendManager.isOnRemovableDrive) {
                AppBackupWorker.schedule(applicationContext, settingsManager, UPDATE)
            }
            // cancel old D2D worker
            WorkManager.getInstance(this).cancelUniqueWork("APP_BACKUP")
        }
    }

}

const val MAGIC_PACKAGE_MANAGER: String = PACKAGE_MANAGER_SENTINEL
const val ANCESTRAL_RECORD_KEY = "@ancestral_record@"
const val NO_DATA_END_SENTINEL = "@end@"
const val GLOBAL_METADATA_KEY = "@meta@"
const val ERROR_BACKUP_CANCELLED: Int = BackupManager.ERROR_BACKUP_CANCELLED
const val ERROR_BACKUP_NOT_ALLOWED: Int = BackupManager.ERROR_BACKUP_NOT_ALLOWED

// TODO this doesn't work for LineageOS as they do public debug builds
fun isDebugBuild() = Build.TYPE == "userdebug"

fun <T> permitDiskReads(func: () -> T): T {
    return if (isDebugBuild()) {
        val oldThreadPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(oldThreadPolicy)
                .permitDiskReads()
                .build()
        )
        try {
            func()
        } finally {
            StrictMode.setThreadPolicy(oldThreadPolicy)
        }
    } else {
        func()
    }
}

/**
 * Hack to allow other profiles access to USB backend.
 * @return the context of the device's main user, so use with great care!
 */
@Suppress("MissingPermission")
fun Context.getStorageContext(isUsbStorage: () -> Boolean): Context {
    if (checkSelfPermission(INTERACT_ACROSS_USERS_FULL) == PERMISSION_GRANTED && isUsbStorage()) {
        UserManager.get(this).getProfileParent(user)
            ?.let { parent -> return createContextAsUser(parent, 0) }
    }
    return this
}
