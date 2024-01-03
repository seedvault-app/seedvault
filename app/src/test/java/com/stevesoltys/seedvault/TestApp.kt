package com.stevesoltys.seedvault

import com.stevesoltys.seedvault.service.crypto.CipherFactory
import com.stevesoltys.seedvault.service.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.service.crypto.CryptoService
import com.stevesoltys.seedvault.service.crypto.CryptoServiceImpl
import com.stevesoltys.seedvault.service.crypto.KeyManager
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.service.header.headerModule
import com.stevesoltys.seedvault.service.metadata.metadataModule
import com.stevesoltys.seedvault.service.storage.saf.documentsProviderModule
import com.stevesoltys.seedvault.ui.restore.apk.installModule
import com.stevesoltys.seedvault.service.settings.SettingsService
import com.stevesoltys.seedvault.service.app.backup.backupModule
import com.stevesoltys.seedvault.service.app.restore.restoreModule
import com.stevesoltys.seedvault.util.TimeSource
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class TestApp : App() {

    private val testCryptoModule = module {
        factory<CipherFactory> { CipherFactoryImpl(get()) }
        single<KeyManager> { KeyManagerTestImpl() }
        single<CryptoService> { CryptoServiceImpl(get(), get(), get()) }
    }
    private val appModule = module {
        single { TimeSource() }
        single { SettingsService(this@TestApp) }
    }

    override fun startKoin() = startKoin {
        androidContext(this@TestApp)
        modules(
            listOf(
                testCryptoModule,
                headerModule,
                metadataModule,
                documentsProviderModule, // storage plugin
                backupModule,
                restoreModule,
                installModule,
                appModule
            )
        )
    }
}
