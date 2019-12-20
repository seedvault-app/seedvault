package com.stevesoltys.seedvault

import android.app.Application
import android.app.backup.BackupManager.PACKAGE_MANAGER_SENTINEL
import android.app.backup.IBackupManager
import android.content.Context.BACKUP_SERVICE
import android.os.Build
import android.os.ServiceManager.getService
import com.stevesoltys.seedvault.crypto.cryptoModule
import com.stevesoltys.seedvault.header.headerModule
import com.stevesoltys.seedvault.metadata.metadataModule
import com.stevesoltys.seedvault.plugins.saf.documentsProviderModule
import com.stevesoltys.seedvault.restore.RestoreViewModel
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.settings.SettingsViewModel
import com.stevesoltys.seedvault.transport.backup.backupModule
import com.stevesoltys.seedvault.transport.restore.restoreModule
import com.stevesoltys.seedvault.ui.recoverycode.RecoveryCodeViewModel
import com.stevesoltys.seedvault.ui.storage.BackupStorageViewModel
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class App : Application() {

    private val appModule = module {
        single { SettingsManager(this@App) }
        single { BackupNotificationManager(this@App) }
        single { Clock() }
        factory<IBackupManager> { IBackupManager.Stub.asInterface(getService(BACKUP_SERVICE)) }

        viewModel { SettingsViewModel(this@App, get(), get(), get()) }
        viewModel { RecoveryCodeViewModel(this@App, get()) }
        viewModel { BackupStorageViewModel(this@App, get(), get()) }
        viewModel { RestoreStorageViewModel(this@App, get(), get()) }
        viewModel { RestoreViewModel(this@App, get(), get(), get(), get(), get()) }
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@App)
            modules(listOf(
                    cryptoModule,
                    headerModule,
                    metadataModule,
                    documentsProviderModule,  // storage plugin
                    backupModule,
                    restoreModule,
                    appModule
            ))
        }
    }

}

const val MAGIC_PACKAGE_MANAGER = PACKAGE_MANAGER_SENTINEL

fun isDebugBuild() = Build.TYPE == "userdebug"
