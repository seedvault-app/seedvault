package com.stevesoltys.seedvault.transport

import android.content.Context
import com.stevesoltys.seedvault.Backup
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.header.HeaderWriterImpl
import com.stevesoltys.seedvault.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.metadata.MetadataWriterImpl
import com.stevesoltys.seedvault.transport.backup.BackupCoordinator
import com.stevesoltys.seedvault.transport.backup.FullBackup
import com.stevesoltys.seedvault.transport.backup.InputFactory
import com.stevesoltys.seedvault.transport.backup.KVBackup
import com.stevesoltys.seedvault.transport.backup.plugins.DocumentsProviderBackupPlugin
import com.stevesoltys.seedvault.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.seedvault.transport.restore.FullRestore
import com.stevesoltys.seedvault.transport.restore.KVRestore
import com.stevesoltys.seedvault.transport.restore.OutputFactory
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import com.stevesoltys.seedvault.transport.restore.plugins.DocumentsProviderRestorePlugin

class PluginManager(context: Context) {

    // We can think about using an injection framework such as Dagger, Koin or Kodein to simplify this.

    private val settingsManager = (context.applicationContext as Backup).settingsManager
    private val storage = DocumentsStorage(context, settingsManager)

    private val headerWriter = HeaderWriterImpl()
    private val headerReader = HeaderReaderImpl()
    private val cipherFactory = CipherFactoryImpl(Backup.keyManager)
    private val crypto = CryptoImpl(cipherFactory, headerWriter, headerReader)
    private val metadataWriter = MetadataWriterImpl(crypto)
    private val metadataReader = MetadataReaderImpl(crypto)


    private val backupPlugin = DocumentsProviderBackupPlugin(storage, context.packageManager)
    private val inputFactory = InputFactory()
    private val kvBackup = KVBackup(backupPlugin.kvBackupPlugin, inputFactory, headerWriter, crypto)
    private val fullBackup = FullBackup(backupPlugin.fullBackupPlugin, inputFactory, headerWriter, crypto)
    private val notificationManager = (context.applicationContext as Backup).notificationManager

    internal val backupCoordinator = BackupCoordinator(context, backupPlugin, kvBackup, fullBackup, metadataWriter, settingsManager, notificationManager)


    private val restorePlugin = DocumentsProviderRestorePlugin(storage)
    private val outputFactory = OutputFactory()
    private val kvRestore = KVRestore(restorePlugin.kvRestorePlugin, outputFactory, headerReader, crypto)
    private val fullRestore = FullRestore(restorePlugin.fullRestorePlugin, outputFactory, headerReader, crypto)

    internal val restoreCoordinator = RestoreCoordinator(settingsManager, restorePlugin, kvRestore, fullRestore, metadataReader)

}
