package com.stevesoltys.seedvault.plugins.saf

import com.stevesoltys.seedvault.transport.backup.BackupPlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val documentsProviderModule = module {
    single { DocumentsStorage(androidContext(), get(), get()) }
    single<BackupPlugin> { DocumentsProviderBackupPlugin(get(), androidContext().packageManager) }
    single<RestorePlugin> { DocumentsProviderRestorePlugin(androidContext(), get()) }
}
