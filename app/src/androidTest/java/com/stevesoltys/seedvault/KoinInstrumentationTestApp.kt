package com.stevesoltys.seedvault

import com.stevesoltys.seedvault.ui.restore.RestoreViewModel
import com.stevesoltys.seedvault.service.app.backup.full.FullBackupService
import com.stevesoltys.seedvault.service.app.backup.InputFactory
import com.stevesoltys.seedvault.service.app.backup.kv.KVBackupService
import com.stevesoltys.seedvault.service.app.restore.full.FullRestore
import com.stevesoltys.seedvault.service.app.restore.kv.KVRestore
import com.stevesoltys.seedvault.service.app.restore.OutputFactory
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import com.stevesoltys.seedvault.ui.storage.BackupStorageViewModel
import com.stevesoltys.seedvault.ui.storage.RestoreStorageViewModel
import io.mockk.spyk
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module

internal var currentRestoreViewModel: RestoreViewModel? = null
internal var currentBackupStorageViewModel: BackupStorageViewModel? = null
internal var currentRestoreStorageViewModel: RestoreStorageViewModel? = null

class KoinInstrumentationTestApp : App() {

    override fun appModules(): List<Module> {
        val testModule = module {
            val context = this@KoinInstrumentationTestApp

            single { spyk(BackupNotificationManager(context)) }
            single { spyk(FullBackupService(get(), get(), get(), get())) }
            single { spyk(KVBackupService(get(), get(), get(), get(), get())) }
            single { spyk(InputFactory()) }

            single { spyk(FullRestore(get(), get(), get(), get(), get())) }
            single { spyk(KVRestore(get(), get(), get(), get(), get(), get())) }
            single { spyk(OutputFactory()) }

            viewModel {
                currentRestoreViewModel =
                    spyk(RestoreViewModel(context, get(), get(), get(), get(), get(), get()))
                currentRestoreViewModel!!
            }

            viewModel {
                currentBackupStorageViewModel =
                    spyk(BackupStorageViewModel(context, get(), get(), get(), get()))
                currentBackupStorageViewModel!!
            }

            viewModel {
                currentRestoreStorageViewModel =
                    spyk(RestoreStorageViewModel(context, get(), get()))
                currentRestoreStorageViewModel!!
            }
        }

        return super.appModules().plus(testModule)
    }
}
