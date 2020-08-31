package com.stevesoltys.seedvault.plugins.saf

import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.transport.backup.BackupTest
import com.stevesoltys.seedvault.transport.backup.FullBackupPlugin
import com.stevesoltys.seedvault.transport.backup.KVBackupPlugin
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

@Suppress("BlockingMethodInNonBlockingContext")
internal class BackupPluginTest : BackupTest() {

    private val storage = mockk<DocumentsStorage>()
    private val kvBackupPlugin: KVBackupPlugin = mockk<DocumentsProviderKVBackup>()
    private val fullBackupPlugin: FullBackupPlugin = mockk<DocumentsProviderFullBackup>()

    private val plugin = DocumentsProviderBackupPlugin(
        context,
        storage,
        kvBackupPlugin,
        fullBackupPlugin
    )

    private val setDir: DocumentFile = mockk()
    private val kvDir: DocumentFile = mockk()
    private val fullDir: DocumentFile = mockk()

    init {
        // to mock extension functions on DocumentFile
        mockkStatic("com.stevesoltys.seedvault.plugins.saf.DocumentsStorageKt")
    }

    @Test
    fun `test startNewRestoreSet`() = runBlocking {
        every { storage.reset(token) } just Runs
        every { storage getProperty "rootBackupDir" } returns setDir

        plugin.startNewRestoreSet(token)
    }

    @Test
    fun `test initializeDevice`() = runBlocking {
        // get current set dir and for that the current token
        every { storage getProperty "currentToken" } returns token
        every { settingsManager.getToken() } returns token
        coEvery { storage.getSetDir(token) } returns setDir
        // delete contents of current set dir
        coEvery { setDir.listFilesBlocking(context) } returns listOf(kvDir)
        every { kvDir.delete() } returns true
        // reset storage
        every { storage.reset(null) } just Runs
        // create kv and full dir
        every { storage getProperty "currentKvBackupDir" } returns kvDir
        every { storage getProperty "currentFullBackupDir" } returns fullDir

        plugin.initializeDevice()
    }

}
