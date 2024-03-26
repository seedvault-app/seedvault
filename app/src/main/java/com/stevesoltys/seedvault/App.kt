package com.stevesoltys.seedvault

import android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
import android.app.Application
import android.app.backup.BackupManager.PACKAGE_MANAGER_SENTINEL
import android.app.backup.IBackupManager
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.ServiceManager.getService
import android.os.StrictMode
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import com.stevesoltys.seedvault.crypto.cryptoModule
import com.stevesoltys.seedvault.header.headerModule
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.metadata.metadataModule
import com.stevesoltys.seedvault.plugins.saf.documentsProviderModule
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.restore.install.installModule
import com.stevesoltys.seedvault.settings.AppListRetriever
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.SettingsViewModel
import com.stevesoltys.seedvault.storage.storageModule
import com.stevesoltys.seedvault.transport.backup.backupModule
import com.stevesoltys.seedvault.transport.restore.restoreModule
import com.stevesoltys.seedvault.ui.files.FileSelectionViewModel
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.recoverycode.RecoveryCodeViewModel
import com.stevesoltys.seedvault.ui.storage.BackupStorageViewModel
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import com.stevesoltys.seedvault.worker.AppBackupWorker
import com.stevesoltys.seedvault.worker.workerModule
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
        single { Clock() }
        factory<IBackupManager> { IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE)) }
        factory { AppListRetriever(this@App, get(), get(), get()) }

        viewModel { SettingsViewModel(this@App, get(), get(), get(), get(), get(), get(), get()) }
        viewModel { RecoveryCodeViewModel(this@App, get(), get(), get(), get(), get(), get()) }
        viewModel { BackupStorageViewModel(this@App, get(), get(), get(), get()) }
        viewModel { RestoreStorageViewModel(this@App, get(), get()) }
        viewModel { RestoreViewModel(this@App, get(), get(), get(), get(), get(), get()) }
        viewModel { FileSelectionViewModel(this@App, get()) }
    }

    override fun onCreate() {
        super.onCreate()
        startKoin()
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
        permitDiskReads {
            migrateTokenFromMetadataToSettingsManager()
        }
        if (!isTest) migrateToOwnScheduling()
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
        documentsProviderModule, // storage plugin
        backupModule,
        restoreModule,
        installModule,
        storageModule,
        workerModule,
        appModule
    )

    private val settingsManager: SettingsManager by inject()
    private val metadataManager: MetadataManager by inject()
    private val backupManager: IBackupManager by inject()

    /**
     * The responsibility for the current token was moved to the [SettingsManager]
     * in the end of 2020.
     * This method migrates the token for existing installs and can be removed
     * after sufficient time has passed.
     */
    private fun migrateTokenFromMetadataToSettingsManager() {
        @Suppress("DEPRECATION")
        val token = metadataManager.getBackupToken()
        if (token != 0L && settingsManager.getToken() == null) {
            settingsManager.setNewToken(token)
        }
    }

    /**
     * Disables the framework scheduling in favor of our own.
     * Introduced in the first half of 2024 and can be removed after a suitable migration period.
     */
    protected open fun migrateToOwnScheduling() {
        if (!isFrameworkSchedulingEnabled()) return // already on own scheduling

        backupManager.setFrameworkSchedulingEnabledForUser(UserHandle.myUserId(), false)
        if (backupManager.isBackupEnabled) {
            AppBackupWorker.schedule(applicationContext, settingsManager, UPDATE)
        }
        // cancel old D2D worker
        WorkManager.getInstance(this).cancelUniqueWork("APP_BACKUP")
    }

    private fun isFrameworkSchedulingEnabled(): Boolean = Settings.Secure.getInt(
        contentResolver, Settings.Secure.BACKUP_SCHEDULING_ENABLED, 1
    ) == 1 // 1 means enabled which is the default

}

const val MAGIC_PACKAGE_MANAGER = PACKAGE_MANAGER_SENTINEL
const val ANCESTRAL_RECORD_KEY = "@ancestral_record@"
const val GLOBAL_METADATA_KEY = "@meta@"

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

@Suppress("MissingPermission")
fun Context.getStorageContext(isUsbStorage: () -> Boolean): Context {
    if (checkSelfPermission(INTERACT_ACROSS_USERS_FULL) == PERMISSION_GRANTED && isUsbStorage()) {
        UserManager.get(this).getProfileParent(user)
            ?.let { parent -> return createContextAsUser(parent, 0) }
    }
    return this
}
